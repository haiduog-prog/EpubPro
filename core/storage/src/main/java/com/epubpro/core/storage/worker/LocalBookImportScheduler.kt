package com.epubpro.core.storage.worker

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.epubpro.core.bookconverter.BookConversionErrorCode
import com.epubpro.core.bookconverter.BookConversionException
import com.epubpro.core.bookconverter.BookFormatSniffer
import com.epubpro.core.bookconverter.MobiEpubConverter
import com.epubpro.core.storage.BookFileTooLargeException
import com.epubpro.core.storage.EpubStorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.File
import java.util.zip.ZipFile
import com.epubpro.core.reader.engine.EpubPackageStructureParser
import com.epubpro.core.reader.engine.EpubReadLimits
import com.epubpro.domain.model.BookSourceFormat
import com.epubpro.domain.repository.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sao lưu và lập lịch chuyển đổi ebook local bằng WorkManager.
 *
 * URI chỉ được đọc một lần ở foreground ngắn; toàn bộ phần nặng chạy sau đó trên Worker,
 * vì vậy việc đóng ứng dụng hoặc process death không làm mất file nguồn.
 */
@Singleton
class LocalBookImportScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageManager: EpubStorageManager,
    private val formatSniffer: BookFormatSniffer,
    private val bookRepository: BookRepository
) {
    /**
     * Đọc ID trên dispatcher I/O và áp dụng giới hạn kích thước EPUB.
     *
     * @param file EPUB cần đọc.
     * @return ID xuất bản hoặc null khi không khai báo.
     * @throws java.io.IOException Nếu không đọc được ZIP.
     */
    private fun readIdentifier(file: File): String? {
        check(file.length() <= EpubReadLimits.MAX_EPUB_FILE_SIZE)
        return ZipFile(file).use { EpubPackageStructureParser.readPublicationIdentifier(it) }
    }

    /**
     * Áp dụng lựa chọn cho yêu cầu còn chờ; khóa ngăn hai lần bấm tạo hai tác vụ.
     *
     * @param sourcePath Khóa yêu cầu import.
     * @param replacementBookId Truyện đích hoặc null để thêm bản mới.
     * @return ID tác vụ hoặc null nếu yêu cầu đã được xử lý.
     * @throws IllegalStateException Nếu truyện đích không thuộc danh sách trùng ID.
     */
    suspend fun resolve(sourcePath: String, replacementBookId: String?): UUID? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pending = _pendingImports.value.firstOrNull { it.sourcePath == sourcePath }
                ?: return@withLock null
            check(replacementBookId == null || pending.matches.any { it.id == replacementBookId })
            val result = schedule(File(sourcePath), pending.originalName, pending.sourceFormat,
                replacementBookId, pending.identifier)
            _pendingImports.value = _pendingImports.value.filterNot { it.sourcePath == sourcePath }
            result
        }
    }

    /**
     * Hủy yêu cầu đang chờ và xóa bản sao nguồn chưa đưa vào thư viện.
     *
     * @param sourcePath Khóa yêu cầu cần hủy.
     */
    suspend fun dismiss(sourcePath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_pendingImports.value.any { it.sourcePath == sourcePath }) {
                _pendingImports.value = _pendingImports.value.filterNot { it.sourcePath == sourcePath }
                File(sourcePath).delete()
            }
            Unit
        }
    }

    private val mutex = Mutex()
    private val _pendingImports = MutableStateFlow<List<PendingLocalBookImport>>(emptyList())
    val pendingImports = _pendingImports.asStateFlow()

    /**
     * Sao lưu URI, kiểm tra phần mở rộng và enqueue một tác vụ chuyển đổi riêng.
     *
     * @param uri URI file người dùng chọn.
     * @param originalName Tên hiển thị gốc của file.
     * @return ID WorkManager, hoặc null khi đang chờ lựa chọn cho EPUB trùng ID.
     * @throws BookConversionException Nếu phần mở rộng không được hỗ trợ.
     * @throws BookFileTooLargeException Nếu file vượt giới hạn dung lượng của định dạng thực tế.
     */
    suspend fun enqueue(uri: Uri, originalName: String?): UUID? = withContext(Dispatchers.IO) {
        val sourceFile = storageManager.importLocalBookSource(uri, originalName)
        val sourceFormat = formatSniffer.sniff(sourceFile)
        if (sourceFormat == null) {
            sourceFile.delete()
            throw BookConversionException(BookConversionErrorCode.UNSUPPORTED_EXTENSION)
        }
        // File `.zip` được sao lưu với giới hạn truyện tranh (1 GiB); nếu thực tế là ebook chữ thì áp lại giới hạn 100 MiB.
        if (sourceFormat != BookSourceFormat.CBZ && sourceFile.length() > MobiEpubConverter.MAX_INPUT_BYTES) {
            sourceFile.delete()
            throw BookFileTooLargeException(MobiEpubConverter.MAX_INPUT_BYTES)
        }

        try {
            val identifier = if (sourceFormat == BookSourceFormat.EPUB) readIdentifier(sourceFile) else null
            val matches = if (identifier == null) emptyList() else {
                bookRepository.getAllBooks().first().filter { book ->
                    try {
                        readIdentifier(File(book.filePath)) == identifier
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            if (matches.isNotEmpty()) {
                mutex.withLock {
                    _pendingImports.value += PendingLocalBookImport(
                        sourceFile.absolutePath, originalName, sourceFormat, identifier.orEmpty(), matches
                    )
                }
                return@withContext null
            }
            schedule(sourceFile, originalName, sourceFormat)
        } catch (error: Throwable) {
            sourceFile.delete()
            throw error
        }
    }

    /**
     * Lập lịch bản sao đã kiểm tra sau khi người dùng quyết định cách xử lý.
     *
     * @param sourceFile File nguồn nội bộ.
     * @param originalName Tên file hiển thị.
     * @param sourceFormat Định dạng đã nhận diện.
     * @param replacementBookId ID nội bộ cần cập nhật, null nếu thêm mới.
     * @param identifier ID xuất bản cần kiểm tra lại khi cập nhật.
     * @return ID tác vụ WorkManager.
     */
    private fun schedule(
        sourceFile: File,
        originalName: String?,
        sourceFormat: BookSourceFormat,
        replacementBookId: String? = null,
        identifier: String? = null
    ): UUID {
        val outputFile = if (sourceFormat == BookSourceFormat.CBZ) {
            storageManager.createComicFile()
        } else {
            storageManager.createConvertedEpubFile()
        }
        val request = OneTimeWorkRequestBuilder<LocalBookImportWorker>()
            .setInputData(
                workDataOf(
                    LocalBookImportWorker.KEY_SOURCE_PATH to sourceFile.absolutePath,
                    LocalBookImportWorker.KEY_OUTPUT_PATH to outputFile.absolutePath,
                    LocalBookImportWorker.KEY_ORIGINAL_NAME to (originalName ?: sourceFile.name),
                    LocalBookImportWorker.KEY_SOURCE_FORMAT to sourceFormat.name,
                    LocalBookImportWorker.KEY_REPLACEMENT_BOOK_ID to replacementBookId,
                    LocalBookImportWorker.KEY_PUBLICATION_IDENTIFIER to identifier
                )
            )
            .addTag(LocalBookImportWorker.TAG)
            .build()
        return try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${LocalBookImportWorker.UNIQUE_WORK_NAME}_${sourceFile.nameWithoutExtension}",
                ExistingWorkPolicy.KEEP,
                request
            ).result.get()
            request.id
        } catch (error: Throwable) {
            sourceFile.delete()
            outputFile.delete()
            throw error
        }
    }
}
