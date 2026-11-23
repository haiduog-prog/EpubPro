package com.epubpro.core.ai

import com.epubpro.core.storage.AiPreferencesManager
import com.epubpro.core.storage.EpubStorageManager
import com.epubpro.domain.model.AiChapterCache
import com.epubpro.domain.model.AiChapterStatus
import com.epubpro.domain.model.AiRule
import com.epubpro.domain.model.AiRuleAction
import com.epubpro.domain.model.AiRuleScope
import com.epubpro.domain.repository.AiChapterRepository
import com.epubpro.domain.repository.AiRuleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class AiPolishProgress(
    val completedParts: Int,
    val totalParts: Int
)

data class CachedAiChapter(
    val html: String,
    val createdWithOldConfiguration: Boolean
)

@Singleton
class AiVietnameseService @Inject constructor(
    private val geminiClient: GeminiClient,
    private val preferencesManager: AiPreferencesManager,
    private val ruleRepository: AiRuleRepository,
    private val chapterRepository: AiChapterRepository,
    private val storageManager: EpubStorageManager
) {
    suspend fun testConnection(apiKey: String?, modelId: String) {
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: preferencesManager.getApiKey()
            ?: throw AiServiceException("Hãy nhập API key trước.")
        geminiClient.testConnection(key, modelId)
    }

    suspend fun loadCachedChapter(
        bookId: String,
        chapterIndex: Int,
        sourceHtml: String
    ): CachedAiChapter? = withContext(Dispatchers.IO) {
        val cache = chapterRepository.getChapterCache(bookId, chapterIndex)
            ?.takeIf { it.status == AiChapterStatus.COMPLETE }
            ?: return@withContext null
        if (cache.sourceHash != sha256(sourceHtml)) return@withContext null

        val html = storageManager.readAiChapter(cache.filePath)
            ?: return@withContext null
        val rules = effectiveRules(bookId)
        val currentConfigHash = configHash(preferencesManager.getSettings().modelId, rules)
        CachedAiChapter(
            html = html,
            createdWithOldConfiguration = cache.configHash != currentConfigHash
        )
    }

    suspend fun polishChapter(
        bookId: String,
        chapterIndex: Int,
        sourceHtml: String,
        onProgress: (AiPolishProgress) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val apiKey = preferencesManager.getApiKey()
            ?: throw AiServiceException("Hãy cấu hình Gemini API key trước.")
        val settings = preferencesManager.getSettings()
        val rules = effectiveRules(bookId)
        val sourceHash = sha256(sourceHtml)
        val configHash = configHash(settings.modelId, rules)
        val processor = AiHtmlProcessor.parse(sourceHtml)
        if (processor.blocks.isEmpty()) {
            throw AiServiceException("Chương này không có văn bản phù hợp để xử lý.")
        }

        val chunks = AiTextChunker.chunk(processor.blocks)
        val snapshot = loadProgress(bookId, chapterIndex)
        val replacements = if (
            snapshot?.sourceHash == sourceHash &&
            snapshot.configHash == configHash
        ) {
            snapshot.replacements.toMutableMap()
        } else {
            mutableMapOf()
        }

        val rulesText = rules.joinToString(System.lineSeparator()) { rule ->
            when (rule.action) {
                AiRuleAction.KEEP -> "GIỮ NGUYÊN: ${rule.source}"
                AiRuleAction.REPLACE -> "THAY: ${rule.source} -> ${rule.replacement.orEmpty()}"
            }
        }

        var completedParts = chunks.count { chunk ->
            chunk.all { replacements.containsKey(it.id) }
        }
        onProgress(AiPolishProgress(completedParts, chunks.size))

        chunks.forEachIndexed { index, chunk ->
            if (chunk.all { replacements.containsKey(it.id) }) return@forEachIndexed

            val previousContext = processor.blocks
                .getOrNull(processor.blocks.indexOf(chunk.first()) - 1)
                ?.let { previous ->
                    replacements[previous.id]
                        ?.let { Jsoup.parseBodyFragment(it).text() }
                        ?: previous.plainText
                }

            val polished = polishChunkWithRetry(
                apiKey = apiKey,
                modelId = settings.modelId,
                chunk = chunk,
                previousContext = previousContext,
                rules = rules,
                rulesText = rulesText,
                processor = processor
            )
            replacements.putAll(polished)
            completedParts = index + 1

            storageManager.saveAiProgress(
                bookId,
                chapterIndex,
                progressJson(sourceHash, configHash, replacements)
            )
            chapterRepository.upsertChapterCache(
                cacheRecord(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    sourceHash = sourceHash,
                    configHash = configHash,
                    modelId = settings.modelId,
                    status = AiChapterStatus.PARTIAL,
                    filePath = null,
                    completedParts = completedParts,
                    totalParts = chunks.size
                )
            )
            onProgress(AiPolishProgress(completedParts, chunks.size))
        }

        val polishedHtml = try {
            processor.render(replacements, rules)
        } catch (error: IllegalArgumentException) {
            storageManager.deleteAiProgress(bookId, chapterIndex)
            throw AiServiceException(error.message ?: "Không thể dựng chương AI", cause = error)
        } catch (error: IllegalStateException) {
            storageManager.deleteAiProgress(bookId, chapterIndex)
            throw AiServiceException(error.message ?: "Không thể dựng chương AI", cause = error)
        }
        val filePath = storageManager.saveAiChapter(bookId, chapterIndex, polishedHtml)
        storageManager.deleteAiProgress(bookId, chapterIndex)
        chapterRepository.upsertChapterCache(
            cacheRecord(
                bookId = bookId,
                chapterIndex = chapterIndex,
                sourceHash = sourceHash,
                configHash = configHash,
                modelId = settings.modelId,
                status = AiChapterStatus.COMPLETE,
                filePath = filePath,
                completedParts = chunks.size,
                totalParts = chunks.size
            )
        )
        polishedHtml
    }

    suspend fun deleteChapter(bookId: String, chapterIndex: Int) = withContext(Dispatchers.IO) {
        storageManager.deleteAiChapterCache(bookId, chapterIndex)
        chapterRepository.deleteChapterCache(bookId, chapterIndex)
    }

    private suspend fun polishChunkWithRetry(
        apiKey: String,
        modelId: String,
        chunk: List<AiTextBlock>,
        previousContext: String?,
        rules: List<AiRule>,
        rulesText: String,
        processor: AiHtmlProcessor
    ): Map<String, String> {
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val replacements = geminiClient.polish(
                    apiKey = apiKey,
                    modelId = modelId,
                    blocks = chunk,
                    previousContext = previousContext,
                    rulesText = rulesText
                )
                processor.validateBatch(chunk, replacements, rules)
                return replacements
            } catch (error: CancellationException) {
                throw error
            } catch (error: AiServiceException) {
                if (!error.retryable) throw error
                lastError = error
            } catch (error: IllegalArgumentException) {
                lastError = error
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw AiServiceException(
            lastError?.message ?: "Không thể kiểm tra kết quả từ Gemini."
        )
    }

    private suspend fun effectiveRules(bookId: String): List<AiRule> {
        val rules = ruleRepository.getRulesForBook(bookId)
            .sortedWith(
                compareBy<AiRule> { if (it.scope == AiRuleScope.GLOBAL) 0 else 1 }
                    .thenBy { it.updatedAt }
            )
        return rules.associateBy { rule ->
            if (rule.caseSensitive) rule.source else rule.source.lowercase()
        }.values.toList()
    }

    private fun cacheRecord(
        bookId: String,
        chapterIndex: Int,
        sourceHash: String,
        configHash: String,
        modelId: String,
        status: AiChapterStatus,
        filePath: String?,
        completedParts: Int,
        totalParts: Int
    ) = AiChapterCache(
        id = "$bookId:$chapterIndex",
        bookId = bookId,
        chapterIndex = chapterIndex,
        sourceHash = sourceHash,
        configHash = configHash,
        status = status,
        filePath = filePath,
        modelId = modelId,
        completedParts = completedParts,
        totalParts = totalParts,
        updatedAt = System.currentTimeMillis()
    )

    private fun loadProgress(bookId: String, chapterIndex: Int): ProgressSnapshot? {
        val json = storageManager.readAiProgress(bookId, chapterIndex) ?: return null
        return runCatching {
            val root = JSONObject(json)
            val values = root.getJSONObject("replacements")
            val replacements = buildMap {
                values.keys().forEach { id -> put(id, values.getString(id)) }
            }
            ProgressSnapshot(
                sourceHash = root.getString("sourceHash"),
                configHash = root.getString("configHash"),
                replacements = replacements
            )
        }.getOrNull()
    }

    private fun progressJson(
        sourceHash: String,
        configHash: String,
        replacements: Map<String, String>
    ): String = JSONObject()
        .put("sourceHash", sourceHash)
        .put("configHash", configHash)
        .put("replacements", JSONObject(replacements))
        .toString()

    private fun configHash(modelId: String, rules: List<AiRule>): String {
        val canonicalRules = rules.joinToString("|") { rule ->
            listOf(
                rule.scope.name,
                rule.source,
                rule.action.name,
                rule.replacement.orEmpty(),
                rule.caseSensitive.toString()
            ).joinToString(":")
        }
        return sha256("$modelId|$PROMPT_VERSION|$canonicalRules")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class ProgressSnapshot(
        val sourceHash: String,
        val configHash: String,
        val replacements: Map<String, String>
    )

    private companion object {
        const val PROMPT_VERSION = 1
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 700L
    }
}
