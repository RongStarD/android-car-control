package cn.edu.xxq.faceenrollment

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.xxq.faceenrollment.data.ApiResult
import cn.edu.xxq.faceenrollment.data.DEFAULT_FACE_SERVICE_URL
import cn.edu.xxq.faceenrollment.data.FaceApiClient
import cn.edu.xxq.faceenrollment.data.FaceEndpoint
import cn.edu.xxq.faceenrollment.data.MAX_FACE_IMAGES
import cn.edu.xxq.faceenrollment.data.MAX_FACE_TOTAL_BYTES
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
    val notice: String? = null,
    val actionError: String? = null,
    val statusUpdatedAt: Long? = null,
)

class FaceEnrollmentViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, 0)
    private val apiClient = FaceApiClient()
    private val imageProcessor = FaceImageProcessor(application.contentResolver)
    private val statusMutex = Mutex()
    private var pollingJob: Job? = null
    private var pollingEnabledByLifecycle = false
    private var configurationGeneration = 0L

    private val _state = MutableStateFlow(
        FaceEnrollmentUiState(
            serviceUrl = preferences.getString("service_url", DEFAULT_FACE_SERVICE_URL)
                ?: DEFAULT_FACE_SERVICE_URL,
        ),
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
            )
        }
        preferences.edit().putString("service_url", value).apply()
        restartPollingAfterConfigurationChange()
    }

    fun setPersonName(value: String) {
        if (value.length <= 80 && '\n' !in value && '\r' !in value) {
            _state.update { it.copy(personName = value, actionError = null) }
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
        if (enabled && canPoll()) startPolling(immediate = true) else stopPolling()
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
                                    notice = receipt.message ?: receipt.totalSamples?.let { total ->
                                        "${receipt.name} 录入成功，新增 ${receipt.acceptedSamples} 张，远端共 $total 张样本"
                                    } ?: "${receipt.name} 录入成功，共 ${receipt.acceptedSamples} 张样本",
                                    actionError = null,
                                )
                            }
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
            _state.update {
                when (result) {
                    is ApiResult.Success -> it.copy(
                        serviceStatus = result.value,
                        statusLoading = false,
                        statusError = null,
                        statusUpdatedAt = System.currentTimeMillis(),
                    )
                    is ApiResult.Failure -> it.copy(
                        statusLoading = false,
                        statusError = result.message,
                    )
                }
            }
        } finally {
            statusMutex.unlock()
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

    override fun onCleared() {
        pollingJob?.cancel()
        apiClient.cancelStatusRequests()
        apiClient.close()
        super.onCleared()
    }
}
