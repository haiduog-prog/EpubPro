package com.epubpro.core.tts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloader gói VieNeu-TTS v3 Turbo `onnx_int8` + codec MOSS-Audio-Tokenizer-Nano (phần giải mã streaming)
 * + từ điển `sea_g2p.bin`, lưu phẳng trong `files/vieneu-turbo/`.
 *
 * URL ghim theo revision để file luôn khớp engine. Nếu gói VieNeu Nano đã có `sea_g2p.bin` hợp lệ thì chép
 * sang thay vì tải lại 63 MB.
 *
 * @param modelDirProvider Hàm cung cấp thư mục đích.
 * @param nanoDirProvider Hàm cung cấp thư mục gói VieNeu Nano (nguồn chép `sea_g2p.bin`).
 */
@Singleton
open class VieNeuTurboModelDownloader constructor(
    modelDirProvider: () -> File,
    private val nanoDirProvider: () -> File,
) : VieNeuModelDownloader(modelDirProvider) {

    /** Bộ file của gói Turbo thay cho bộ file Nano. */
    override val artifacts: List<RemoteArtifactSpec>
        get() = TURBO_ARTIFACTS

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        modelDirProvider = { File(context.filesDir, MODEL_SUBDIR) },
        nanoDirProvider = { File(context.filesDir, VieNeuModelDownloader.MODEL_SUBDIR) },
    )

    /**
     * Chép `sea_g2p.bin` từ gói Nano (nếu có) rồi tải các file còn thiếu.
     *
     * @param onProgress Callback báo tiến trình trên luồng I/O.
     */
    override suspend fun downloadMissingArtifacts(onProgress: (DownloadProgress) -> Unit) {
        seedDictionaryFromNano()
        super.downloadMissingArtifacts(onProgress)
    }

    /** Chép từ điển `sea_g2p.bin` hợp lệ của gói Nano sang thư mục Turbo qua file `.tmp`. */
    private suspend fun seedDictionaryFromNano() = withContext(Dispatchers.IO) {
        val spec = TURBO_ARTIFACTS.first { it.fileName == VieNeuModelDownloader.SEA_G2P_DICT_FILE }
        val target = File(modelDir, VieNeuModelDownloader.SEA_G2P_DICT_FILE)
        if (target.isFile && target.length() == spec.expectedBytes) return@withContext
        val source = File(nanoDirProvider(), VieNeuModelDownloader.SEA_G2P_DICT_FILE)
        if (!source.isFile || source.length() != spec.expectedBytes) return@withContext
        runCatching {
            modelDir.mkdirs()
            val temp = File(modelDir, "${VieNeuModelDownloader.SEA_G2P_DICT_FILE}.tmp")
            source.copyTo(temp, overwrite = true)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }

    companion object {
        const val MODEL_SUBDIR = "vieneu-turbo"
        private const val TURBO_BASE =
            "https://huggingface.co/pnnbao-ump/VieNeu-TTS-v3-Turbo/resolve/61b85e3d937fbbacb387714180e8182823512523/onnx_int8"
        private const val CODEC_BASE =
            "https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/resolve/ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae"
        private const val SEA_G2P_URL = "https://github.com/pnnbao97/sea-g2p/releases/download/v0.10.0/sea_g2p.bin"

        /** Danh sách file bắt buộc của gói Turbo (kích thước đo từ bản đã ghim). */
        val TURBO_ARTIFACTS = listOf(
            RemoteArtifactSpec(VieNeuTurboEngine.CONFIG_FILE, "$TURBO_BASE/config.json", 2_152L),
            RemoteArtifactSpec(VieNeuTurboEngine.TOKENIZER_FILE, "$TURBO_BASE/tokenizer.json", 22_320L),
            RemoteArtifactSpec(VieNeuTurboEngine.PREFILL_FILE, "$TURBO_BASE/vieneu_prefill.onnx", 1_090_823L),
            RemoteArtifactSpec(VieNeuTurboEngine.DECODE_FILE, "$TURBO_BASE/vieneu_decode_step.onnx", 1_062_040L),
            RemoteArtifactSpec(VieNeuTurboEngine.ACOUSTIC_FILE, "$TURBO_BASE/vieneu_acoustic_cached.onnx", 7_207_223L),
            RemoteArtifactSpec(VieNeuTurboEngine.BACKBONE_DATA_FILE, "$TURBO_BASE/vieneu_backbone_shared.data", 103_891_968L),
            RemoteArtifactSpec(VieNeuTurboEngine.HEADS_FILE, "$TURBO_BASE/vieneu_v3_heads.npz", 52_219_622L),
            RemoteArtifactSpec(VieNeuTurboEngine.CODEC_META_FILE, "$CODEC_BASE/codec_browser_onnx_meta.json", 17_036L),
            RemoteArtifactSpec(VieNeuTurboEngine.CODEC_STEP_FILE, "$CODEC_BASE/moss_audio_tokenizer_decode_step.onnx", 351_400L),
            RemoteArtifactSpec(VieNeuTurboEngine.CODEC_DATA_FILE, "$CODEC_BASE/moss_audio_tokenizer_decode_shared.data", 44_198_912L),
            RemoteArtifactSpec(VieNeuModelDownloader.SEA_G2P_DICT_FILE, SEA_G2P_URL, 62_829_820L),
        )
    }
}

/**
 * Bộ điều phối tải gói VieNeu-TTS v3 Turbo ở cấp `@Singleton`, dùng chung logic với gói Nano
 * (resume `.tmp`, không tải trùng, giữ tiến trình xuyên màn hình).
 *
 * @param downloader Downloader gói Turbo.
 * @param deviceSupportChecker Bộ kiểm tra hỗ trợ ABI `arm64-v8a`.
 */
@Singleton
open class VieNeuTurboDownloadCoordinator @Inject constructor(
    downloader: VieNeuTurboModelDownloader,
    deviceSupportChecker: VieNeuDeviceSupportChecker,
) : VieNeuDownloadCoordinator(downloader, deviceSupportChecker, Dispatchers.IO)
