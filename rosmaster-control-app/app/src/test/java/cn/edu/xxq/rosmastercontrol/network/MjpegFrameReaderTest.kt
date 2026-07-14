package cn.edu.xxq.rosmastercontrol.network

import java.io.IOException
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MjpegFrameReaderTest {
    @Test
    fun `reads consecutive length-delimited multipart frames`() {
        val first = jpeg(1, 2, 3)
        val second = jpeg(9, 8)
        val source = Buffer().apply {
            writeUtf8("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${first.size}\r\n\r\n")
            write(first)
            writeUtf8("\r\n--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${second.size}\r\n\r\n")
            write(second)
            writeUtf8("\r\n--frame--\r\n")
        }

        val reader = MjpegFrameReader(source)

        assertArrayEquals(first, reader.readFrame())
        assertArrayEquals(second, reader.readFrame())
        assertNull(reader.readFrame())
    }

    @Test
    fun `accepts case-insensitive content length and leading response noise`() {
        val frame = jpeg(42)
        val source = Buffer().apply {
            writeUtf8("ignored line\r\n\r\n--camera\ncontent-length: ${frame.size}\nX-Test: yes\n\n")
            write(frame)
        }

        assertArrayEquals(frame, MjpegFrameReader(source).readFrame())
    }

    @Test
    fun `falls back to jpeg markers when content length is absent`() {
        val frame = jpeg(3, 4, 5, 6)
        val source = Buffer().apply {
            writeUtf8("--frame\r\nContent-Type: image/jpeg\r\n\r\n")
            write(frame)
            writeUtf8("\r\n")
        }

        assertArrayEquals(frame, MjpegFrameReader(source).readFrame())
    }

    @Test(expected = IOException::class)
    fun `rejects a frame larger than configured limit`() {
        val frame = jpeg(1, 2, 3)
        val source = Buffer().apply {
            writeUtf8("--frame\r\nContent-Length: ${frame.size}\r\n\r\n")
            write(frame)
        }

        MjpegFrameReader(source, maxFrameBytes = frame.size - 1).readFrame()
    }

    @Test(expected = IOException::class)
    fun `rejects length-delimited non-jpeg data`() {
        val source = Buffer().apply {
            writeUtf8("--frame\r\nContent-Length: 4\r\n\r\nnope")
        }

        MjpegFrameReader(source).readFrame()
    }

    private fun jpeg(vararg payload: Int): ByteArray = byteArrayOf(
        0xff.toByte(),
        0xd8.toByte(),
        *payload.map(Int::toByte).toByteArray(),
        0xff.toByte(),
        0xd9.toByte(),
    )
}
