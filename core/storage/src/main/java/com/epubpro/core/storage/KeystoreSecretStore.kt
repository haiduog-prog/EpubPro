package com.epubpro.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Lưu một chuỗi bí mật (API key, client key) đã mã hóa AES-GCM bằng khóa trong Android Keystore.
 *
 * File nằm trong `noBackupFilesDir` nên không đi vào bản sao lưu; khóa Keystore cũng không rời thiết bị,
 * nên dù file bị sao chép ra ngoài cũng không giải mã được.
 *
 * @param context Context ứng dụng để lấy thư mục `noBackupFilesDir`.
 * @param fileName Tên file chứa IV và bản mã, riêng cho từng bí mật.
 * @param keyAlias Alias khóa AES trong Android Keystore, riêng cho từng bí mật.
 */
internal class KeystoreSecretStore(
    context: Context,
    private val fileName: String,
    private val keyAlias: String
) {
    private val secretFile = File(context.noBackupFilesDir, fileName)

    /**
     * Mã hóa và ghi đè bí mật bằng cách ghi file tạm rồi đổi tên.
     *
     * @param secret Giá trị bí mật cần lưu.
     * @throws IllegalStateException Nếu không ghi được file.
     */
    fun save(secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encodedIv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encodedValue = Base64.encodeToString(
            cipher.doFinal(secret.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )

        val temporaryFile = File(secretFile.parentFile, "$fileName.tmp")
        temporaryFile.writeText(
            listOf(encodedIv, encodedValue).joinToString(System.lineSeparator()),
            Charsets.UTF_8
        )
        if (secretFile.exists() && !secretFile.delete()) {
            temporaryFile.delete()
            error("Không thể cập nhật bí mật đã lưu")
        }
        if (!temporaryFile.renameTo(secretFile)) {
            temporaryFile.delete()
            error("Không thể lưu bí mật")
        }
    }

    /**
     * Đọc và giải mã bí mật đã lưu.
     *
     * @return Bí mật, hoặc `null` nếu chưa lưu, file hỏng hoặc khóa Keystore không còn (ví dụ sau khi khôi phục
     *         sang máy khác).
     */
    fun load(): String? {
        if (!secretFile.exists()) return null
        return runCatching {
            val values = secretFile.readLines(Charsets.UTF_8)
            require(values.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(values[0], Base64.NO_WRAP))
            )
            String(
                cipher.doFinal(Base64.decode(values[1], Base64.NO_WRAP)),
                Charsets.UTF_8
            ).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Xóa bí mật đã lưu nếu có. */
    fun clear() {
        if (secretFile.exists()) {
            secretFile.delete()
        }
    }

    /**
     * Lấy khóa AES trong Android Keystore theo [keyAlias], tạo mới nếu chưa có.
     *
     * @return Khóa bí mật dùng cho AES-GCM.
     */
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
