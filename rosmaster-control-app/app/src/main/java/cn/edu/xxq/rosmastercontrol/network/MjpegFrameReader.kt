package cn.edu.xxq.rosmastercontrol.network

import java.io.EOFException
import java.io.IOException
import okio.BufferedSource

/**
 * Reads individual JPEG images from an MJPEG multipart response body.
 *
 * The Jetson bridge includes Content-Length for every part, which is the preferred and fastest
 * path. The marker-based fallback keeps the client compatible with cameras that omit that header.
 */
internal class MjpegFrameReader(
    private val source: BufferedSource,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
) {
    init {
        require(maxFrameBytes >= 2) { "maxFrameBytes must be at least 2" }
    }

    fun readFrame(): ByteArray? {
        while (true) {
            val boundaryLine = try {
                source.readUtf8LineStrict(MAX_HEADER_LINE_BYTES)
            } catch (_: EOFException) {
                return null
            }

            if (!boundaryLine.startsWith("--")) continue

            var contentLength: Int? = null
            while (true) {
                val headerLine = try {
                    source.readUtf8LineStrict(MAX_HEADER_LINE_BYTES)
                } catch (_: EOFException) {
                    return null
                }
                if (headerLine.isEmpty()) break

                val separator = headerLine.indexOf(':')
                if (separator <= 0) continue
                if (headerLine.substring(0, separator).trim().equals("Content-Length", ignoreCase = true)) {
                    contentLength = headerLine.substring(separator + 1).trim().toIntOrNull()
                }
            }

            val frame = if (contentLength != null) {
                readLengthDelimitedFrame(contentLength)
            } else {
                readMarkerDelimitedFrame()
            }
            if (frame != null) return frame
        }
    }

    private fun readLengthDelimitedFrame(length: Int): ByteArray {
        if (length !in 2..maxFrameBytes) {
            throw IOException("Invalid MJPEG frame length: $length")
        }
        val frame = source.readByteArray(length.toLong())
        if (!frame.hasJpegMarkers()) {
            throw IOException("MJPEG part is not a JPEG image")
        }
        return frame
    }

    private fun readMarkerDelimitedFrame(): ByteArray? {
        var previous = -1
        var started = false
        val frame = ArrayList<Byte>(64 * 1024)

        while (!source.exhausted()) {
            val current = source.readByte().toInt() and 0xff
            if (!started) {
                if (previous == JPEG_MARKER_PREFIX && current == JPEG_START) {
                    frame.add(JPEG_MARKER_PREFIX.toByte())
                    frame.add(JPEG_START.toByte())
                    started = true
                }
                previous = current
                continue
            }

            frame.add(current.toByte())
            if (frame.size > maxFrameBytes) {
                throw IOException("MJPEG frame exceeds $maxFrameBytes bytes")
            }
            if (previous == JPEG_MARKER_PREFIX && current == JPEG_END) {
                return frame.toByteArray()
            }
            previous = current
        }
        return null
    }

    private fun ByteArray.hasJpegMarkers(): Boolean =
        size >= 4 &&
            this[0].toInt() and 0xff == JPEG_MARKER_PREFIX &&
            this[1].toInt() and 0xff == JPEG_START &&
            this[size - 2].toInt() and 0xff == JPEG_MARKER_PREFIX &&
            this[size - 1].toInt() and 0xff == JPEG_END

    private companion object {
        const val DEFAULT_MAX_FRAME_BYTES = 5 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 16L * 1024L
        const val JPEG_MARKER_PREFIX = 0xff
        const val JPEG_START = 0xd8
        const val JPEG_END = 0xd9
    }
}
