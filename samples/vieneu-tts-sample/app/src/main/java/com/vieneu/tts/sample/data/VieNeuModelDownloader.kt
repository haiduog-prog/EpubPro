package com.vieneu.tts.sample.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mô tả một file tài nguyên cần tải cho VieNeu-TTS v3-Nano + `sea-g2p`.
 *
 * @property fileName Tên file lưu trong thư mục model trên thiết bị.
 * @property url Đường dẫn tải trực tiếp từ Hugging Face hoặc GitHub Release.
 * @property expectedBytes Kích thước dự kiến theo byte để hiển thị tiến trình.
 */
data class RemoteArtifactSpec(
    val fileName: String,
    val url: String,
    val expectedBytes: Long,
)

/**
 * Trạng thái hiện tại của thư mục chứa model ONNX và từ điển `sea_g2p.bin`.
 *
 * @property modelDir Thư mục lưu trữ tài nguyên trên thiết bị.
 * @property presentCount Số file đã tồn tại và hợp lệ.
 * @property totalCount Tổng số file bắt buộc.
 * @property existingBytes Tổng dung lượng hiện có trên máy (byte).
 * @property missingBytes Tổng dung lượng ước tính còn thiếu (byte).
 */
data class ResourceInventory(
    val modelDir: File,
    val presentCount: Int,
    val totalCount: Int,
    val existingBytes: Long,
    val missingBytes: Long,
) {
    /**
     * Cho biết toàn bộ model và từ điển G2P đã sẵn sàng để nạp chưa.
     */
    val isReady: Boolean
        get() = presentCount == totalCount
}

/**
 * Tiến trình tải xuống tài nguyên theo từng file và tổng dung lượng.
 *
 * @property currentFile Tên file đang được tải.
 * @property fileIndex Chỉ số 1-based của file đang tải.
 * @property totalFiles Tổng số file cần tải trong đợt.
 * @property downloadedBytes Tổng số byte đã tải trong toàn đợt (bao gồm file đã có).
 * @property totalBytes Tổng số byte ước tính của toàn bộ tài nguyên.
 */
data class DownloadProgress(
    val currentFile: String,
    val fileIndex: Int,
    val totalFiles: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    /**
     * Tỉ lệ hoàn thành từ 0f đến 1f.
     */
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

/**
 * Quản lý kiểm tra và tải các file `VieNeu-TTS-v3-Nano` + `sea_g2p.bin` vào bộ nhớ ứng dụng.
 * Hỗ trợ HTTP Range resume trên file `.tmp` và tự động thử lại khi mạng rớt tạm thời.
 *
 * @param context Context ứng dụng dùng để lấy `filesDir`.
 */
class VieNeuModelDownloader(private val context: Context) {

    /**
     * Thư mục nội bộ lưu toàn bộ model ONNX, config, constants và `sea_g2p.bin`.
     */
    val modelDir: File
        get() = File(context.filesDir, MODEL_SUBDIR)

    /**
     * Kiểm tra danh sách tài nguyên hiện có trong thư mục model.
     *
     * @return Bản kiểm kê trạng thái các file bắt buộc.
     */
    fun inspectInventory(): ResourceInventory {
        val dir = modelDir
        var presentCount = 0
        var existingBytes = 0L
        var missingBytes = 0L
        for (spec in REQUIRED_ARTIFACTS) {
            val file = File(dir, spec.fileName)
            if (isValidFile(file, spec.expectedBytes)) {
                presentCount++
                existingBytes += file.length()
            } else {
                missingBytes += spec.expectedBytes
            }
        }
        return ResourceInventory(
            modelDir = dir,
            presentCount = presentCount,
            totalCount = REQUIRED_ARTIFACTS.size,
            existingBytes = existingBytes,
            missingBytes = missingBytes,
        )
    }

    /**
     * Tải toàn bộ các file còn thiếu về [modelDir], ghi qua file `.tmp` có hỗ trợ resume trước khi đổi tên.
     *
     * @param onProgress Callback báo tiến trình theo luồng gọi trên `Dispatchers.IO`.
     * @throws IOException Khi không tải được file sau các lần thử lại.
     */
    suspend fun downloadMissingArtifacts(
        onProgress: (DownloadProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val dir = modelDir
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Không tạo được thư mục model: ${dir.absolutePath}")
        }

        val missing = REQUIRED_ARTIFACTS.filter { spec ->
            !isValidFile(File(dir, spec.fileName), spec.expectedBytes)
        }
        if (missing.isEmpty()) return@withContext

        val totalExpectedBytes = REQUIRED_ARTIFACTS.sumOf { it.expectedBytes }
        var completedBytes = REQUIRED_ARTIFACTS
            .filter { isValidFile(File(dir, it.fileName), it.expectedBytes) }
            .sumOf { it.expectedBytes }

        missing.forEachIndexed { index, spec ->
            currentCoroutineContext().ensureActive()
            val target = File(dir, spec.fileName)
            val temp = File(dir, "${spec.fileName}.tmp")
            if (temp.exists() && temp.length() >= spec.expectedBytes) {
                temp.delete()
            }

            try {
                downloadWithRetry(
                    spec = spec,
                    destTemp = temp,
                    onBytes = { savedBytesInFile, knownTotalFileBytes ->
                        val effectiveSize = if (knownTotalFileBytes > 0L) knownTotalFileBytes else spec.expectedBytes
                        val cappedFileBytes = savedBytesInFile.coerceAtMost(effectiveSize)
                        onProgress(
                            DownloadProgress(
                                currentFile = spec.fileName,
                                fileIndex = index + 1,
                                totalFiles = missing.size,
                                downloadedBytes = completedBytes + cappedFileBytes,
                                totalBytes = totalExpectedBytes,
                            ),
                        )
                    },
                )
                if (target.exists() && !target.delete()) {
                    throw IOException("Không thể ghi đè file cũ: ${target.name}")
                }
                if (!temp.renameTo(target)) {
                    throw IOException("Không thể hoàn tất lưu file: ${target.name}")
                }
                completedBytes += target.length()
            } catch (cancel: CancellationException) {
                // Giữ lại file `.tmp` chưa hoàn tất để người dùng có thể bấm tải tiếp từ vị trí đã lưu.
                throw cancel
            } catch (t: Throwable) {
                throw t
            }
        }
    }

    /**
     * Tải một file với cơ chế thử lại tối đa [MAX_ATTEMPTS_PER_FILE] lần khi gặp lỗi mạng tạm thời.
     *
     * @param spec Thông tin file cần tải.
     * @param destTemp Đường dẫn file `.tmp` lưu trung gian.
     * @param onBytes Callback báo tổng số byte đã lưu trong file và tổng kích thước file.
     */
    private suspend fun downloadWithRetry(
        spec: RemoteArtifactSpec,
        destTemp: File,
        onBytes: (savedBytesInFile: Long, knownTotalFileBytes: Long) -> Unit,
    ) {
        var lastError: IOException? = null
        for (attempt in 1..MAX_ATTEMPTS_PER_FILE) {
            currentCoroutineContext().ensureActive()
            try {
                downloadSingleFile(spec, destTemp, onBytes)
                return
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (ioe: IOException) {
                lastError = ioe
                if (attempt < MAX_ATTEMPTS_PER_FILE) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            }
        }
        throw lastError ?: IOException("Không tải được ${spec.fileName}")
    }

    /**
     * Tải một file từ URL từ xa xuống file tạm (tự resume qua `Range` nếu `.tmp` hợp lệ).
     *
     * @param spec Mô tả tài nguyên cần tải.
     * @param destTemp Đường dẫn file `.tmp` đích.
     * @param onBytes Callback nhận tổng byte đã có trên đĩa và tổng kích thước file.
     */
    private suspend fun downloadSingleFile(
        spec: RemoteArtifactSpec,
        destTemp: File,
        onBytes: (savedBytesInFile: Long, knownTotalFileBytes: Long) -> Unit,
    ) {
        val resumeFrom = if (destTemp.isFile && destTemp.length() > 0L) destTemp.length() else 0L
        var connection = openFollowingRedirects(spec.url, rangeStart = resumeFrom)
        try {
            var code = connection.responseCode
            var effectiveOffset = 0L

            if (code == HTTP_RANGE_NOT_SATISFIABLE) {
                connection.disconnect()
                destTemp.delete()
                connection = openFollowingRedirects(spec.url, rangeStart = 0L)
                code = connection.responseCode
            }

            if (code !in 200..299) {
                throw IOException("HTTP $code khi tải ${spec.fileName}")
            }

            val appendMode = if (code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0L) {
                val rangeHeader = connection.getHeaderField("Content-Range")
                val rangeStart = parseContentRangeStart(rangeHeader)
                if (rangeStart == resumeFrom) {
                    effectiveOffset = resumeFrom
                    true
                } else {
                    destTemp.delete()
                    false
                }
            } else {
                false
            }

            val responseLength = connection.contentLengthLong
            val knownTotalFileBytes = if (responseLength > 0L) {
                effectiveOffset + responseLength
            } else {
                spec.expectedBytes
            }
            onBytes(effectiveOffset, knownTotalFileBytes)

            var bytesReadThisAttempt = 0L
            connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                FileOutputStream(destTemp, appendMode).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastEmit = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytesReadThisAttempt += read
                        if (bytesReadThisAttempt - lastEmit >= PROGRESS_EMIT_STEP_BYTES) {
                            lastEmit = bytesReadThisAttempt
                            onBytes(effectiveOffset + bytesReadThisAttempt, knownTotalFileBytes)
                        }
                    }
                    output.fd.sync()
                    onBytes(effectiveOffset + bytesReadThisAttempt, knownTotalFileBytes)
                }
            }

            if (responseLength > 0L && bytesReadThisAttempt != responseLength) {
                throw IOException(
                    "Kết nối bị ngắt sớm với ${spec.fileName} ($bytesReadThisAttempt/$responseLength bytes)",
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Trích xuất vị trí byte bắt đầu từ header `Content-Range` (ví dụ `bytes 1024-2047/4096`).
     *
     * @param header Giá trị header `Content-Range`.
     * @return Chỉ số byte bắt đầu, hoặc `null` nếu không hợp lệ.
     */
    internal fun parseContentRangeStart(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val match = CONTENT_RANGE_REGEX.find(header.trim()) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    /**
     * Mở kết nối HTTP GET và tự động theo các chuyển hướng 301/302/303/307/308 liên miền.
     *
     * @param initialUrl URL ban đầu.
     * @param rangeStart Vị trí byte muốn tiếp tục tải (`> 0` để gửi header `Range`).
     * @return Đối tượng [HttpURLConnection] tại điểm cuối phản hồi non-redirect.
     * @throws IOException Nếu thiếu header Location hoặc vượt quá số bước chuyển hướng.
     */
    private fun openFollowingRedirects(initialUrl: String, rangeStart: Long = 0L): HttpURLConnection {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                if (rangeStart > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
            }
            val status = conn.responseCode
            if (status in REDIRECT_STATUSES) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("Thiếu header Location khi chuyển hướng HTTP $status")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
            } else {
                return conn
            }
        }
        throw IOException("Vượt quá số lần chuyển hướng tối đa ($MAX_REDIRECTS)")
    }

    /**
     * Kiểm tra file trên máy có tồn tại và đạt ngưỡng kích thước tối thiểu hợp lệ hay không.
     *
     * @param file File cần kiểm tra.
     * @param expectedBytes Kích thước chuẩn dự kiến.
     * @return `true` nếu file hợp lệ.
     */
    private fun isValidFile(file: File, expectedBytes: Long): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val minSize = if (expectedBytes > 100_000L) (expectedBytes * 95L) / 100L else 100L
        return file.length() >= minSize
    }

    companion object {
        const val MODEL_SUBDIR = "vieneu-nano"
        const val SEA_G2P_DICT_FILE = "sea_g2p.bin"
        private const val HF_BASE = "https://huggingface.co/pnnbao-ump/VieNeu-TTS-v3-Nano/resolve/main"
        private const val SEA_G2P_URL = "https://github.com/pnnbao97/sea-g2p/releases/download/v0.10.0/sea_g2p.bin"
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_EMIT_STEP_BYTES = 256L * 1024L
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_REDIRECTS = 8
        private const val MAX_ATTEMPTS_PER_FILE = 3
        private const val RETRY_DELAY_MS = 1_000L
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val USER_AGENT = "VieNeuTtsSampleAndroid/1.0"
        private val CONTENT_RANGE_REGEX = Regex("""^bytes\s+(\d+)-(\d+|\*)/(\d+|\*)$""", RegexOption.IGNORE_CASE)
        private val REDIRECT_STATUSES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )

        val REQUIRED_ARTIFACTS = listOf(
            RemoteArtifactSpec("config.json", "$HF_BASE/config.json", 2_927L),
            RemoteArtifactSpec("constants.npz", "$HF_BASE/constants.npz", 53_448L),
            RemoteArtifactSpec("duration_predictor.onnx", "$HF_BASE/duration_predictor.onnx", 727_809L),
            RemoteArtifactSpec("text_encoder.onnx", "$HF_BASE/text_encoder.onnx", 26_519_943L),
            RemoteArtifactSpec("codec_decoder.onnx", "$HF_BASE/codec_decoder.onnx", 99_319_941L),
            RemoteArtifactSpec("vector_estimator.onnx", "$HF_BASE/vector_estimator.onnx", 155_132_418L),
            RemoteArtifactSpec(SEA_G2P_DICT_FILE, SEA_G2P_URL, 62_829_820L),
        )
    }
}
