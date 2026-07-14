package cn.edu.xxq.rosmastercontrol.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import cn.edu.xxq.rosmastercontrol.BuildConfig
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

interface MjpegStreamListener {
    fun onConnecting()
    fun onFrame(bitmap: Bitmap)
    fun onError(message: String)
}

/** A cancellable MJPEG connection that automatically retries transient camera/network failures. */
class MjpegStreamClient(
    private val listener: MjpegStreamListener,
    private val retryDelayMs: Long = 1_000,
    private val minimumFrameIntervalMs: Long = 66,
) : Closeable {
    init {
        require(retryDelayMs >= 0) { "retryDelayMs must not be negative" }
        require(minimumFrameIntervalMs >= 0) { "minimumFrameIntervalMs must not be negative" }
    }

    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mjpeg-stream").apply { isDaemon = true }
    }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var activeCall: Call? = null

    fun start(url: String) {
        check(!closed.get()) { "MJPEG client is already closed" }
        check(started.compareAndSet(false, true)) { "MJPEG client is already started" }
        worker.execute { streamWithRetry(url) }
    }

    private fun streamWithRetry(url: String) {
        while (!closed.get() && !Thread.currentThread().isInterrupted) {
            post { listener.onConnecting() }
            try {
                streamOnce(url)
            } catch (error: Exception) {
                if (closed.get() || error is InterruptedException) return
                post { listener.onError(error.userMessage()) }
            } finally {
                activeCall = null
            }

            try {
                if (retryDelayMs > 0) Thread.sleep(retryDelayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun streamOnce(url: String) {
        val request = Request.Builder()
            .url(url)
            .header("X-API-Key", BuildConfig.FACE_API_KEY)
            .header("Accept", "multipart/x-mixed-replace")
            .header("Cache-Control", "no-cache")
            .build()
        val call = httpClient.newCall(request)
        activeCall = call

        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} ${response.message}")
            }
            val body = response.body ?: throw IOException("视频响应没有内容")
            val reader = MjpegFrameReader(body.source())
            var lastDecodedAt = 0L

            while (!closed.get()) {
                val jpeg = reader.readFrame() ?: throw IOException("视频流已结束")
                val now = System.nanoTime() / 1_000_000L
                if (now - lastDecodedAt < minimumFrameIntervalMs) continue

                val bitmap = BitmapFactory.decodeByteArray(
                    jpeg,
                    0,
                    jpeg.size,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    },
                ) ?: throw IOException("无法解码摄像头 JPEG 帧")
                lastDecodedAt = now
                post { listener.onFrame(bitmap) }
            }
        }
    }

    private fun post(block: () -> Unit) {
        if (closed.get()) return
        mainHandler.post {
            if (!closed.get()) block()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeCall?.cancel()
        activeCall = null
        worker.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun Exception.userMessage(): String = when (this) {
        is IOException -> message ?: "视频网络连接中断"
        else -> message ?: "视频流处理失败"
    }
}
