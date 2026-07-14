package cn.edu.xxq.faceenrollment

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.xxq.faceenrollment.data.ApiResult
import cn.edu.xxq.faceenrollment.data.CatalogProduct
import cn.edu.xxq.faceenrollment.data.DEFAULT_FACE_SERVICE_URL
import cn.edu.xxq.faceenrollment.data.FaceApiClient
import cn.edu.xxq.faceenrollment.data.FaceEndpoint
import cn.edu.xxq.faceenrollment.data.MAX_FACE_IMAGES
import cn.edu.xxq.faceenrollment.data.MAX_FACE_TOTAL_BYTES
import cn.edu.xxq.faceenrollment.data.OrderItemRequest
import cn.edu.xxq.faceenrollment.data.OrderReceipt
import cn.edu.xxq.faceenrollment.data.RecognitionSession
import cn.edu.xxq.faceenrollment.data.ServiceStatus
import cn.edu.xxq.faceenrollment.media.FaceImageProcessor
import cn.edu.xxq.faceenrollment.media.FaceImageSample
import cn.edu.xxq.faceenrollment.media.ImageProcessingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

private const val PREFERENCES_NAME = "face_enrollment"
private const val AUTO_REFRESH_INTERVAL_MS = 5_000L
private const val RECOGNITION_POLL_INTERVAL_MS = 500L
private const val RECOGNITION_DURATION_SECONDS = 5
private const val MAX_ORDER_QUANTITY = 99

data class FaceEnrollmentUiState(
    val serviceUrl: String = DEFAULT_FACE_SERVICE_URL,
    val personName: String = "",
    val replaceExisting: Boolean = false,
    val images: List<FaceImageSample> = emptyList(),
    val serviceStatus: ServiceStatus? = null,
    val autoRefresh: Boolean = true,
    val processingImages: Boolean = false,
    val uploading: Boolean = false,
    val statusLoading: Boolean = false,
    val statusError: String? = null,
    val recognitionSession: RecognitionSession? = null,
    val recognitionRequestLoading: Boolean = false,
    val recognitionError: String? = null,
    val notice: String? = null,
    val actionError: String? = null,
    val statusUpdatedAt: Long? = null,
    val orderPersonName: String = "",
    val roomNumber: String = "",
    val catalog: List<CatalogProduct> = emptyList(),
    val productQuantities: Map<String, Int> = emptyMap(),
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val catalogLoadedAt: Long? = null,
    val orderSubmitting: Boolean = false,
    val orderError: String? = null,
    val lastOrder: OrderReceipt? = null,
    val lastEnrolledPersonId: String? = null,
    val lastEnrolledName: String? = null,
)

class FaceEnrollmentViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, 0)
    private val apiClient = FaceApiClient()
    private val imageProcessor = FaceImageProcessor(application.contentResolver)
    private val statusMutex = Mutex()
    private val recognitionMutex = Mutex()
    private val catalogMutex = Mutex()
    private var pollingJob: Job? = null
    private var recognitionPollingJob: Job? = null
    private var orderJob: Job? = null
    private var pollingEnabledByLifecycle = false
    private var configurationGeneration = 0L

    private val _state = MutableStateFlow(
        preferences.getString("last_enrolled_name", "").orEmpty().let { recentName ->
            FaceEnrollmentUiState(
                serviceUrl = preferences.getString("service_url", DEFAULT_FACE_SERVICE_URL)
                    ?: DEFAULT_FACE_SERVICE_URL,
                personName = recentName,
                orderPersonName = recentName,
                lastEnrolledPersonId = preferences.getString("last_enrolled_person_id", null),
                lastEnrolledName = recentName.takeIf(String::isNotBlank),
            )
        },
    )
    val state: StateFlow<FaceEnrollmentUiState> = _state.asStateFlow()

    init {
        // Remove a key left by an early development build; credentials now come from BuildConfig.
        preferences.edit().remove("api_key").apply()
    }

    fun setServiceUrl(value: String) {
        if (value.length > 300) return
        configurationGeneration += 1
        _state.update {
            it.copy(
                serviceUrl = value,
                serviceStatus = null,
                statusError = null,
                actionError = null,
                statusLoading = false,
                recognitionSession = null,
                recognitionRequestLoading = false,
                recognitionError = null,
                catalog = emptyList(),
                productQuantities = emptyMap(),
                catalogLoading = false,
                catalogError = null,
                catalogLoadedAt = null,
                orderSubmitting = false,
                orderError = null,
                lastOrder = null,
            )
        }
        stopRecognitionPolling()
        apiClient.cancelCatalogRequests()
        apiClient.cancelOrderRequests()
        orderJob?.cancel()
        orderJob = null
        preferences.edit().putString("service_url", value).apply()
        restartPollingAfterConfigurationChange()
    }

    fun setPersonName(value: String) {
        if (value.length <= 80 && '\n' !in value && '\r' !in value) {
            _state.update {
                it.copy(
                    personName = value,
                    orderPersonName = if (
                        it.orderPersonName.isBlank() || it.orderPersonName == it.personName
                    ) value else it.orderPersonName,
                    actionError = null,
                )
            }
        }
    }

    fun setOrderPersonName(value: String) {
        if (value.length <= 80 && '\n' !in value && '\r' !in value) {
            _state.update { it.copy(orderPersonName = value, orderError = null, lastOrder = null) }
        }
    }

    fun setRoomNumber(value: String) {
        if (value.length <= 64 && '\n' !in value && '\r' !in value) {
            _state.update { it.copy(roomNumber = value, orderError = null, lastOrder = null) }
        }
    }

    fun toggleProduct(productId: String) {
        _state.update { state ->
            val product = state.catalog.firstOrNull { it.productId == productId && it.enabled }
                ?: return@update state
            val updated = state.productQuantities.toMutableMap()
            if ((updated[product.productId] ?: 0) > 0) {
                updated.remove(product.productId)
            } else {
                updated[product.productId] = 1
            }
            state.copy(productQuantities = updated, orderError = null, lastOrder = null)
        }
    }

    fun changeProductQuantity(productId: String, delta: Int) {
        if (delta == 0) return
        _state.update { state ->
            val product = state.catalog.firstOrNull { it.productId == productId && it.enabled }
                ?: return@update state
            val current = state.productQuantities[product.productId] ?: 0
            val next = (current + delta).coerceIn(0, MAX_ORDER_QUANTITY)
            val updated = state.productQuantities.toMutableMap()
            if (next == 0) updated.remove(product.productId) else updated[product.productId] = next
            state.copy(productQuantities = updated, orderError = null, lastOrder = null)
        }
    }

    fun refreshCatalog() {
        if (!pollingEnabledByLifecycle || _state.value.catalogLoading) return
        val normalizedUrl = FaceEndpoint.normalize(_state.value.serviceUrl)
        if (normalizedUrl == null) {
            _state.update { it.copy(catalogError = "服务地址无效，请填写 http:// 或 https:// 地址") }
            return
        }
        viewModelScope.launch { loadCatalog(normalizedUrl) }
    }

    fun submitOrder() {
        val snapshot = _state.value
        if (snapshot.orderSubmitting) return
        val normalizedUrl = FaceEndpoint.normalize(snapshot.serviceUrl)
        val validationError = OrderFormLogic.validationError(
            serviceUrl = normalizedUrl,
            personName = snapshot.orderPersonName,
            roomNumber = snapshot.roomNumber,
            catalog = snapshot.catalog,
            productQuantities = snapshot.productQuantities,
            lastEnrolledPersonId = snapshot.lastEnrolledPersonId,
            lastEnrolledName = snapshot.lastEnrolledName,
        )
        if (validationError != null) {
            _state.update { it.copy(orderError = validationError, lastOrder = null) }
            return
        }
        val url = checkNotNull(normalizedUrl)
        val personId = checkNotNull(snapshot.lastEnrolledPersonId?.trim()?.takeIf(String::isNotEmpty))
        val requestItems = OrderFormLogic.requestItems(snapshot.catalog, snapshot.productQuantities)
        val generation = configurationGeneration
        _state.update {
            it.copy(
                serviceUrl = url,
                orderSubmitting = true,
                orderError = null,
                lastOrder = null,
            )
        }
        preferences.edit().putString("service_url", url).apply()
        orderJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                apiClient.createOrder(
                    baseUrl = url,
                    personId = personId,
                    personName = snapshot.orderPersonName,
                    roomNumber = snapshot.roomNumber,
                    items = requestItems,
                )
            }
            if (generation != configurationGeneration) return@launch
            when (result) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        orderSubmitting = false,
                        roomNumber = "",
                        productQuantities = emptyMap(),
                        orderError = null,
                        lastOrder = result.value,
                    )
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(orderSubmitting = false, orderError = result.message, lastOrder = null)
                }
            }
            orderJob = null
        }
    }

    fun setReplaceExisting(value: Boolean) {
        _state.update { it.copy(replaceExisting = value, actionError = null) }
    }

    fun setAutoRefresh(enabled: Boolean) {
        _state.update { it.copy(autoRefresh = enabled) }
        if (enabled && canPoll()) startPolling(immediate = true) else stopPolling()
    }

    fun setPollingEnabled(enabled: Boolean) {
        pollingEnabledByLifecycle = enabled
        if (enabled) {
            if (canPoll()) startPolling(immediate = true) else stopPolling()
            if (_state.value.recognitionSession?.active == true) ensureRecognitionPolling()
            if (_state.value.catalog.isEmpty() && !_state.value.catalogLoading) refreshCatalog()
        } else {
            stopPolling()
            stopRecognitionPolling()
            apiClient.cancelCatalogRequests()
            _state.update { it.copy(catalogLoading = false) }
        }
    }

    fun startRecognition() {
        val snapshot = _state.value
        if (snapshot.recognitionRequestLoading || snapshot.recognitionSession?.active == true) return
        val normalizedUrl = FaceEndpoint.normalize(snapshot.serviceUrl)
        if (normalizedUrl == null) {
            _state.update { it.copy(recognitionError = "服务地址无效，请填写 http:// 或 https:// 地址") }
            return
        }
        _state.update {
            it.copy(
                serviceUrl = normalizedUrl,
                recognitionRequestLoading = true,
                recognitionError = null,
                recognitionSession = null,
            )
        }
        preferences.edit().putString("service_url", normalizedUrl).apply()
        val generation = configurationGeneration
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                apiClient.startRecognitionSession(normalizedUrl, RECOGNITION_DURATION_SECONDS)
            }
            if (generation != configurationGeneration || !pollingEnabledByLifecycle) return@launch
            when (result) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            recognitionRequestLoading = false,
                            recognitionSession = result.value,
                            recognitionError = null,
                        )
                    }
                    if (result.value.active && pollingEnabledByLifecycle) ensureRecognitionPolling()
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(recognitionRequestLoading = false, recognitionError = result.message)
                }
            }
        }
    }

    fun cancelRecognition() {
        val snapshot = _state.value
        if (snapshot.recognitionRequestLoading || snapshot.recognitionSession?.active != true) return
        val normalizedUrl = FaceEndpoint.normalize(snapshot.serviceUrl) ?: return
        _state.update { it.copy(recognitionRequestLoading = true, recognitionError = null) }
        val generation = configurationGeneration
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { apiClient.cancelRecognitionSession(normalizedUrl) }
            if (generation != configurationGeneration || !pollingEnabledByLifecycle) return@launch
            when (result) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            recognitionRequestLoading = false,
                            recognitionSession = result.value,
                            recognitionError = null,
                        )
                    }
                    stopRecognitionPolling()
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(recognitionRequestLoading = false, recognitionError = result.message)
                }
            }
        }
    }

    fun addImages(
        uris: List<Uri>,
        sourceLabel: String,
        onComplete: (() -> Unit)? = null,
    ) {
        if (uris.isEmpty()) {
            onComplete?.invoke()
            return
        }
        val current = _state.value
        if (current.processingImages) {
            showError("正在处理上一批图片，请稍候")
            onComplete?.invoke()
            return
        }
        val remaining = MAX_FACE_IMAGES - current.images.size
        if (remaining <= 0) {
            showError("一次最多选择 $MAX_FACE_IMAGES 张照片")
            onComplete?.invoke()
            return
        }
        if (current.images.sumOf { it.sizeBytes.toLong() } >= MAX_FACE_TOTAL_BYTES.toLong()) {
            showError("所有待上传 JPEG 合计不能超过 20 MiB")
            onComplete?.invoke()
            return
        }
        val selected = uris.take(remaining)
        val skipped = uris.size - selected.size
        _state.update { it.copy(processingImages = true, actionError = null, notice = null) }
        viewModelScope.launch {
            try {
                val processed = mutableListOf<FaceImageSample>()
                val failures = mutableListOf<String>()
                withContext(Dispatchers.IO) {
                    selected.forEachIndexed { index, uri ->
                        try {
                            processed += imageProcessor.process(uri, sourceLabel)
                        } catch (error: ImageProcessingException) {
                            failures += "第 ${index + 1} 张：${error.message ?: "处理失败"}"
                        }
                    }
                }
                _state.update { state ->
                    val available = (MAX_FACE_IMAGES - state.images.size).coerceAtLeast(0)
                    var totalBytes = state.images.sumOf { it.sizeBytes.toLong() }
                    val accepted = mutableListOf<FaceImageSample>()
                    var rejectedByTotalSize = 0
                    processed.take(available).forEach { sample ->
                        if (totalBytes + sample.sizeBytes <= MAX_FACE_TOTAL_BYTES.toLong()) {
                            accepted += sample
                            totalBytes += sample.sizeBytes
                        } else {
                            rejectedByTotalSize += 1
                        }
                    }
                    val messages = buildList {
                        if (accepted.isNotEmpty()) add("已加入 ${accepted.size} 张照片")
                        if (skipped > 0 || processed.size - rejectedByTotalSize > accepted.size) {
                            add("超出 10 张上限的照片已忽略")
                        }
                    }
                    val errors = buildList {
                        addAll(failures)
                        if (rejectedByTotalSize > 0) {
                            add("所有待上传 JPEG 合计不能超过 20 MiB，已忽略 $rejectedByTotalSize 张")
                        }
                    }
                    state.copy(
                        images = state.images + accepted,
                        processingImages = false,
                        notice = messages.joinToString("；").ifBlank { null },
                        actionError = errors.joinToString("；").ifBlank { null },
                    )
                }
            } finally {
                onComplete?.invoke()
            }
        }
    }

    fun removeImage(id: String) {
        if (_state.value.uploading) return
        _state.update {
            it.copy(images = it.images.filterNot { image -> image.id == id }, notice = null, actionError = null)
        }
    }

    fun clearImages() {
        if (_state.value.uploading) return
        _state.update { it.copy(images = emptyList(), notice = "已清空待上传照片", actionError = null) }
    }

    fun upload() {
        val snapshot = _state.value
        if (snapshot.uploading) return
        val normalizedUrl = FaceEndpoint.normalize(snapshot.serviceUrl)
        val name = snapshot.personName.trim()
        when {
            normalizedUrl == null -> showError("服务地址无效，请填写 http:// 或 https:// 地址")
            name.isEmpty() -> showError("请输入人员姓名")
            name.length > 64 -> showError("姓名不能超过 64 个字符")
            snapshot.images.isEmpty() -> showError("请先拍照或从相册选择照片")
            snapshot.images.sumOf { it.sizeBytes.toLong() } > MAX_FACE_TOTAL_BYTES.toLong() ->
                showError("所有待上传 JPEG 合计不能超过 20 MiB")
            else -> {
                _state.update {
                    it.copy(
                        serviceUrl = normalizedUrl,
                        uploading = true,
                        actionError = null,
                        notice = "正在上传并录入人脸…",
                    )
                }
                preferences.edit().putString("service_url", normalizedUrl).apply()
                val generation = configurationGeneration
                viewModelScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        apiClient.enroll(
                            baseUrl = normalizedUrl,
                            name = name,
                            replace = snapshot.replaceExisting,
                            images = snapshot.images.map { it.jpeg },
                        )
                    }
                    if (generation != configurationGeneration) return@launch
                    when (result) {
                        is ApiResult.Success -> {
                            val receipt = result.value
                            _state.update {
                                it.copy(
                                    uploading = false,
                                    images = emptyList(),
                                    personName = receipt.name,
                                    orderPersonName = receipt.name,
                                    lastEnrolledPersonId = receipt.personId,
                                    lastEnrolledName = receipt.name,
                                    notice = receipt.message ?: receipt.totalSamples?.let { total ->
                                        "${receipt.name} 录入成功，新增 ${receipt.acceptedSamples} 张，远端共 $total 张样本"
                                    } ?: "${receipt.name} 录入成功，共 ${receipt.acceptedSamples} 张样本",
                                    actionError = null,
                                )
                            }
                            preferences.edit().apply {
                                putString("last_enrolled_name", receipt.name)
                                if (receipt.personId != null) {
                                    putString("last_enrolled_person_id", receipt.personId)
                                } else {
                                    remove("last_enrolled_person_id")
                                }
                            }.apply()
                            refreshStatus()
                        }
                        is ApiResult.Failure -> _state.update {
                            it.copy(uploading = false, notice = null, actionError = result.message)
                        }
                    }
                }
            }
        }
    }

    fun refreshStatus() {
        if (!pollingEnabledByLifecycle) return
        viewModelScope.launch { loadStatus() }
    }

    fun clearMessages() {
        _state.update { it.copy(notice = null, actionError = null) }
    }

    fun reportActionError(message: String) {
        showError(message)
    }

    private fun startPolling(immediate: Boolean) {
        if (!canPoll()) {
            stopPolling()
            return
        }
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            if (!immediate) delay(AUTO_REFRESH_INTERVAL_MS)
            while (isActive && canPoll()) {
                loadStatus()
                delay(AUTO_REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun loadStatus() {
        if (!pollingEnabledByLifecycle) return
        if (!statusMutex.tryLock()) return
        try {
            val snapshot = _state.value
            val generation = configurationGeneration
            _state.update { it.copy(statusLoading = true) }
            val result = withContext(Dispatchers.IO) {
                apiClient.getStatus(snapshot.serviceUrl)
            }
            if (generation != configurationGeneration || !pollingEnabledByLifecycle) return
            var activeSession = false
            _state.update {
                when (result) {
                    is ApiResult.Success -> {
                        val session = result.value.recognitionSession
                        activeSession = session?.active == true
                        it.copy(
                            serviceStatus = result.value,
                            recognitionSession = session ?: it.recognitionSession,
                            statusLoading = false,
                            statusError = null,
                            statusUpdatedAt = System.currentTimeMillis(),
                        )
                    }
                    is ApiResult.Failure -> it.copy(
                        statusLoading = false,
                        statusError = result.message,
                    )
                }
            }
            if (activeSession) ensureRecognitionPolling()
        } finally {
            statusMutex.unlock()
        }
    }

    private suspend fun loadCatalog(normalizedUrl: String) {
        if (!pollingEnabledByLifecycle || !catalogMutex.tryLock()) return
        try {
            val generation = configurationGeneration
            _state.update { it.copy(catalogLoading = true, catalogError = null) }
            val result = withContext(Dispatchers.IO) { apiClient.getCatalog(normalizedUrl) }
            if (generation != configurationGeneration || !pollingEnabledByLifecycle) return
            _state.update { state ->
                when (result) {
                    is ApiResult.Success -> {
                        val availableIds = result.value.filter(CatalogProduct::enabled)
                            .mapTo(mutableSetOf(), CatalogProduct::productId)
                        state.copy(
                            catalog = result.value,
                            productQuantities = state.productQuantities.filterKeys(availableIds::contains),
                            catalogLoading = false,
                            catalogError = null,
                            catalogLoadedAt = System.currentTimeMillis(),
                        )
                    }
                    is ApiResult.Failure -> state.copy(
                        catalogLoading = false,
                        catalogError = result.message,
                    )
                }
            }
        } finally {
            catalogMutex.unlock()
        }
    }

    private fun ensureRecognitionPolling() {
        if (!pollingEnabledByLifecycle || recognitionPollingJob?.isActive == true) return
        recognitionPollingJob = viewModelScope.launch {
            while (isActive && pollingEnabledByLifecycle && _state.value.recognitionSession?.active == true) {
                loadRecognitionSession()
                if (_state.value.recognitionSession?.active != true) break
                delay(RECOGNITION_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun loadRecognitionSession() {
        if (!pollingEnabledByLifecycle || !recognitionMutex.tryLock()) return
        try {
            val snapshot = _state.value
            val generation = configurationGeneration
            val result = withContext(Dispatchers.IO) {
                apiClient.getRecognitionSession(snapshot.serviceUrl)
            }
            if (generation != configurationGeneration || !pollingEnabledByLifecycle) return
            when (result) {
                is ApiResult.Success -> _state.update {
                    it.copy(recognitionSession = result.value, recognitionError = null)
                }
                is ApiResult.Failure -> _state.update { it.copy(recognitionError = result.message) }
            }
        } finally {
            recognitionMutex.unlock()
        }
    }

    private fun showError(message: String) {
        _state.update { it.copy(actionError = message, notice = null) }
    }

    private fun canPoll(): Boolean = pollingEnabledByLifecycle &&
        _state.value.autoRefresh

    private fun restartPollingAfterConfigurationChange() {
        stopPolling()
        if (canPoll()) startPolling(immediate = false)
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        apiClient.cancelStatusRequests()
        _state.update { it.copy(statusLoading = false) }
    }

    private fun stopRecognitionPolling() {
        recognitionPollingJob?.cancel()
        recognitionPollingJob = null
        apiClient.cancelRecognitionRequests()
        _state.update { it.copy(recognitionRequestLoading = false) }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        recognitionPollingJob?.cancel()
        orderJob?.cancel()
        apiClient.cancelStatusRequests()
        apiClient.cancelRecognitionRequests()
        apiClient.cancelCatalogRequests()
        apiClient.cancelOrderRequests()
        apiClient.close()
        super.onCleared()
    }
}

internal object OrderFormLogic {
    fun validationError(
        serviceUrl: String?,
        personName: String,
        roomNumber: String,
        catalog: List<CatalogProduct>,
        productQuantities: Map<String, Int>,
        lastEnrolledPersonId: String?,
        lastEnrolledName: String?,
    ): String? {
        val cleanName = personName.trim()
        val cleanRoom = roomNumber.trim()
        val enrolledName = lastEnrolledName?.trim().orEmpty()
        return when {
            serviceUrl == null -> "服务地址无效，请填写 http:// 或 https:// 地址"
            cleanName.isEmpty() -> "请输入已录入人脸的姓名"
            cleanName.length > 64 -> "姓名不能超过 64 个字符"
            lastEnrolledPersonId.isNullOrBlank() || enrolledName.isEmpty() || cleanName != enrolledName ->
                "请先使用“$cleanName”在人脸采集页面完成人脸录入"
            cleanRoom.isEmpty() -> "请输入房间号"
            cleanRoom.length > 32 -> "房间号不能超过 32 个字符"
            catalog.none(CatalogProduct::enabled) -> "当前没有可下单商品，请刷新商品列表"
            requestItems(catalog, productQuantities).isEmpty() -> "请至少选择一件商品"
            else -> null
        }
    }

    fun requestItems(
        catalog: List<CatalogProduct>,
        productQuantities: Map<String, Int>,
    ): List<OrderItemRequest> = catalog.asSequence()
        .filter(CatalogProduct::enabled)
        .mapNotNull { product ->
            val quantity = productQuantities[product.productId] ?: return@mapNotNull null
            if (quantity !in 1..MAX_ORDER_QUANTITY) return@mapNotNull null
            OrderItemRequest(product.productId, quantity)
        }
        .toList()
}
