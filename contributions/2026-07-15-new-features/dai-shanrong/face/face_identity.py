"""Local face enrollment and identification using YOLO + YuNet + SFace."""

from __future__ import annotations

import argparse
import sys
import unicodedata
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from ultralytics import YOLO


ROOT = Path(__file__).resolve().parent
DEFAULT_YOLO = Path(r"D:\Edge data\model.pt")
DEFAULT_SFACE = ROOT / "models" / "face_recognition_sface_2021dec.onnx"
DEFAULT_YUNET = ROOT / "models" / "face_detection_yunet_2023mar.onnx"
DEFAULT_DB = ROOT / "face_data" / "face_db.npz"
DEFAULT_THRESHOLD = 0.45
DEFAULT_MARGIN = 0.05


def read_image(path: Path) -> np.ndarray:
    """Read image paths containing non-ASCII characters on Windows."""
    if not path.is_file():
        raise FileNotFoundError(f"Image not found: {path}")
    image = cv2.imdecode(np.fromfile(path, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError(f"Cannot decode image: {path}")
    return image


def write_image(path: Path, image: np.ndarray) -> None:
    """Write image paths containing non-ASCII characters on Windows."""
    suffix = path.suffix.lower() or ".jpg"
    if suffix not in {".jpg", ".jpeg", ".png", ".bmp", ".webp"}:
        raise ValueError(f"Unsupported output image type: {suffix}")
    path.parent.mkdir(parents=True, exist_ok=True)
    ok, encoded = cv2.imencode(suffix, image)
    if not ok:
        raise RuntimeError(f"Cannot encode output image: {path}")
    encoded.tofile(path)


def normalized(vector: np.ndarray) -> np.ndarray:
    vector = np.asarray(vector, dtype=np.float32).reshape(-1).copy()
    length = float(np.linalg.norm(vector))
    if not np.isfinite(length) or length <= 1e-12:
        raise ValueError("Face feature is invalid")
    return vector / length


def normalized_name(name: str) -> str:
    name = unicodedata.normalize("NFC", name).strip()
    if not name:
        raise ValueError("Name cannot be empty")
    if len(name) > 100 or any(unicodedata.category(char).startswith("C") for char in name):
        raise ValueError("Name is too long or contains control characters")
    return name


class FaceDatabase:
    """A pickle-free local database of names and normalized face embeddings."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.names = np.empty((0,), dtype=np.str_)
        self.embeddings = np.empty((0, 0), dtype=np.float32)
        if path.exists():
            with np.load(path, allow_pickle=False) as data:
                self.names = np.asarray(data["names"]).astype(np.str_)
                self.embeddings = np.asarray(data["embeddings"], dtype=np.float32)
            if self.embeddings.ndim != 2 or len(self.names) != len(self.embeddings):
                raise ValueError(f"Invalid face database: {path}")
            if len(self.embeddings):
                self.embeddings = np.stack([normalized(row) for row in self.embeddings])

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp.npz")
        np.savez_compressed(temporary, names=self.names, embeddings=self.embeddings)
        temporary.replace(self.path)

    def add(self, name: str, embeddings: list[np.ndarray], replace: bool = False) -> None:
        name = normalized_name(name)
        if not embeddings:
            raise ValueError("No face samples to save")
        new_embeddings = np.stack([normalized(item) for item in embeddings])
        if replace and len(self.names):
            keep = self.names != name
            self.names = self.names[keep]
            self.embeddings = self.embeddings[keep]
        if len(self.embeddings) and self.embeddings.shape[1] != new_embeddings.shape[1]:
            raise ValueError("Embedding size does not match the existing database")
        if not len(self.embeddings):
            self.embeddings = new_embeddings
        else:
            self.embeddings = np.concatenate([self.embeddings, new_embeddings])
        new_names = np.asarray([name] * len(new_embeddings), dtype=np.str_)
        self.names = np.concatenate([self.names, new_names])
        self.save()

    def delete(self, name: str) -> int:
        mask = self.names == name
        deleted = int(mask.sum())
        if deleted:
            self.names = self.names[~mask]
            self.embeddings = self.embeddings[~mask]
            self.save()
        return deleted

    def counts(self) -> list[tuple[str, int]]:
        ordered_names = dict.fromkeys(self.names.tolist())
        return [(name, int((self.names == name).sum())) for name in ordered_names]

    def identify(
        self, embedding: np.ndarray, threshold: float, margin: float
    ) -> tuple[str, float]:
        if not len(self.names):
            return "unknown", float("nan")
        query = normalized(embedding)
        identities: list[str] = []
        centroids: list[np.ndarray] = []
        for name, _ in self.counts():
            centroid = normalized(self.embeddings[self.names == name].mean(axis=0))
            identities.append(name)
            centroids.append(centroid)
        scores = np.stack(centroids) @ query
        best = int(np.argmax(scores))
        score = float(scores[best])
        if score < threshold:
            return "unknown", score
        if len(scores) > 1:
            second_best = float(np.partition(scores, -2)[-2])
            if score - second_best < margin:
                return "uncertain", score
        return identities[best], score


@dataclass
class DetectedFace:
    box: tuple[int, int, int, int]
    confidence: float
    embedding: np.ndarray | None


class FaceEngine:
    def __init__(
        self,
        yolo_path: Path,
        sface_path: Path,
        yunet_path: Path,
        device: str,
        detection_confidence: float,
    ) -> None:
        for model_path in (yolo_path, sface_path, yunet_path):
            if not model_path.is_file():
                raise FileNotFoundError(f"Model not found: {model_path}")
        self.yolo = YOLO(str(yolo_path))
        self.sface = cv2.FaceRecognizerSF_create(str(sface_path), "")
        self.yunet = cv2.FaceDetectorYN_create(
            str(yunet_path), "", (320, 320), 0.8, 0.3, 5000
        )
        self.device = device
        self.detection_confidence = detection_confidence

    @staticmethod
    def _iou(box1: np.ndarray, box2: np.ndarray) -> float:
        left = max(float(box1[0]), float(box2[0]))
        top = max(float(box1[1]), float(box2[1]))
        right = min(float(box1[2]), float(box2[2]))
        bottom = min(float(box1[3]), float(box2[3]))
        intersection = max(0.0, right - left) * max(0.0, bottom - top)
        area1 = max(0.0, float(box1[2] - box1[0])) * max(
            0.0, float(box1[3] - box1[1])
        )
        area2 = max(0.0, float(box2[2] - box2[0])) * max(
            0.0, float(box2[3] - box2[1])
        )
        union = area1 + area2 - intersection
        return intersection / union if union > 0 else 0.0

    def _yolo_faces(self, image: np.ndarray) -> list[tuple[np.ndarray, float]]:
        result = self.yolo.predict(
            source=image,
            imgsz=640,
            conf=self.detection_confidence,
            device=self.device,
            verbose=False,
        )[0]
        if result.boxes is None or not len(result.boxes):
            return []
        boxes = result.boxes.xyxy.detach().cpu().numpy()
        confidences = result.boxes.conf.detach().cpu().numpy()
        return [(box.astype(np.float32), float(conf)) for box, conf in zip(boxes, confidences)]

    def _yunet_faces(self, image: np.ndarray) -> np.ndarray:
        height, width = image.shape[:2]
        self.yunet.setInputSize((width, height))
        _, faces = self.yunet.detect(image)
        if faces is None:
            return np.empty((0, 15), dtype=np.float32)
        return np.asarray(faces, dtype=np.float32)

    def _match_landmarks(
        self, yolo_faces: list[tuple[np.ndarray, float]], landmarks: np.ndarray
    ) -> dict[int, int]:
        """Greedily create a one-to-one YOLO-box to YuNet-landmark assignment."""
        candidates: list[tuple[float, int, int]] = []
        for yolo_index, (yolo_box, _) in enumerate(yolo_faces):
            for landmark_index, face in enumerate(landmarks):
                x, y, width, height = face[:4]
                face_box = np.array([x, y, x + width, y + height], dtype=np.float32)
                score = self._iou(yolo_box, face_box)
                if score >= 0.10:
                    candidates.append((score, yolo_index, landmark_index))
        matches: dict[int, int] = {}
        used_landmarks: set[int] = set()
        for _, yolo_index, landmark_index in sorted(candidates, reverse=True):
            if yolo_index in matches or landmark_index in used_landmarks:
                continue
            matches[yolo_index] = landmark_index
            used_landmarks.add(landmark_index)
        return matches

    def _embedding_for_landmarks(
        self, image: np.ndarray, landmarks: np.ndarray
    ) -> np.ndarray:
        aligned = self.sface.alignCrop(image, landmarks[:-1])
        return normalized(self.sface.feature(aligned))

    def analyze(self, image: np.ndarray) -> list[DetectedFace]:
        yolo_faces = self._yolo_faces(image)
        if not yolo_faces:
            return []
        landmarks = self._yunet_faces(image)
        landmark_matches = self._match_landmarks(yolo_faces, landmarks)
        height, width = image.shape[:2]
        detected: list[DetectedFace] = []
        for index, (box, confidence) in enumerate(yolo_faces):
            box[0::2] = np.clip(box[0::2], 0, width - 1)
            box[1::2] = np.clip(box[1::2], 0, height - 1)
            x1, y1, x2, y2 = (int(round(value)) for value in box)
            landmark_index = landmark_matches.get(index)
            embedding = (
                None
                if landmark_index is None
                else self._embedding_for_landmarks(image, landmarks[landmark_index])
            )
            detected.append(
                DetectedFace(
                    box=(x1, y1, x2, y2),
                    confidence=confidence,
                    embedding=embedding,
                )
            )
        return detected


@lru_cache(maxsize=4)
def display_font(size: int) -> ImageFont.ImageFont:
    candidates = [
        Path(r"C:\Windows\Fonts\msyh.ttc"),
        Path(r"C:\Windows\Fonts\simhei.ttf"),
        Path(r"C:\Windows\Fonts\arial.ttf"),
    ]
    for path in candidates:
        if path.is_file():
            return ImageFont.truetype(str(path), size)
    return ImageFont.load_default()


def put_label(
    image: np.ndarray,
    text: str,
    position: tuple[int, int],
    color: tuple[int, int, int],
) -> np.ndarray:
    """Draw labels with support for Chinese names."""
    rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    canvas = Image.fromarray(rgb)
    draw = ImageDraw.Draw(canvas)
    x, y = position
    draw.text(
        (x, max(0, y - 25)),
        text,
        font=display_font(20),
        fill=(color[2], color[1], color[0]),
        stroke_width=1,
        stroke_fill=(0, 0, 0),
    )
    return cv2.cvtColor(np.asarray(canvas), cv2.COLOR_RGB2BGR)


def annotate(
    image: np.ndarray,
    faces: list[DetectedFace],
    database: FaceDatabase | None,
    threshold: float,
    margin: float = DEFAULT_MARGIN,
) -> tuple[np.ndarray, list[tuple[str, float]]]:
    output = image.copy()
    identities: list[tuple[str, float]] = []
    for face in faces:
        if face.embedding is None:
            name, score = "unaligned", float("nan")
        elif database is None:
            name, score = "face", float("nan")
        else:
            name, score = database.identify(face.embedding, threshold, margin)
        identities.append((name, score))
        known = name not in {"unknown", "uncertain", "unaligned", "face"}
        color = (0, 180, 0) if known else (0, 0, 255)
        x1, y1, x2, y2 = face.box
        cv2.rectangle(output, (x1, y1), (x2, y2), color, 2)
        label = name if not np.isfinite(score) else f"{name} {score:.3f}"
        output = put_label(output, label, (x1, y1), color)
    return output, identities


def require_single_aligned_face(engine: FaceEngine, image: np.ndarray, source: str) -> np.ndarray:
    faces = engine.analyze(image)
    if len(faces) != 1:
        raise ValueError(f"{source}: expected exactly one face, found {len(faces)}")
    x1, y1, x2, y2 = faces[0].box
    if min(x2 - x1, y2 - y1) < 80:
        raise ValueError(f"{source}: face is too small; use a face at least 80 pixels wide/high")
    if faces[0].embedding is None:
        raise ValueError(f"{source}: face found but 5-point alignment failed; use a clear frontal image")
    return faces[0].embedding


def open_camera(index: int) -> cv2.VideoCapture:
    camera = cv2.VideoCapture(index, cv2.CAP_DSHOW)
    if not camera.isOpened():
        camera.release()
        camera = cv2.VideoCapture(index)
    if not camera.isOpened():
        raise RuntimeError(f"Cannot open camera {index}")
    return camera


def enroll_images(args: argparse.Namespace, engine: FaceEngine, database: FaceDatabase) -> None:
    embeddings = [
        require_single_aligned_face(engine, read_image(path), str(path)) for path in args.images
    ]
    database.add(args.name, embeddings, replace=args.replace)
    total = dict(database.counts())[args.name]
    print(f"Enrolled {args.name}: added {len(embeddings)} sample(s), total {total}")
    print(f"Database: {database.path}")


def enroll_camera(args: argparse.Namespace, engine: FaceEngine, database: FaceDatabase) -> None:
    camera = open_camera(args.camera)
    embeddings: list[np.ndarray] = []
    print("Press SPACE to capture one sample; press Q or ESC to stop.")
    try:
        while len(embeddings) < args.samples:
            ok, frame = camera.read()
            if not ok:
                raise RuntimeError("Cannot read a frame from the camera")
            faces = engine.analyze(frame)
            preview, _ = annotate(frame, faces, None, DEFAULT_THRESHOLD)
            preview = put_label(
                preview,
                f"samples {len(embeddings)}/{args.samples} | SPACE capture | Q quit",
                (10, 30),
                (255, 255, 255),
            )
            cv2.imshow("Face enrollment", preview)
            key = cv2.waitKey(1) & 0xFF
            if key in (ord("q"), 27):
                break
            if key == 32:
                if len(faces) != 1:
                    print(f"Skipped: expected one face, found {len(faces)}")
                elif min(
                    faces[0].box[2] - faces[0].box[0],
                    faces[0].box[3] - faces[0].box[1],
                ) < 80:
                    print("Skipped: face is too small; move closer to the camera")
                elif faces[0].embedding is None:
                    print("Skipped: face alignment failed; face the camera clearly")
                else:
                    embeddings.append(faces[0].embedding.copy())
                    print(f"Captured sample {len(embeddings)}/{args.samples}")
    finally:
        camera.release()
        cv2.destroyAllWindows()
    if not embeddings:
        raise ValueError("No samples were captured")
    database.add(args.name, embeddings, replace=args.replace)
    total = dict(database.counts())[args.name]
    print(f"Enrolled {args.name}: added {len(embeddings)} sample(s), total {total}")
    print(f"Database: {database.path}")


def recognize_image(args: argparse.Namespace, engine: FaceEngine, database: FaceDatabase) -> None:
    image = read_image(args.image)
    faces = engine.analyze(image)
    output, identities = annotate(image, faces, database, args.threshold, args.margin)
    write_image(args.output, output)
    print(f"Faces: {len(faces)}")
    for index, ((name, score), face) in enumerate(zip(identities, faces), start=1):
        score_text = "n/a" if not np.isfinite(score) else f"{score:.4f}"
        print(f"{index}: name={name}, similarity={score_text}, box={face.box}")
    print(f"Output: {args.output.resolve()}")


def recognize_camera(args: argparse.Namespace, engine: FaceEngine, database: FaceDatabase) -> None:
    camera = open_camera(args.camera)
    print("Press Q or ESC to stop.")
    try:
        while True:
            ok, frame = camera.read()
            if not ok:
                raise RuntimeError("Cannot read a frame from the camera")
            faces = engine.analyze(frame)
            output, _ = annotate(frame, faces, database, args.threshold, args.margin)
            cv2.imshow("Face recognition", output)
            key = cv2.waitKey(1) & 0xFF
            if key in (ord("q"), 27):
                break
    finally:
        camera.release()
        cv2.destroyAllWindows()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--yolo", type=Path, default=DEFAULT_YOLO)
    parser.add_argument("--sface", type=Path, default=DEFAULT_SFACE)
    parser.add_argument("--yunet", type=Path, default=DEFAULT_YUNET)
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument("--device", default="cpu", help="YOLO device: cpu or GPU index such as 0")
    parser.add_argument("--det-conf", type=float, default=0.45)
    commands = parser.add_subparsers(dest="command", required=True)

    enroll = commands.add_parser("enroll", help="Enroll one person from one or more images")
    enroll.add_argument("name")
    enroll.add_argument("images", nargs="+", type=Path)
    enroll.add_argument("--replace", action="store_true", help="Replace existing samples for this name")

    enroll_cam = commands.add_parser("enroll-camera", help="Enroll one person from the camera")
    enroll_cam.add_argument("name")
    enroll_cam.add_argument("--camera", type=int, default=0)
    enroll_cam.add_argument("--samples", type=int, default=5)
    enroll_cam.add_argument("--replace", action="store_true", help="Replace existing samples for this name")

    recognize = commands.add_parser("recognize", help="Recognize faces in one image")
    recognize.add_argument("image", type=Path)
    recognize.add_argument("--output", type=Path, default=ROOT / "recognized.jpg")
    recognize.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    recognize.add_argument("--margin", type=float, default=DEFAULT_MARGIN)

    camera = commands.add_parser("camera", help="Recognize faces from the camera")
    camera.add_argument("--camera", type=int, default=0)
    camera.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    camera.add_argument("--margin", type=float, default=DEFAULT_MARGIN)

    commands.add_parser("list", help="List enrolled identities")
    delete = commands.add_parser("delete", help="Delete one enrolled identity")
    delete.add_argument("name")
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    if hasattr(args, "name"):
        args.name = normalized_name(args.name)
    if not 0.0 < args.det_conf <= 1.0:
        parser.error("--det-conf must be within (0, 1]")
    database = FaceDatabase(args.db)

    if args.command == "list":
        if not database.counts():
            print("No identities enrolled")
        for name, count in database.counts():
            print(f"{name}: {count} sample(s)")
        return 0
    if args.command == "delete":
        deleted = database.delete(args.name)
        print(f"Deleted {args.name}: {deleted} sample(s)")
        return 0 if deleted else 1
    if args.command in {"recognize", "camera"} and not len(database.names):
        raise ValueError("Face database is empty; enroll at least one person first")
    if hasattr(args, "threshold") and not -1.0 <= args.threshold <= 1.0:
        parser.error("--threshold must be within [-1, 1]")
    if hasattr(args, "margin") and not 0.0 <= args.margin <= 2.0:
        parser.error("--margin must be within [0, 2]")
    if args.command == "enroll-camera" and args.samples < 1:
        parser.error("--samples must be at least 1")

    engine = FaceEngine(
        args.yolo, args.sface, args.yunet, args.device, args.det_conf
    )
    if args.command == "enroll":
        enroll_images(args, engine, database)
    elif args.command == "enroll-camera":
        enroll_camera(args, engine, database)
    elif args.command == "recognize":
        recognize_image(args, engine, database)
    elif args.command == "camera":
        recognize_camera(args, engine, database)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (FileNotFoundError, ValueError, RuntimeError, cv2.error) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
