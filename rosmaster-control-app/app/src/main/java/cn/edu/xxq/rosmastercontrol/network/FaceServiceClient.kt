package cn.edu.xxq.rosmastercontrol.network

import cn.edu.xxq.rosmastercontrol.BuildConfig
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

private const val API_KEY_HEADER = "X-API-Key"

data class FacePerson(
    val name: String,
    val samples: Int,
    val updatedAt: String? = null,
)

data class FaceServiceSnapshot(
    val peopleCount: Int,
    val sampleCount: Int,
    val databaseRevision: Long,
    val cameraReady: Boolean,
    val recognitionReady: Boolean,
    val people: List<FacePerson>,
)

sealed interface FaceApiResult<out T> {
    data class Success<T>(val value: T) : FaceApiResult<T>
    data class Failure(val message: String, val statusCode: Int? = null) : FaceApiResult<Nothing>
}

internal object FaceServiceEndpoint {
    fun apiUrl(baseUrl: String, vararg segments: String): HttpUrl? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return null
        val parsed = (if (trimmed.endsWith('/')) trimmed else "$trimmed/").toHttpUrlOrNull()
            ?: return null
        if (parsed.scheme !in setOf("http", "https") || parsed.query != null || parsed.fragment != null) {
            return null
        }
        return parsed.newBuilder().apply { segments.forEach(::addPathSegment) }.build()
    }
}

internal object FaceServiceJson {
    fun parseSnapshot(status: JSONObject, peoplePayload: JSONObject): FaceServiceSnapshot {
        val database = status.optJSONObject("database") ?: JSONObject()
        val camera = status.optJSONObject("camera") ?: JSONObject()
        val recognition = status.optJSONObject("recognition") ?: JSONObject()
        val peopleArray = peoplePayload.optJSONArray("people")
        val people = buildList {
            if (peopleArray != null) {
                for (index in 0 until peopleArray.length()) {
                    val item = peopleArray.optJSONObject(index) ?: continue
                    val name = item.optString("name").trim()
                    if (name.isEmpty()) continue
                    add(
                        FacePerson(
                            name = name,
                            samples = item.optInt("samples", 0).coerceAtLeast(0),
                            updatedAt = item.optString("updated_at").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }
        return FaceServiceSnapshot(
            peopleCount = database.optInt("people", people.size).coerceAtLeast(0),
            sampleCount = database.optInt("samples", people.sumOf(FacePerson::samples)).coerceAtLeast(0),
            databaseRevision = database.optLong(
                "revision",
                peoplePayload.optLong("database_revision", 0L),
            ).coerceAtLeast(0L),
            cameraReady = camera.optBoolean("ready", false),
            recognitionReady = recognition.optBoolean("ready", false),
            people = people.sortedBy { it.name.lowercase() },
        )
    }

    fun errorMessage(payload: JSONObject?): String? {
        val error = payload?.opt("error") ?: return null
        return when (error) {
            is JSONObject -> error.optString("message").trim().ifEmpty {
                error.optString("code").trim()
            }.ifEmpty { null }
            is String -> error.trim().ifEmpty { null }
            else -> null
        }
    }
}

class FaceServiceClient(
    private val apiKey: String = BuildConfig.FACE_API_KEY,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) : Closeable {
    fun load(baseUrl: String): FaceApiResult<FaceServiceSnapshot> {
        val statusUrl = FaceServiceEndpoint.apiUrl(baseUrl, "api", "v1", "status")
            ?: return FaceApiResult.Failure("人脸服务地址无效")
        val peopleUrl = FaceServiceEndpoint.apiUrl(baseUrl, "api", "v1", "people")
            ?: return FaceApiResult.Failure("人脸服务地址无效")
        val status = when (val result = executeJson(request(statusUrl).get().build())) {
            is FaceApiResult.Success -> result.value
            is FaceApiResult.Failure -> return result
        }
        val people = when (val result = executeJson(request(peopleUrl).get().build())) {
            is FaceApiResult.Success -> result.value
            is FaceApiResult.Failure -> return result
        }
        return try {
            FaceApiResult.Success(FaceServiceJson.parseSnapshot(status, people))
        } catch (error: Exception) {
            FaceApiResult.Failure("人脸服务响应无法解析：${error.message ?: "字段不完整"}")
        }
    }

    fun deletePerson(baseUrl: String, name: String): FaceApiResult<Unit> {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return FaceApiResult.Failure("人员姓名不能为空")
        val url = FaceServiceEndpoint.apiUrl(baseUrl, "api", "v1", "people", cleanName)
            ?: return FaceApiResult.Failure("人脸服务地址无效")
        return when (val result = executeJson(request(url).delete().build())) {
            is FaceApiResult.Success -> FaceApiResult.Success(Unit)
            is FaceApiResult.Failure -> result
        }
    }

    private fun request(url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header(API_KEY_HEADER, apiKey)
        .header("Accept", "application/json")

    private fun executeJson(request: Request): FaceApiResult<JSONObject> {
        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(raw)
                } catch (_: JSONException) {
                    null
                }
                if (!response.isSuccessful || json?.optBoolean("ok", false) != true) {
                    val fallback = when (response.code) {
                        401 -> "人脸服务认证失败"
                        404 -> "人脸服务接口不存在"
                        503 -> "人脸服务尚未就绪"
                        else -> "人脸服务返回 HTTP ${response.code}"
                    }
                    return FaceApiResult.Failure(
                        FaceServiceJson.errorMessage(json) ?: fallback,
                        response.code,
                    )
                }
                FaceApiResult.Success(json)
            }
        } catch (_: IOException) {
            FaceApiResult.Failure("无法连接 9095 人脸服务，请确认服务已手动启动且手机与小车在同一网络")
        } catch (error: IllegalArgumentException) {
            FaceApiResult.Failure("人脸服务请求无效：${error.message ?: "地址错误"}")
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
