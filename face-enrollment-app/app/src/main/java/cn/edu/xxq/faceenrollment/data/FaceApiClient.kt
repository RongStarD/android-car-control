package cn.edu.xxq.faceenrollment.data

import cn.edu.xxq.faceenrollment.BuildConfig
import java.io.Closeable
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

const val DEFAULT_FACE_SERVICE_URL = "http://10.39.132.165:9095"
const val MAX_FACE_IMAGES = 10
const val MAX_FACE_IMAGE_BYTES = 5 * 1024 * 1024
const val MAX_FACE_TOTAL_BYTES = 20 * 1024 * 1024
private const val API_KEY_HEADER = "X-API-Key"

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val message: String, val statusCode: Int? = null) : ApiResult<Nothing>
}

data class CameraStatus(
    val ready: Boolean = false,
    val source: String? = null,
    val error: String? = null,
)

data class DatabaseStatus(val people: Int = 0, val samples: Int = 0)

data class RecognitionStatus(
    val enabled: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null,
    val lastSuccessAt: String? = null,
)

data class RecognizedFace(
    val state: String,
    val name: String,
    val similarity: Double,
)

data class LatestRecognition(
    val timestamp: String? = null,
    val faces: List<RecognizedFace> = emptyList(),
)

data class ServiceStatus(
    val camera: CameraStatus = CameraStatus(),
    val recognition: RecognitionStatus = RecognitionStatus(),
    val database: DatabaseStatus = DatabaseStatus(),
    val latest: LatestRecognition? = null,
    val recognitionSession: RecognitionSession? = null,
)

data class RecognitionSessionResult(
    val state: String,
    val name: String? = null,
    val similarity: Double? = null,
    val appearances: Int = 0,
)

data class RecognitionSession(
    val state: String = "idle",
    val active: Boolean = false,
    val sessionId: String? = null,
    val durationSeconds: Double = 5.0,
    val remainingSeconds: Double = 0.0,
    val processedFrames: Int = 0,
    val result: RecognitionSessionResult? = null,
)

data class EnrollmentReceipt(
    val name: String,
    val personId: String? = null,
    val acceptedSamples: Int,
    val totalSamples: Int? = null,
    val databaseRevision: Long? = null,
    val message: String? = null,
)

data class CatalogProduct(
    val productId: String,
    val name: String,
    val enabled: Boolean,
)

data class OrderItemRequest(
    val productId: String,
    val quantity: Int,
)

data class OrderItemReceipt(
    val productId: String,
    val productName: String,
    val quantity: Int,
)

data class OrderReceipt(
    val orderId: String,
    val personId: String? = null,
    val personName: String,
    val roomNumber: String,
    val status: String,
    val items: List<OrderItemReceipt>,
    val createdAt: String? = null,
)

object FaceEndpoint {
    fun normalize(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val withSlash = if (trimmed.endsWith('/')) trimmed else "$trimmed/"
        val url = withSlash.toHttpUrlOrNull() ?: return null
        if (url.scheme != "http" && url.scheme != "https") return null
        if (url.host.isBlank() || url.query != null || url.fragment != null) return null
        return url.toString().removeSuffix("/")
    }

    internal fun apiUrl(baseUrl: String, vararg pathSegments: String): HttpUrl? {
        val normalized = normalize(baseUrl) ?: return null
        val base = "$normalized/".toHttpUrlOrNull() ?: return null
        return base.newBuilder().apply {
            pathSegments.forEach(::addPathSegment)
        }.build()
    }
}

internal object FacePayloadLimits {
    fun validationError(imageSizes: List<Int>): String? {
        if (imageSizes.isEmpty()) return "请至少选择一张人脸照片"
        if (imageSizes.size > MAX_FACE_IMAGES) return "一次最多上传 $MAX_FACE_IMAGES 张照片"
        if (imageSizes.any { it <= 0 || it > MAX_FACE_IMAGE_BYTES }) {
            return "每张 JPEG 必须小于或等于 5 MiB"
        }
        if (imageSizes.sumOf(Int::toLong) > MAX_FACE_TOTAL_BYTES.toLong()) {
            return "所有 JPEG 合计必须小于或等于 20 MiB"
        }
        return null
    }
}

class FaceApiClient(
    private val apiKey: String = BuildConfig.FACE_API_KEY,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        // Enrollment is not idempotent: a transport retry could create duplicate samples.
        .retryOnConnectionFailure(false)
        .build(),
) : Closeable {
    fun getStatus(baseUrl: String): ApiResult<ServiceStatus> {
        val url = FaceEndpoint.apiUrl(baseUrl, "api", "v1", "status")
            ?: return ApiResult.Failure("服务地址无效，请填写 http:// 或 https:// 地址")
        val networkRequest = request(url, apiKey)
            .tag(StatusRequestTag::class.java, StatusRequestTag)
            .get()
            .build()
        return executeJson(networkRequest, FaceApiJson::parseStatus)
    }

    fun enroll(
        baseUrl: String,
        name: String,
        replace: Boolean,
        images: List<ByteArray>,
    ): ApiResult<EnrollmentReceipt> {
        FacePayloadLimits.validationError(images.map(ByteArray::size))?.let {
            return ApiResult.Failure(it)
        }
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return ApiResult.Failure("姓名不能为空")

        val url = FaceEndpoint.apiUrl(baseUrl, "api", "v1", "enroll")
            ?: return ApiResult.Failure("服务地址无效，请填写 http:// 或 https:// 地址")
        val imageArray = JSONArray()
        images.forEach { imageArray.put(Base64.getEncoder().encodeToString(it)) }
        val payload = JSONObject()
            .put("name", cleanName)
            .put("replace", replace)
            .put("images", imageArray)
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val networkRequest = request(url, apiKey).post(body).build()
        return executeJson(networkRequest) { json ->
            FaceApiJson.parseEnrollment(json, cleanName, images.size)
        }
    }

    fun getCatalog(baseUrl: String): ApiResult<List<CatalogProduct>> {
        val url = FaceEndpoint.apiUrl(baseUrl, "api", "v1", "catalog") ?: return invalidAddress()
        val networkRequest = request(url, apiKey)
            .tag(CatalogRequestTag::class.java, CatalogRequestTag)
            .get()
            .build()
        return executeJson(networkRequest, FaceApiJson::parseCatalog)
    }

    fun createOrder(
        baseUrl: String,
        personId: String,
        personName: String,
        roomNumber: String,
        items: List<OrderItemRequest>,
    ): ApiResult<OrderReceipt> {
        val cleanPersonId = personId.trim()
        val cleanName = personName.trim()
        val cleanRoom = roomNumber.trim()
        if (cleanPersonId.isEmpty()) return ApiResult.Failure("请先完成人脸录入")
        if (cleanName.isEmpty()) return ApiResult.Failure("姓名不能为空")
        if (cleanRoom.isEmpty()) return ApiResult.Failure("房间号不能为空")
        if (items.isEmpty()) return ApiResult.Failure("请至少选择一件商品")

        val url = FaceEndpoint.apiUrl(baseUrl, "api", "v1", "orders") ?: return invalidAddress()
        val payload = FaceApiJson.orderPayload(cleanPersonId, cleanName, cleanRoom, items)
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val networkRequest = request(url, apiKey)
            .tag(OrderRequestTag::class.java, OrderRequestTag)
            .post(body)
            .build()
        return executeJson(networkRequest, FaceApiJson::parseOrder)
    }

    fun startRecognitionSession(
        baseUrl: String,
        durationSeconds: Int = 5,
    ): ApiResult<RecognitionSession> {
        val url = recognitionSessionUrl(baseUrl) ?: return invalidAddress()
        val body = JSONObject()
            .put("duration_seconds", durationSeconds.coerceIn(1, 10))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val networkRequest = request(url, apiKey)
            .tag(RecognitionRequestTag::class.java, RecognitionRequestTag)
            .post(body)
            .build()
        return executeJson(networkRequest, FaceApiJson::parseRecognitionSession)
    }

    fun getRecognitionSession(baseUrl: String): ApiResult<RecognitionSession> {
        val url = recognitionSessionUrl(baseUrl) ?: return invalidAddress()
        val networkRequest = request(url, apiKey)
            .tag(RecognitionRequestTag::class.java, RecognitionRequestTag)
            .get()
            .build()
        return executeJson(networkRequest, FaceApiJson::parseRecognitionSession)
    }

    fun cancelRecognitionSession(baseUrl: String): ApiResult<RecognitionSession> {
        val url = recognitionSessionUrl(baseUrl) ?: return invalidAddress()
        val networkRequest = request(url, apiKey)
            .tag(RecognitionRequestTag::class.java, RecognitionRequestTag)
            .delete()
            .build()
        return executeJson(networkRequest, FaceApiJson::parseRecognitionSession)
    }

    private fun recognitionSessionUrl(baseUrl: String): HttpUrl? =
        FaceEndpoint.apiUrl(baseUrl, "api", "v1", "recognition", "session")

    private fun invalidAddress(): ApiResult.Failure =
        ApiResult.Failure("服务地址无效，请填写 http:// 或 https:// 地址")

    private fun request(url: HttpUrl, apiKey: String): Request.Builder = Request.Builder()
        .url(url)
        .header(API_KEY_HEADER, apiKey.trim())
        .header("Accept", "application/json")

    private fun <T> executeJson(
        request: Request,
        parse: (JSONObject) -> T,
    ): ApiResult<T> {
        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(raw)
                } catch (_: JSONException) {
                    null
                }
                val serverError = json?.let(FaceApiJson::parseError)
                if (!response.isSuccessful || json?.optBoolean("ok", false) != true) {
                    return ApiResult.Failure(
                        message = serverError ?: if (raw.isBlank()) {
                            "服务返回 HTTP ${response.code}"
                        } else {
                            "服务返回了无法解析的响应"
                        },
                        statusCode = response.code,
                    )
                }
                try {
                    ApiResult.Success(parse(json))
                } catch (error: Exception) {
                    ApiResult.Failure("服务响应字段不完整：${error.message ?: "解析失败"}", response.code)
                }
            }
        } catch (error: IOException) {
            ApiResult.Failure("无法连接人脸服务：${error.message ?: "网络不可用"}")
        } catch (error: IllegalArgumentException) {
            ApiResult.Failure("请求参数无效：${error.message ?: "地址或 Header 错误"}")
        }
    }

    fun cancelStatusRequests() {
        val calls = client.dispatcher.queuedCalls() + client.dispatcher.runningCalls()
        calls.filter {
            it.request().tag(StatusRequestTag::class.java) === StatusRequestTag
        }.forEach { it.cancel() }
    }

    fun cancelRecognitionRequests() {
        val calls = client.dispatcher.queuedCalls() + client.dispatcher.runningCalls()
        calls.filter {
            it.request().tag(RecognitionRequestTag::class.java) === RecognitionRequestTag
        }.forEach { it.cancel() }
    }

    fun cancelCatalogRequests() {
        val calls = client.dispatcher.queuedCalls() + client.dispatcher.runningCalls()
        calls.filter {
            it.request().tag(CatalogRequestTag::class.java) === CatalogRequestTag
        }.forEach { it.cancel() }
    }

    fun cancelOrderRequests() {
        val calls = client.dispatcher.queuedCalls() + client.dispatcher.runningCalls()
        calls.filter {
            it.request().tag(OrderRequestTag::class.java) === OrderRequestTag
        }.forEach { it.cancel() }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private object StatusRequestTag
    private object RecognitionRequestTag
    private object CatalogRequestTag
    private object OrderRequestTag
}

internal object FaceApiJson {
    fun orderPayload(
        personId: String,
        personName: String,
        roomNumber: String,
        items: List<OrderItemRequest>,
    ): JSONObject {
        val itemArray = JSONArray()
        items.forEach { item ->
            itemArray.put(
                JSONObject()
                    .put("product_id", item.productId)
                    .put("quantity", item.quantity),
            )
        }
        return JSONObject()
            .put("person_id", personId.trim())
            .put("person_name", personName.trim())
            .put("room_number", roomNumber.trim())
            .put("items", itemArray)
    }

    fun parseError(json: JSONObject): String? {
        return when (val error = json.opt("error")) {
            is JSONObject -> {
                val message = error.optNullableString("message").orEmpty()
                val code = error.optNullableString("code").orEmpty()
                when {
                    message.isNotEmpty() && code.isNotEmpty() -> "$message（$code）"
                    message.isNotEmpty() -> message
                    code.isNotEmpty() -> code
                    else -> null
                }
            }
            is String -> error.trim().ifEmpty { null }
            else -> null
        }
    }

    fun parseEnrollment(
        json: JSONObject,
        fallbackName: String,
        requestedSamples: Int,
    ): EnrollmentReceipt {
        val person = json.optJSONObject("person")
        val nestedName = person?.optNullableString("name").orEmpty()
        val topLevelName = json.optNullableString("name").orEmpty()
        val totalSamples = when {
            person?.has("samples") == true -> person.optInt("samples", requestedSamples)
            json.has("samples") -> json.optInt("samples", requestedSamples)
            else -> null
        }?.coerceAtLeast(0)
        val acceptedSamples = when {
            json.has("added_samples") -> json.optInt("added_samples", requestedSamples)
            json.has("accepted") -> json.optInt("accepted", requestedSamples)
            person == null && json.has("samples") -> json.optInt("samples", requestedSamples)
            else -> requestedSamples
        }.coerceAtLeast(0)
        return EnrollmentReceipt(
            name = nestedName.ifBlank { topLevelName }.ifBlank { fallbackName },
            personId = person?.optNullableString("person_id")
                ?: person?.optNullableString("id")
                ?: json.optNullableString("person_id"),
            acceptedSamples = acceptedSamples,
            totalSamples = totalSamples,
            databaseRevision = json.optLongOrNull("database_revision"),
            message = json.optNullableString("message"),
        )
    }

    fun parseCatalog(json: JSONObject): List<CatalogProduct> {
        val products = json.optJSONArray("products")
            ?: throw JSONException("缺少 products")
        val seenIds = mutableSetOf<String>()
        return buildList {
            for (index in 0 until products.length()) {
                val value = products.optJSONObject(index)
                    ?: throw JSONException("products[$index] 不是对象")
                val productId = value.optNullableString("product_id")
                    ?: throw JSONException("products[$index] 缺少 product_id")
                val name = value.optNullableString("name")
                    ?: throw JSONException("products[$index] 缺少 name")
                if (!seenIds.add(productId)) continue
                add(
                    CatalogProduct(
                        productId = productId,
                        name = name,
                        enabled = value.optBoolean("enabled", false),
                    ),
                )
            }
        }
    }

    fun parseOrder(json: JSONObject): OrderReceipt {
        val order = json.optJSONObject("order") ?: throw JSONException("缺少 order")
        val orderId = order.optNullableString("order_id") ?: throw JSONException("缺少 order_id")
        val personName = order.optNullableString("person_name") ?: throw JSONException("缺少 person_name")
        val roomNumber = order.optNullableString("room_number") ?: throw JSONException("缺少 room_number")
        val status = order.optNullableString("status") ?: throw JSONException("缺少 status")
        val itemsJson = order.optJSONArray("items") ?: throw JSONException("缺少 items")
        val items = buildList {
            for (index in 0 until itemsJson.length()) {
                val value = itemsJson.optJSONObject(index)
                    ?: throw JSONException("items[$index] 不是对象")
                val productId = value.optNullableString("product_id")
                    ?: throw JSONException("items[$index] 缺少 product_id")
                val productName = value.optNullableString("product_name")
                    ?: throw JSONException("items[$index] 缺少 product_name")
                val quantity = value.optInt("quantity", 0)
                if (quantity <= 0) throw JSONException("items[$index] quantity 无效")
                add(OrderItemReceipt(productId, productName, quantity))
            }
        }
        if (items.isEmpty()) throw JSONException("订单商品不能为空")
        return OrderReceipt(
            orderId = orderId,
            personId = order.optNullableString("person_id"),
            personName = personName,
            roomNumber = roomNumber,
            status = status.lowercase(),
            items = items,
            createdAt = order.optNullableString("created_at"),
        )
    }

    fun parseStatus(json: JSONObject): ServiceStatus {
        val cameraJson = json.optJSONObject("camera")
        val recognitionJson = json.optJSONObject("recognition")
        val databaseJson = json.optJSONObject("database")
        val latestJson = json.optJSONObject("latest")
        val latest = latestJson?.let { value ->
            val facesJson = value.optJSONArray("faces") ?: JSONArray()
            val faces = buildList {
                for (index in 0 until facesJson.length()) {
                    val face = facesJson.optJSONObject(index) ?: continue
                    val state = face.optNullableString("state")?.lowercase() ?: continue
                    if (state != "recognized") continue
                    val name = face.optNullableString("name") ?: continue
                    val similarity = face.optFiniteDouble("similarity") ?: continue
                    add(RecognizedFace(state = state, name = name, similarity = similarity))
                }
            }
            LatestRecognition(
                timestamp = value.optNullableString("timestamp"),
                faces = faces,
            )
        }
        return ServiceStatus(
            camera = CameraStatus(
                ready = cameraJson?.optBoolean("ready", false) ?: false,
                source = cameraJson?.optNullableString("source"),
                error = cameraJson?.optNullableString("error"),
            ),
            recognition = RecognitionStatus(
                enabled = recognitionJson?.optBoolean("enabled", false) ?: false,
                ready = recognitionJson?.optBoolean("ready", false) ?: false,
                error = recognitionJson?.optNullableString("error"),
                lastSuccessAt = recognitionJson?.optNullableString("last_success_at"),
            ),
            database = DatabaseStatus(
                people = databaseJson?.optInt("people", 0)?.coerceAtLeast(0) ?: 0,
                samples = databaseJson?.optInt("samples", 0)?.coerceAtLeast(0) ?: 0,
            ),
            latest = latest,
            recognitionSession = json.optJSONObject("recognition_session")?.let(::parseRecognitionSessionObject),
        )
    }

    fun parseRecognitionSession(json: JSONObject): RecognitionSession {
        val value = json.optJSONObject("recognition_session") ?: json
        return parseRecognitionSessionObject(value)
    }

    private fun parseRecognitionSessionObject(value: JSONObject): RecognitionSession {
        val active = value.optBoolean("active", false)
        val result = value.optJSONObject("result")?.let { resultJson ->
            val state = resultJson.optNullableString("state")?.lowercase() ?: "unknown"
            RecognitionSessionResult(
                state = state,
                name = resultJson.optNullableString("name"),
                similarity = resultJson.optFiniteDouble("similarity"),
                appearances = resultJson.optInt("appearances", 0).coerceAtLeast(0),
            )
        }
        return RecognitionSession(
            state = value.optNullableString("state")?.lowercase() ?: if (active) "active" else "idle",
            active = active,
            sessionId = value.optNullableString("session_id"),
            durationSeconds = (value.optFiniteDouble("duration_seconds") ?: 5.0).coerceAtLeast(0.0),
            remainingSeconds = (value.optFiniteDouble("remaining_seconds") ?: 0.0).coerceAtLeast(0.0),
            processedFrames = value.optInt("processed_frames", 0).coerceAtLeast(0),
            result = result,
        )
    }
}

private fun JSONObject.optNullableString(key: String): String? {
    val value = opt(key)
    if (value == null || value === JSONObject.NULL || value !is String) return null
    return value.trim().takeIf(String::isNotEmpty)
}

private fun JSONObject.optFiniteDouble(key: String): Double? {
    val value = opt(key)
    val number = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
    return number?.takeIf(Double::isFinite)
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
