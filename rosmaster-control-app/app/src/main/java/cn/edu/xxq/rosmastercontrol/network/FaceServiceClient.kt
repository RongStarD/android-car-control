package cn.edu.xxq.rosmastercontrol.network

import cn.edu.xxq.rosmastercontrol.BuildConfig
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

private const val API_KEY_HEADER = "X-API-Key"

data class FacePerson(
    val name: String,
    val samples: Int,
    val updatedAt: String? = null,
)

enum class RecognitionSessionState(val wireValue: String) {
    IDLE("idle"),
    RUNNING("running"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    FAILED("failed"),
}

enum class RecognitionResultState(val wireValue: String) {
    RECOGNIZED("recognized"),
    UNCERTAIN("uncertain"),
    UNKNOWN("unknown"),
    NO_FACE("no_face"),
}

data class FaceRecognitionResult(
    val state: RecognitionResultState,
    val personId: String? = null,
    val name: String? = null,
    val similarity: Double? = null,
    val appearances: Int = 0,
)

data class OrderVerificationResult(
    val matched: Boolean,
    val completed: Boolean,
    val status: String,
    val message: String,
)

data class RecognitionSession(
    val state: RecognitionSessionState = RecognitionSessionState.IDLE,
    val active: Boolean = false,
    val sessionId: String? = null,
    val durationSeconds: Double = 0.0,
    val remainingSeconds: Double = 0.0,
    val processedFrames: Int = 0,
    val orderId: String? = null,
    val orderResult: OrderVerificationResult? = null,
    val result: FaceRecognitionResult? = null,
)

enum class DeliveryOrderStatus(val wireValue: String, val label: String) {
    PENDING("pending", "待完成"),
    DELIVERING("delivering", "配送中"),
    COMPLETED("completed", "已完成"),
    CANCELLED("cancelled", "已取消"),
}

data class DeliveryOrderItem(
    val productId: String,
    val productName: String,
    val quantity: Int,
)

data class DeliveryOrder(
    val orderId: String,
    val personId: String,
    val personName: String,
    val roomNumber: String,
    val status: DeliveryOrderStatus,
    val items: List<DeliveryOrderItem>,
    val createdAt: String? = null,
    val deliveringAt: String? = null,
    val completedAt: String? = null,
    val cancelledAt: String? = null,
    val updatedAt: String? = null,
)

data class FaceServiceSnapshot(
    val peopleCount: Int,
    val sampleCount: Int,
    val databaseRevision: Long,
    val cameraReady: Boolean,
    val recognitionReady: Boolean,
    val people: List<FacePerson>,
    val recognitionSession: RecognitionSession,
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
    private fun nullableString(payload: JSONObject, key: String): String? =
        payload.optString(key).trim()
            .takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }

    fun parseSnapshot(status: JSONObject, peoplePayload: JSONObject): FaceServiceSnapshot {
        val database = status.optJSONObject("database") ?: JSONObject()
        val camera = status.optJSONObject("camera") ?: JSONObject()
        val recognition = status.optJSONObject("recognition") ?: JSONObject()
        val peopleArray = peoplePayload.optJSONArray("people")
        val people = buildList {
            if (peopleArray != null) {
                for (index in 0 until peopleArray.length()) {
                    val item = peopleArray.optJSONObject(index) ?: continue
                    val name = nullableString(item, "name") ?: continue
                    add(
                        FacePerson(
                            name = name,
                            samples = item.optInt("samples", 0).coerceAtLeast(0),
                            updatedAt = nullableString(item, "updated_at"),
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
            recognitionSession = parseRecognitionSession(status),
        )
    }

    fun parseRecognitionSession(payload: JSONObject): RecognitionSession {
        val session = payload.optJSONObject("recognition_session") ?: return RecognitionSession()
        val state = RecognitionSessionState.entries.firstOrNull {
            it.wireValue == session.optString("state").trim().lowercase()
        } ?: RecognitionSessionState.IDLE
        val resultPayload = session.optJSONObject("result")
        val result = resultPayload?.let { item ->
            val resultState = RecognitionResultState.entries.firstOrNull {
                it.wireValue == item.optString("state").trim().lowercase()
            } ?: return@let null
            FaceRecognitionResult(
                state = resultState,
                personId = nullableString(item, "person_id"),
                name = nullableString(item, "name"),
                similarity = item.optDouble("similarity", Double.NaN)
                    .takeIf(Double::isFinite)
                    ?.coerceIn(0.0, 1.0),
                appearances = item.optInt("appearances", 0).coerceAtLeast(0),
            )
        }
        val orderResultPayload = session.optJSONObject("order_result")
        val orderResult = orderResultPayload?.let { item ->
            OrderVerificationResult(
                matched = item.optBoolean("matched", false),
                completed = item.optBoolean("completed", false),
                status = item.optString("status").trim(),
                message = item.optString("message").trim(),
            )
        }
        return RecognitionSession(
            state = state,
            active = session.optBoolean("active", state == RecognitionSessionState.RUNNING),
            sessionId = nullableString(session, "session_id"),
            durationSeconds = session.optDouble("duration_seconds", 0.0).coerceAtLeast(0.0),
            remainingSeconds = session.optDouble("remaining_seconds", 0.0).coerceAtLeast(0.0),
            processedFrames = session.optInt("processed_frames", 0).coerceAtLeast(0),
            orderId = nullableString(session, "order_id"),
            orderResult = orderResult,
            result = result,
        )
    }

    fun parseOrders(payload: JSONObject): List<DeliveryOrder> {
        val orders = payload.optJSONArray("orders") ?: return emptyList()
        return buildList {
            for (index in 0 until orders.length()) {
                orders.optJSONObject(index)?.let { parseOrder(it) }?.let(::add)
            }
        }
    }

    fun parseOrder(payload: JSONObject): DeliveryOrder? {
        val orderId = nullableString(payload, "order_id") ?: return null
        val personId = nullableString(payload, "person_id") ?: return null
        val personName = nullableString(payload, "person_name") ?: return null
        val roomNumber = nullableString(payload, "room_number") ?: return null
        val status = DeliveryOrderStatus.entries.firstOrNull {
            it.wireValue == payload.optString("status").trim().lowercase()
        }
        if (
            status == null
        ) return null

        val itemsPayload = payload.optJSONArray("items")
        val items = buildList {
            if (itemsPayload != null) {
                for (index in 0 until itemsPayload.length()) {
                    val item = itemsPayload.optJSONObject(index) ?: continue
                    val productId = nullableString(item, "product_id")
                    val productName = nullableString(item, "product_name")
                    val quantity = item.optInt("quantity", 0)
                    if (productId != null && productName != null && quantity > 0) {
                        add(DeliveryOrderItem(productId, productName, quantity))
                    }
                }
            }
        }
        fun optionalTimestamp(key: String): String? = nullableString(payload, key)

        return DeliveryOrder(
            orderId = orderId,
            personId = personId,
            personName = personName,
            roomNumber = roomNumber,
            status = status,
            items = items,
            createdAt = optionalTimestamp("created_at"),
            deliveringAt = optionalTimestamp("delivering_at"),
            completedAt = optionalTimestamp("completed_at"),
            cancelledAt = optionalTimestamp("cancelled_at"),
            updatedAt = optionalTimestamp("updated_at"),
        )
    }

    fun errorMessage(payload: JSONObject?): String? {
        val error = payload?.opt("error") ?: return null
        return when (error) {
            is JSONObject -> {
                val code = nullableString(error, "code").orEmpty()
                when (code) {
                    "ORDER_NOT_FOUND" -> "订单不存在或已被删除"
                    "ORDER_NOT_DELIVERING" -> "请先将订单设为配送中，再识别收货人"
                    "ORDER_PERSON_UNAVAILABLE" -> "订单关联人员已不存在，无法继续配送"
                    "INVALID_ORDER_TRANSITION" -> "当前订单状态不允许执行此操作"
                    "DELIVERY_ALREADY_ACTIVE" -> "已有配送中订单，请先完成或取消当前配送"
                    "INVALID_ORDER_STATUS" -> "订单筛选状态无效"
                    "INVALID_ORDER_ID" -> "订单编号无效"
                    "NOT_FOUND" -> "9095 服务版本过旧或接口不存在，请更新 Jetson 后端"
                    else -> nullableString(error, "message") ?: code.ifEmpty { null }
                }
            }
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
        // 启动/取消配送和识别会话都是状态变更请求，响应丢失时不能自动重放。
        // GET 状态由 ViewModel 的定时刷新恢复。
        .retryOnConnectionFailure(false)
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

    fun startRecognitionSession(
        baseUrl: String,
        durationSeconds: Int = 5,
        orderId: String? = null,
    ): FaceApiResult<RecognitionSession> {
        if (durationSeconds !in 1..10) {
            return FaceApiResult.Failure("识别时长必须是 1～10 秒")
        }
        val url = recognitionSessionUrl(baseUrl)
            ?: return FaceApiResult.Failure("人脸服务地址无效")
        val body = JSONObject()
            .put("duration_seconds", durationSeconds)
            .apply {
                orderId?.trim()?.takeIf(String::isNotEmpty)?.let { put("order_id", it) }
            }
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        return executeSession(request(url).post(body).build())
    }

    fun getRecognitionSession(baseUrl: String): FaceApiResult<RecognitionSession> {
        val url = recognitionSessionUrl(baseUrl)
            ?: return FaceApiResult.Failure("人脸服务地址无效")
        return executeSession(request(url).get().build())
    }

    fun cancelRecognitionSession(baseUrl: String): FaceApiResult<RecognitionSession> {
        val url = recognitionSessionUrl(baseUrl)
            ?: return FaceApiResult.Failure("人脸服务地址无效")
        return executeSession(request(url).delete().build())
    }

    fun loadOrders(baseUrl: String, status: String = "all"): FaceApiResult<List<DeliveryOrder>> {
        val base = FaceServiceEndpoint.apiUrl(baseUrl, "api", "v1", "admin", "orders")
            ?: return FaceApiResult.Failure("人脸服务地址无效")
        val url = base.newBuilder().addQueryParameter("status", status).build()
        return when (val result = executeJson(request(url).get().build())) {
            is FaceApiResult.Success -> FaceApiResult.Success(FaceServiceJson.parseOrders(result.value))
            is FaceApiResult.Failure -> result
        }
    }

    fun startDelivery(baseUrl: String, orderId: String): FaceApiResult<DeliveryOrder> =
        executeOrderAction(baseUrl, orderId, "start")

    fun cancelOrder(baseUrl: String, orderId: String): FaceApiResult<DeliveryOrder> =
        executeOrderAction(baseUrl, orderId, "cancel")

    private fun executeOrderAction(
        baseUrl: String,
        orderId: String,
        action: String,
    ): FaceApiResult<DeliveryOrder> {
        val cleanId = orderId.trim()
        if (cleanId.isEmpty()) return FaceApiResult.Failure("订单编号不能为空")
        val url = FaceServiceEndpoint.apiUrl(
            baseUrl,
            "api",
            "v1",
            "admin",
            "orders",
            cleanId,
            action,
        ) ?: return FaceApiResult.Failure("人脸服务地址无效")
        val body = "{}".toRequestBody(JSON_MEDIA_TYPE)
        return when (val result = executeJson(request(url).post(body).build())) {
            is FaceApiResult.Success -> {
                val order = result.value.optJSONObject("order")?.let(FaceServiceJson::parseOrder)
                if (order == null) {
                    FaceApiResult.Failure("订单服务响应缺少有效 order")
                } else {
                    FaceApiResult.Success(order)
                }
            }
            is FaceApiResult.Failure -> result
        }
    }

    private fun recognitionSessionUrl(baseUrl: String): HttpUrl? =
        FaceServiceEndpoint.apiUrl(baseUrl, "api", "v1", "recognition", "session")

    private fun executeSession(request: Request): FaceApiResult<RecognitionSession> {
        return when (val result = executeJson(request)) {
            is FaceApiResult.Success -> {
                if (result.value.optJSONObject("recognition_session") == null) {
                    FaceApiResult.Failure("人脸服务响应缺少 recognition_session")
                } else {
                    FaceApiResult.Success(FaceServiceJson.parseRecognitionSession(result.value))
                }
            }
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

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
