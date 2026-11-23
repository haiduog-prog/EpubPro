package com.epubpro.core.bookconverter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.epubpro.domain.model.BookSourceFormat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Chuyển PRC/MOBI/AZW3 reflowable không DRM thành EPUB chuẩn để dùng chung reader hiện tại.
 *
 * Native chỉ tái dựng resource; lớp này đảm bảo container EPUB, thứ tự entry, giới hạn kích
 * thước và thao tác commit atomically ở tầng Kotlin.
 */
@Singleton
class MobiEpubConverter @Inject constructor(
    private val formatSniffer: BookFormatSniffer
) {
    /**
     * Chuyển file nguồn thành EPUB tại đường dẫn đích.
     *
     * @param inputFile File PRC/MOBI/AZW3 đã được copy vào app-private storage.
     * @param outputFile File EPUB đích; chỉ xuất hiện sau khi đóng gói thành công.
     * @param onProgress Callback nhận tiến độ tùy chọn.
     * @return [BookConversionResult] chứa file EPUB và định dạng gốc.
     * @throws BookConversionException Nếu file không hợp lệ, có DRM, fixed-layout hoặc vượt giới hạn.
     */
    suspend fun convert(
        inputFile: File,
        outputFile: File,
        onProgress: suspend (BookConversionProgress) -> Unit = {}
    ): BookConversionResult = withContext(Dispatchers.IO) {
        val sourceFormat = formatSniffer.sniff(inputFile)
            ?: throw BookConversionException(BookConversionErrorCode.UNSUPPORTED_EXTENSION)
        if (sourceFormat == BookSourceFormat.EPUB || sourceFormat == BookSourceFormat.CBZ) {
            throw BookConversionException(BookConversionErrorCode.UNSUPPORTED_EXTENSION)
        }
        if (!inputFile.isFile || inputFile.length() <= 0L) {
            throw BookConversionException(BookConversionErrorCode.INVALID_OR_CORRUPTED_FILE)
        }
        if (inputFile.length() > MAX_INPUT_BYTES) {
            throw BookConversionException(BookConversionErrorCode.FILE_TOO_LARGE)
        }

        onProgress(BookConversionProgress(BookConversionStage.VALIDATING, 5))
        val parentDirectory = outputFile.parentFile
            ?: throw BookConversionException(BookConversionErrorCode.OUTPUT_FAILED)
        val temporaryDirectory = File(parentDirectory, ".mobi-${UUID.randomUUID()}")
        val temporaryEpub = File(parentDirectory, ".${outputFile.name}.${UUID.randomUUID()}.part")
        if (!temporaryDirectory.mkdirs() || !temporaryDirectory.isDirectory) {
            throw BookConversionException(BookConversionErrorCode.OUTPUT_FAILED)
        }
        try {
            coroutineContext.ensureActive()
            onProgress(BookConversionProgress(BookConversionStage.DECODING, 15))
            val nativeResult = MobiNativeDecoder.decodeToDirectory(
                inputFile.absolutePath,
                temporaryDirectory.absolutePath
            )
            throwForNativeResult(nativeResult)

            onProgress(BookConversionProgress(BookConversionStage.PACKAGING, 55))
            packageEpub(temporaryDirectory, temporaryEpub) { copied, total ->
                val progress = if (total <= 0L) 70 else {
                    (55 + copied * 30 / total).toInt().coerceAtMost(85)
                }
                onProgress(BookConversionProgress(BookConversionStage.PACKAGING, progress))
            }

            onProgress(BookConversionProgress(BookConversionStage.VALIDATING_EPUB, 90))
            validateEpubContainer(temporaryEpub)
            parentDirectory.mkdirs()
            try {
                Files.move(
                    temporaryEpub.toPath(),
                    outputFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporaryEpub.toPath(),
                    outputFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            onProgress(BookConversionProgress(BookConversionStage.COMPLETED, 100))
            BookConversionResult(outputFile, sourceFormat)
        } catch (error: BookConversionException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw BookConversionException(BookConversionErrorCode.INVALID_OR_CORRUPTED_FILE, error)
        } finally {
            temporaryDirectory.deleteRecursively()
            if (temporaryEpub.exists()) temporaryEpub.delete()
        }
    }

    /**
     * Ánh xạ mã trả về của libmobi sang lỗi miền ổn định.
     *
     * @param result Mã kết quả từ JNI.
     * @throws BookConversionException Khi native báo lỗi hoặc định dạng không hỗ trợ.
     */
    private fun throwForNativeResult(result: Int) {
        when (result) {
            0 -> Unit
            5, 11, 12, 13, 15 -> throw BookConversionException(BookConversionErrorCode.ENCRYPTED_OR_DRM)
            102 -> throw BookConversionException(BookConversionErrorCode.FIXED_LAYOUT_UNSUPPORTED)
            6 -> throw BookConversionException(BookConversionErrorCode.INVALID_OR_CORRUPTED_FILE)
            100, 101, 7, 8, 14 -> throw BookConversionException(BookConversionErrorCode.OUTPUT_FAILED)
            else -> throw BookConversionException(BookConversionErrorCode.INVALID_OR_CORRUPTED_FILE)
        }
    }

    /**
     * Đóng gói các part native thành archive EPUB với container tối thiểu.
     *
     * @param sourceDirectory Thư mục part đã tái dựng.
     * @param outputFile File archive tạm.
     * @param onProgress Callback tiến độ copy dữ liệu.
     */
    private suspend fun packageEpub(
        sourceDirectory: File,
        outputFile: File,
        onProgress: suspend (copiedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val files = sourceDirectory.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        val opf = files.firstOrNull { it.name.equals("content.opf", ignoreCase = true) }
        val htmlFiles = files.filter { it.extension.equals("html", true) || it.extension.equals("xhtml", true) }
        if (opf == null || htmlFiles.isEmpty()) {
            throw BookConversionException(BookConversionErrorCode.INVALID_OR_CORRUPTED_FILE)
        }
        val totalBytes = files.sumOf { it.length() }.coerceAtLeast(1L)
        if (totalBytes > MAX_OUTPUT_BYTES) {
            throw BookConversionException(BookConversionErrorCode.OUTPUT_FAILED)
        }
        var copiedBytes = 0L

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zip ->
            addStoredEntry(zip, "mimetype", "application/epub+zip".toByteArray(StandardCharsets.US_ASCII))
            addEntry(zip, "META-INF/container.xml", CONTAINER_XML.toByteArray(StandardCharsets.UTF_8))
            files.forEach { file ->
                coroutineContext.ensureActive()
                val entryName = "OEBPS/${file.name}"
                val size = file.length()
                if (size > MAX_ENTRY_BYTES) {
                    throw BookConversionException(BookConversionErrorCode.OUTPUT_FAILED)
                }
                addFileEntry(zip, entryName, file) { count ->
                    copiedBytes += count
                    onProgress(copiedBytes, totalBytes)
                }
            }
        }
        if (outputFile.length() > MAX_OUTPUT_BYTES) {
            throw BookConversionException(BookConversionErrorCode.OUTPUT_FAILED)
        }
    }

    /**
     * Thêm entry không nén, dùng bắt buộc cho `mimetype` theo chuẩn EPUB.
     *
     * @param zip Archive đang mở.
     * @param name Tên entry.
     * @param bytes Dữ liệu entry.
     */
    private fun addStoredEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val checksum = CRC32().apply { update(bytes) }
        zip.putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            crc = checksum.value
        })
        zip.write(bytes)
        zip.closeEntry()
    }

    /**
     * Thêm entry nén từ vùng nhớ.
     *
     * @param zip Archive đang mở.
     * @param name Tên entry.
     * @param bytes Dữ liệu entry.
     */
    private fun addEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    /**
     * Stream một file part vào archive để không giữ toàn bộ nội dung trong RAM.
     *
     * @param zip Archive đang mở.
     * @param name Tên entry trong archive.
     * @param file File nguồn.
     * @param onBytesCopied Callback số byte vừa copy.
     */
    private suspend fun addFileEntry(
        zip: ZipOutputStream,
        name: String,
        file: File,
        onBytesCopied: suspend (Long) -> Unit
    ) {
        zip.putNextEntry(ZipEntry(name))
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                zip.write(buffer, 0, read)
                onBytesCopied(read.toLong())
            }
        }
        zip.closeEntry()
    }

    /**
     * Kiểm tra các entry tối thiểu trước khi commit file EPUB.
     *
     * @param file Archive EPUB tạm cần kiểm tra.
     * @throws BookConversionException Khi container không hợp lệ.
     */
    private fun validateEpubContainer(file: File) {
        java.util.zip.ZipFile(file).use { zip ->
            val mimetypeEntry = zip.getEntry("mimetype")
                ?: throw BookConversionException(BookConversionErrorCode.OUTPUT_FAILED)
            val mimetype = zip.getInputStream(mimetypeEntry).use {
                String(it.readBytes(), StandardCharsets.US_ASCII).trim()
            }
            if (mimetype != "application/epub+zip" || zip.getEntry("META-INF/container.xml") == null ||
                zip.getEntry("OEBPS/content.opf") == null
            ) {
                throw BookConversionException(BookConversionErrorCode.OUTPUT_FAILED)
            }
        }
    }

    companion object {
        /** Giới hạn kích thước file nguồn cho import cục bộ. */
        const val MAX_INPUT_BYTES: Long = 100L * 1024L * 1024L
        private const val MAX_OUTPUT_BYTES: Long = 500L * 1024L * 1024L
        private const val MAX_ENTRY_BYTES: Long = 50L * 1024L * 1024L
        private val CONTAINER_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()
    }
}
