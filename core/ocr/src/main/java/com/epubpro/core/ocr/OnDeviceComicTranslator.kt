package com.epubpro.core.ocr

import com.epubpro.domain.model.ComicSourceLanguage
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dịch văn bản sang tiếng Việt bằng ML Kit Translate chạy trên máy.
 *
 * Gói ngôn ngữ (~30 MB mỗi ngôn ngữ) chỉ được tải khi người dùng xác nhận qua [downloadModel];
 * [translate] không tự tải để tránh tốn dữ liệu di động ngoài ý muốn.
 */
@Singleton
class OnDeviceComicTranslator @Inject constructor() {

    /**
     * Kiểm tra gói dịch của ngôn ngữ nguồn và gói tiếng Việt đã có trên máy chưa.
     *
     * @param source Ngôn ngữ nguồn.
     * @return true nếu đủ gói để dịch offline.
     */
    suspend fun isModelReady(source: ComicSourceLanguage): Boolean {
        val manager = RemoteModelManager.getInstance()
        val sourceReady = manager.isModelDownloaded(TranslateRemoteModel.Builder(languageCode(source)).build()).await()
        val targetReady = manager.isModelDownloaded(TranslateRemoteModel.Builder(TranslateLanguage.VIETNAMESE).build()).await()
        return sourceReady && targetReady
    }

    /**
     * Tải gói dịch cần thiết.
     *
     * @param source Ngôn ngữ nguồn.
     * @param wifiOnly true để chỉ tải khi có Wi-Fi.
     * @throws Exception Nếu tải thất bại.
     */
    suspend fun downloadModel(source: ComicSourceLanguage, wifiOnly: Boolean) {
        val translator = Translation.getClient(options(source))
        try {
            val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
            translator.downloadModelIfNeeded(conditions).await()
        } finally {
            translator.close()
        }
    }

    /**
     * Dịch danh sách đoạn văn; gói ngôn ngữ phải có sẵn.
     *
     * @param texts Các đoạn cần dịch.
     * @param source Ngôn ngữ nguồn.
     * @return Bản dịch theo cùng thứ tự.
     * @throws Exception Nếu gói chưa tải hoặc dịch lỗi.
     */
    suspend fun translate(texts: List<String>, source: ComicSourceLanguage): List<String> {
        if (texts.isEmpty()) return emptyList()
        val translator = Translation.getClient(options(source))
        try {
            return texts.map { text -> translator.translate(text).await() }
        } finally {
            translator.close()
        }
    }

    /**
     * Tạo cấu hình dịch sang tiếng Việt.
     *
     * @param source Ngôn ngữ nguồn.
     * @return Cấu hình ML Kit.
     */
    private fun options(source: ComicSourceLanguage): TranslatorOptions =
        TranslatorOptions.Builder()
            .setSourceLanguage(languageCode(source))
            .setTargetLanguage(TranslateLanguage.VIETNAMESE)
            .build()

    /**
     * Mã ngôn ngữ ML Kit tương ứng.
     *
     * @param source Ngôn ngữ nguồn.
     * @return Mã BCP-47 dùng bởi ML Kit.
     */
    private fun languageCode(source: ComicSourceLanguage): String = when (source) {
        ComicSourceLanguage.CHINESE -> TranslateLanguage.CHINESE
        ComicSourceLanguage.JAPANESE -> TranslateLanguage.JAPANESE
        ComicSourceLanguage.KOREAN -> TranslateLanguage.KOREAN
        ComicSourceLanguage.ENGLISH -> TranslateLanguage.ENGLISH
    }
}
