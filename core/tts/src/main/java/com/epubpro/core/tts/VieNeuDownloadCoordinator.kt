package com.epubpro.core.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trạng thái tải gói tài nguyên dùng chung của VieNeu-TTS v3-Nano ở phạm vi toàn ứng dụng.
 */
sealed interface VieNeuDownloadState {
    /**
     * Chưa tải xong hoặc đang rảnh (có thể đã có một phần `.tmp` hoặc file cũ trên máy).
     *
     * @property inventory Bản kiểm kê file hiện có trong thư mục model.
     */
    data class Idle(val inventory: ResourceInventory) : VieNeuDownloadState

    /**
     * Đang tải các file còn thiếu về thiết bị.
     *
     * @property progress Thông tin tiến trình tải theo file và tổng dung lượng.
     */
    data class Downloading(val progress: DownloadProgress) : VieNeuDownloadState

    /**
     * Đã tải đầy đủ và hợp lệ cả 7 file model + `sea_g2p.bin`.
     *
     * @property inventory Bản kiểm kê xác nhận `isReady == true`.
     */
    data class Ready(val inventory: ResourceInventory) : VieNeuDownloadState

    /**
     * Quá trình tải gặp lỗi hoặc thiết bị không hỗ trợ ABI `arm64-v8a`.
     *
     * @property message Mô tả lỗi cho UI hiển thị.
     * @property inventory Bản kiểm kê tại thời điểm phát sinh lỗi.
     */
    data class Error(
        val message: String,
        val inventory: ResourceInventory,
    ) : VieNeuDownloadState

    /**
     * Cho biết toàn bộ gói model VieNeu v3-Nano đã sẵn sàng để dùng hay chưa.
     */
    val isReady: Boolean
        get() = this is Ready

    /**
     * Cho biết có tác vụ tải VieNeu v3-Nano đang chạy hay không.
     */
    val isDownloading: Boolean
        get() = this is Downloading

    /**
     * Tỉ lệ hoàn thành từ `0f` đến `1f` dùng trực tiếp cho thanh tiến trình UI.
     */
    val progressFraction: Float
        get() = when (this) {
            is Downloading -> progress.fraction
            is Ready -> 1f
            is Idle, is Error -> 0f
        }
}

/**
 * Bộ điều phối tải model VieNeu-TTS v3-Nano ở cấp `@Singleton`.
 *
 * Duy trì tiến trình tải xuyên suốt vòng đời màn hình (Settings, Reader BottomSheet),
 * đảm bảo không tải trùng lặp và giữ lại file `.tmp` khi hủy để lần sau HTTP `Range` resume tiếp.
 *
 * @property downloader Bộ tải file model thô.
 * @property deviceSupportChecker Bộ kiểm tra hỗ trợ ABI `arm64-v8a`.
 * @property ioDispatcher Dispatcher chạy tác vụ tải nền.
 */
@Singleton
open class VieNeuDownloadCoordinator constructor(
    private val downloader: VieNeuModelDownloader,
    private val deviceSupportChecker: VieNeuDeviceSupportChecker,
    ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        downloader: VieNeuModelDownloader,
        deviceSupportChecker: VieNeuDeviceSupportChecker,
    ) : this(
        downloader = downloader,
        deviceSupportChecker = deviceSupportChecker,
        ioDispatcher = Dispatchers.IO,
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var activeJob: Job? = null

    private val _state = MutableStateFlow(initialState())

    /**
     * Luồng trạng thái tải VieNeu-TTS v3-Nano dùng chung cho các màn hình.
     */
    open val state: StateFlow<VieNeuDownloadState> = _state.asStateFlow()

    /**
     * Kiểm kê lại thư mục model trên đĩa và cập nhật trạng thái nếu không có đợt tải đang chạy.
     *
     * @return Bản kiểm kê mới nhất từ đĩa.
     */
    open fun refreshInventory(): ResourceInventory = synchronized(lock) {
        val inventory = downloader.inspectInventory()
        if (activeJob?.isActive != true) {
            _state.value = if (inventory.isReady) {
                VieNeuDownloadState.Ready(inventory)
            } else {
                VieNeuDownloadState.Idle(inventory)
            }
        }
        inventory
    }

    /**
     * Bắt đầu tải các file còn thiếu của gói VieNeu v3-Nano nếu chưa có tác vụ tải đang chạy.
     *
     * @return `true` nếu đã tải xong, đang tải hoặc vừa khởi chạy tải; `false` nếu thiết bị không hỗ trợ `arm64-v8a`.
     */
    open fun startDownload(): Boolean {
        synchronized(lock) {
            if (!deviceSupportChecker.isSupported()) {
                _state.value = VieNeuDownloadState.Error(
                    message = UNSUPPORTED_DEVICE_MESSAGE,
                    inventory = downloader.inspectInventory(),
                )
                return false
            }

            if (activeJob?.isActive == true) {
                return true
            }

            val currentInventory = downloader.inspectInventory()
            if (currentInventory.isReady) {
                _state.value = VieNeuDownloadState.Ready(currentInventory)
                return true
            }

            _state.value = VieNeuDownloadState.Downloading(
                DownloadProgress(
                    currentFile = VieNeuModelDownloader.REQUIRED_ARTIFACTS.first().fileName,
                    fileIndex = 1,
                    totalFiles = currentInventory.totalCount - currentInventory.presentCount,
                    downloadedBytes = currentInventory.existingBytes,
                    totalBytes = VieNeuModelDownloader.TOTAL_EXPECTED_BYTES,
                ),
            )

            lateinit var launchedJob: Job
            launchedJob = scope.launch {
                try {
                    downloader.downloadMissingArtifacts { progress ->
                        synchronized(lock) {
                            if (activeJob === launchedJob) {
                                _state.value = VieNeuDownloadState.Downloading(progress)
                            }
                        }
                    }
                    val finalInventory = downloader.inspectInventory()
                    synchronized(lock) {
                        if (activeJob === launchedJob) {
                            activeJob = null
                            _state.value = if (finalInventory.isReady) {
                                VieNeuDownloadState.Ready(finalInventory)
                            } else {
                                VieNeuDownloadState.Error(
                                    message = INCOMPLETE_MODEL_MESSAGE,
                                    inventory = finalInventory,
                                )
                            }
                        }
                    }
                } catch (cancel: CancellationException) {
                    synchronized(lock) {
                        if (activeJob === launchedJob) {
                            activeJob = null
                            _state.value = VieNeuDownloadState.Idle(downloader.inspectInventory())
                        }
                    }
                    throw cancel
                } catch (error: Throwable) {
                    synchronized(lock) {
                        if (activeJob === launchedJob) {
                            activeJob = null
                            _state.value = VieNeuDownloadState.Error(
                                message = error.message ?: DEFAULT_ERROR_MESSAGE,
                                inventory = downloader.inspectInventory(),
                            )
                        }
                    }
                }
            }
            activeJob = launchedJob
            return true
        }
    }

    /**
     * Hủy tác vụ tải đang chạy (nếu có), giữ lại file `.tmp` để lần sau có thể tải tiếp.
     */
    open fun cancelDownload() {
        val jobToCancel = synchronized(lock) {
            val current = activeJob
            activeJob = null
            _state.value = VieNeuDownloadState.Idle(downloader.inspectInventory())
            current
        }
        jobToCancel?.cancel()
    }

    /**
     * Hủy tải nếu đang chạy và xoá toàn bộ dữ liệu gói VieNeu v3-Nano trên máy.
     */
    open fun clearModel() {
        val jobToCancel = synchronized(lock) {
            val current = activeJob
            activeJob = null
            current
        }
        jobToCancel?.cancel()
        downloader.clearModelFiles()
        synchronized(lock) {
            if (activeJob == null) {
                _state.value = VieNeuDownloadState.Idle(downloader.inspectInventory())
            }
        }
    }

    /**
     * Tạo trạng thái ban đầu bằng cách kiểm kê đĩa ngay lúc khởi tạo coordinator.
     *
     * @return [VieNeuDownloadState.Ready] nếu đủ file, ngược lại [VieNeuDownloadState.Idle].
     */
    private fun initialState(): VieNeuDownloadState {
        val inventory = downloader.inspectInventory()
        return if (inventory.isReady) {
            VieNeuDownloadState.Ready(inventory)
        } else {
            VieNeuDownloadState.Idle(inventory)
        }
    }

    companion object {
        private const val UNSUPPORTED_DEVICE_MESSAGE = "Thiết bị này không hỗ trợ ABI 64-bit arm64-v8a cho VieNeu-TTS"
        private const val INCOMPLETE_MODEL_MESSAGE = "Gói VieNeu-TTS v3-Nano tải chưa đầy đủ"
        private const val DEFAULT_ERROR_MESSAGE = "Không thể tải gói model VieNeu-TTS v3-Nano"
    }
}
