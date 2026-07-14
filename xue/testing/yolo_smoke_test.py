"""Minimal local smoke test for an Ultralytics YOLO .pt model."""

from argparse import ArgumentParser
from pathlib import Path

import numpy as np
from ultralytics import YOLO


DEFAULT_MODEL = Path(r"D:\Edge data\model.pt")


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--image", type=Path, help="Optional image to run inference on")
    parser.add_argument("--device", default="cpu", help="For example: cpu or 0")
    args = parser.parse_args()

    if not args.model.is_file():
        raise FileNotFoundError(f"Model not found: {args.model}")
    if args.image is not None and not args.image.is_file():
        raise FileNotFoundError(f"Image not found: {args.image}")

    model = YOLO(str(args.model))
    source = str(args.image) if args.image else np.zeros((640, 640, 3), dtype=np.uint8)
    result = model.predict(source=source, imgsz=640, device=args.device, verbose=False)[0]
    detection_count = 0 if result.boxes is None else len(result.boxes)

    print("YOLO model smoke test passed")
    print(f"model: {args.model}")
    print(f"task: {model.task}")
    print(f"classes: {model.names}")
    print(f"input: {args.image or 'generated 640x640 blank image'}")
    print(f"detections: {detection_count}")
    print(f"timing_ms: {result.speed}")


if __name__ == "__main__":
    main()
