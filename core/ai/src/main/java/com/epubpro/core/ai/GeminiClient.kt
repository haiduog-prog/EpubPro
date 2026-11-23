package com.epubpro.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AiServiceException(
    override val message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause)

@Singleton
class GeminiClient @Inject constructor() {
    suspend fun testConnection(apiKey: String, modelId: String) = withContext(Dispatchers.IO) {
        request(
            url = "$BASE_URL/models/$modelId",
            apiKey = apiKey,
            method = "GET",
            body = null
        )
    }

    suspend fun polish(
        apiKey: String,
        modelId: String,
        blocks: List<AiTextBlock>,
        previousContext: String?,
        rulesText: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val inputBlocks = JSONArray().apply {
            blocks.forEach { block ->
                put(JSONObject().put("id", block.id).put("html", block.html))
            }
        }

        val prompt = buildString {
            appendLine("Bạn là biên tập viên truyện tiếng Việt. Hãy làm câu văn tự nhiên, rõ nghĩa và dễ đọc hơn.")
            appendLine("Không dịch sang ngôn ngữ khác, không tóm tắt, không thêm hoặc xóa tình tiết.")
            appendLine("Giữ nguyên tên riêng, danh xưng, địa danh, môn phái, cảnh giới và chiêu thức.")
            appendLine("Không tự chuyển thuật ngữ Hán Việt sang nghĩa thuần Việt.")
            appendLine("Mỗi html là innerHTML của một khối. Chỉ sửa text node; giữ nguyên tuyệt đối tag, thứ tự tag và mọi thuộc tính.")
            appendLine("Trả đúng một JSON object theo schema, đủ và chỉ gồm các id đầu vào.")
            appendLine()
            appendLine("QUY TẮC BẮT BUỘC:")
            appendLine(rulesText.ifBlank { "Không có quy tắc bổ sung." })
            if (!previousContext.isNullOrBlank()) {
                appendLine()
                appendLine("NGỮ CẢNH TRƯỚC (chỉ tham khảo, không trả lại):")
                appendLine(previousContext.takeLast(1_500))
            }
            appendLine()
            appendLine("CÁC KHỐI CẦN XỬ LÝ:")
            append(inputBlocks.toString())
        }

        val responseSchema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject().put(
                    "blocks",
                    JSONObject()
                        .put("type", "ARRAY")
                        .put(
                            "items",
                            JSONObject()
                                .put("type", "OBJECT")
                                .put(
                                    "properties",
                                    JSONObject()
                                        .put("id", JSONObject().put("type", "STRING"))
                                        .put("html", JSONObject().put("type", "STRING"))
                                )
                                .put("required", JSONArray().put("id").put("html"))
                        )
                )
            )
            .put("required", JSONArray().put("blocks"))

        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.3)
                    .put("maxOutputTokens", 16_384)
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", responseSchema)
            )

        val response = request(
            url = "$BASE_URL/models/$modelId:generateContent",
            apiKey = apiKey,
            method = "POST",
            body = body.toString()
        )
        parsePolishResponse(response)
    }

    private suspend fun request(
        url: String,
        apiKey: String,
        method: String,
        body: String?
    ): String = suspendCancellableCoroutine { continuation ->
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        val future = requestExecutor.submit {
            try {
                if (body != null) {
                    connection.outputStream.use { output ->
                        output.write(body.toByteArray(Charsets.UTF_8))
                    }
                }

                val responseCode = connection.responseCode
                val responseBody = (
                    if (responseCode in 200..299) connection.inputStream else connection.errorStream
                    )?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                if (responseCode !in 200..299) {
                    throw mapHttpError(responseCode, responseBody)
                }
                if (continuation.isActive) {
                    continuation.resume(responseBody)
                }
            } catch (error: CancellationException) {
                if (continuation.isActive) {
                    continuation.cancel(error)
                }
            } catch (error: AiServiceException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            } catch (error: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        AiServiceException(
                            message = "Không thể kết nối Gemini. Hãy kiểm tra mạng và thử lại.",
                            retryable = true,
                            cause = error
                        )
                    )
                }
            } finally {
                runCatching { connection.disconnect() }
            }
        }

        continuation.invokeOnCancellation {
            runCatching { connection.disconnect() }
            future.cancel(true)
        }
    }

    private fun parsePolishResponse(response: String): Map<String, String> {
        try {
            val root = JSONObject(response)
            val text = root.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            val blocks = JSONObject(text).getJSONArray("blocks")
            return buildMap {
                for (index in 0 until blocks.length()) {
                    val block = blocks.getJSONObject(index)
                    val id = block.getString("id")
                    require(!containsKey(id)) { "AI trả về ID trùng lặp" }
                    put(id, block.getString("html"))
                }
            }
        } catch (error: Exception) {
            throw AiServiceException(
                message = "Gemini trả về dữ liệu không đúng định dạng.",
                retryable = true
            )
        }
    }

    private fun mapHttpError(code: Int, body: String): AiServiceException {
        val serverMessage = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return when (code) {
            400 -> AiServiceException(serverMessage ?: "Yêu cầu gửi tới Gemini không hợp lệ.")
            401, 403 -> AiServiceException("API key không hợp lệ hoặc không có quyền dùng model.")
            404 -> AiServiceException(
                serverMessage ?: "Model đã chọn không khả dụng."
            )
            408, 500, 502, 503, 504 -> AiServiceException(
                "Gemini đang tạm thời không phản hồi. Hãy thử lại.",
                retryable = true
            )
            429 -> AiServiceException(
                "Đã hết hạn mức hoặc Gemini đang giới hạn số yêu cầu.",
                retryable = true
            )
            else -> AiServiceException(serverMessage ?: "Gemini trả về lỗi $code.")
        }
    }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 120_000
        private val threadCounter = AtomicInteger(1)
        private val requestExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "gemini-http-${threadCounter.getAndIncrement()}").apply {
                isDaemon = true
            }
        }
    }
}
