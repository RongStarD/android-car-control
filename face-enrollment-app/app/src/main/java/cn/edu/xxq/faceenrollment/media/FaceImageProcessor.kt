package cn.edu.xxq.faceenrollment.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import cn.edu.xxq.faceenrollment.data.MAX_FACE_IMAGE_BYTES
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

private const val MAX_IMAGE_EDGE = 1_600
private const val THUMBNAIL_EDGE = 240
private const val JPEG_QUALITY = 85
private const val THUMBNAIL_QUALITY = 78

data class FaceImageSample(
    val id: String = UUID.randomUUID().toString(),
    val jpeg: ByteArray,
    val thumbnailJpeg: ByteArray,
    val width: Int,
    val height: Int,
    val sourceLabel: String,
) {
    val sizeBytes: Int get() = jpeg.size
}

class ImageProcessingException(message: String, cause: Throwable? = null) : Exception(message, cause)

class FaceImageProcessor(private val resolver: ContentResolver) {
    fun process(uri: Uri, sourceLabel: String): FaceImageSample {
        try {
            val bounds = decodeBounds(uri)
            if (bounds.first <= 0 || bounds.second <= 0) {
                throw ImageProcessingException("无法读取图片尺寸")
            }
            if (bounds.first > 50_000 || bounds.second > 50_000) {
                throw ImageProcessingException("图片尺寸异常，无法安全处理")
            }

            val orientation = readOrientation(uri)
            val decoded = decodeSampled(uri, bounds.first, bounds.second)
            var working = applyExifOrientation(decoded, orientation)
            if (working !== decoded) decoded.recycle()

            val longest = max(working.width, working.height)
            if (longest > MAX_IMAGE_EDGE) {
                val scale = MAX_IMAGE_EDGE.toFloat() / longest.toFloat()
                val scaled = Bitmap.createScaledBitmap(
                    working,
                    (working.width * scale).roundToInt().coerceAtLeast(1),
                    (working.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== working) working.recycle()
                working = scaled
            }

            if (working.hasAlpha()) {
                val opaque = Bitmap.createBitmap(working.width, working.height, Bitmap.Config.ARGB_8888)
                Canvas(opaque).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(working, 0f, 0f, null)
                }
                working.recycle()
                working = opaque
            }

            val jpeg = compress(working, JPEG_QUALITY)
            if (jpeg.isEmpty() || jpeg.size > MAX_FACE_IMAGE_BYTES) {
                working.recycle()
                throw ImageProcessingException("重编码后的图片超过 5 MiB，请换一张照片")
            }

            val thumbScale = (THUMBNAIL_EDGE.toFloat() / max(working.width, working.height))
                .coerceAtMost(1f)
            val thumbnail = Bitmap.createScaledBitmap(
                working,
                (working.width * thumbScale).roundToInt().coerceAtLeast(1),
                (working.height * thumbScale).roundToInt().coerceAtLeast(1),
                true,
            )
            val thumbnailJpeg = compress(thumbnail, THUMBNAIL_QUALITY)
            if (thumbnail !== working) thumbnail.recycle()
            val width = working.width
            val height = working.height
            working.recycle()

            return FaceImageSample(
                jpeg = jpeg,
                thumbnailJpeg = thumbnailJpeg,
                width = width,
                height = height,
                sourceLabel = sourceLabel,
            )
        } catch (error: ImageProcessingException) {
            throw error
        } catch (error: SecurityException) {
            throw ImageProcessingException("没有权限读取所选图片", error)
        } catch (error: OutOfMemoryError) {
            throw ImageProcessingException("图片过大，手机内存不足", error)
        } catch (error: Exception) {
            throw ImageProcessingException(error.message ?: "图片处理失败", error)
        }
    }

    private fun decodeBounds(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).useRequired { stream -> BitmapFactory.decodeStream(stream, null, options) }
        return options.outWidth to options.outHeight
    }

    private fun decodeSampled(uri: Uri, width: Int, height: Int): Bitmap {
        var sampleSize = 1
        while (max(width, height) / sampleSize > MAX_IMAGE_EDGE * 2) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri).useRequired { stream ->
            BitmapFactory.decodeStream(stream, null, options)
                ?: throw ImageProcessingException("图片格式不受支持或文件已损坏")
        }
    }

    private fun readOrientation(uri: Uri): Int {
        return try {
            resolver.openInputStream(uri).useRequired { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().use { output ->
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            throw ImageProcessingException("JPEG 重编码失败")
        }
        output.toByteArray()
    }
}

private inline fun <T> java.io.InputStream?.useRequired(block: (java.io.InputStream) -> T): T {
    val stream = this ?: throw ImageProcessingException("无法打开图片")
    return stream.use(block)
}
