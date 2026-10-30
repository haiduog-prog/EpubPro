package com.epubpro.core.storage

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import com.epubpro.core.convert.DictionaryBuilder
import com.epubpro.core.convert.HanVietConverter
import com.epubpro.core.convert.PhraseDictionary
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loại từ điển Convert do người dùng nhập, xếp theo độ ưu tiên giảm dần.
 *
 * @property fileName Tên file nhị phân đã biên dịch trong thư mục từ điển.
 */
enum class ConvertDictionaryKind(val fileName: String) {
    /** Tên riêng dùng chung cho mọi truyện (`Names.txt`). */
    NAMES("names.bin"),

    /** Cụm từ Trung → Việt (`VietPhrase.txt`). */
    VIET_PHRASE("vietphrase.bin"),

    /** Âm Hán-Việt từng chữ (`ChinesePhienAmWords.txt`). */
    PHIEN_AM("phienam.bin")
}

/**
 * File từ điển người dùng chọn vượt giới hạn dung lượng.
 *
 * @property limitBytes Giới hạn đã bị vượt.
 */
class DictionaryTooLargeException(val limitBytes: Long) :
    IOException("File từ điển vượt quá giới hạn ${limitBytes / (1024 * 1024)} MiB")

/**
 * Thông tin một từ điển đã nhập.
 *
 * @property kind Loại từ điển.
 * @property entryCount Số mục, null nếu chưa nhập.
 */
data class ConvertDictionaryInfo(val kind: ConvertDictionaryKind, val entryCount: Int?)

/**
 * Quản lý từ điển Convert Hán-Việt: nhập/biên dịch file người dùng chọn, nạp lười bằng mmap,
 * bảng Name riêng từng truyện và trạng thái bật Convert theo sách.
 *
 * Ứng dụng không đóng gói sẵn dữ liệu VietPhrase vì nguồn gốc license không rõ; người dùng tự nhập.
 */
@Singleton
class ConvertDictionaryStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val root = File(context.filesDir, "convert").apply { mkdirs() }
    private val bookNamesDir = File(root, "book_names").apply { mkdirs() }
    private val prefs: SharedPreferences = context.getSharedPreferences("convert_prefs", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var loadedLayers: Map<ConvertDictionaryKind, PhraseDictionary>? = null

    private val _revision = MutableStateFlow(0)

    /** Tăng mỗi khi từ điển chung hoặc Name của truyện thay đổi, để Reader convert lại chương. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /**
     * Liệt kê trạng thái các từ điển.
     *
     * @return Thông tin từng loại theo thứ tự ưu tiên.
     */
    suspend fun dictionaryInfo(): List<ConvertDictionaryInfo> = withContext(Dispatchers.IO) {
        ConvertDictionaryKind.entries.map { kind ->
            val file = dictionaryFile(kind)
            ConvertDictionaryInfo(kind, if (file.isFile) PhraseDictionary.readEntryCount(file) else null)
        }
    }

    /**
     * Có ít nhất từ điển VietPhrase hoặc Phiên âm để Convert cho ra kết quả đọc được.
     *
     * @return true nếu đã nhập một trong hai.
     */
    fun hasBaseDictionary(): Boolean =
        dictionaryFile(ConvertDictionaryKind.VIET_PHRASE).isFile || dictionaryFile(ConvertDictionaryKind.PHIEN_AM).isFile

    /**
     * Nhập và biên dịch file từ điển định dạng QuickTranslator (`中文=nghĩa`), tự nhận UTF-8/UTF-16 theo BOM.
     *
     * @param kind Loại từ điển.
     * @param uri URI file người dùng chọn.
     * @param onProgress Callback số dòng đã đọc.
     * @return Số mục sau khi khử trùng.
     * @throws DictionaryTooLargeException Nếu file vượt [MAX_DICTIONARY_BYTES].
     * @throws IllegalStateException Nếu file không đọc được, rỗng hoặc quá lớn so với bộ nhớ.
     */
    suspend fun importDictionary(
        kind: ConvertDictionaryKind,
        uri: Uri,
        onProgress: (Int) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            querySize(uri)?.let { size ->
                if (size > MAX_DICTIONARY_BYTES) throw DictionaryTooLargeException(MAX_DICTIONARY_BYTES)
            }
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Không mở được file từ điển")
            val dictionary = try {
                // Kích thước provider báo có thể thiếu/sai nên vẫn đếm byte thực tế khi đọc.
                BufferedInputStream(LimitedInputStream(input, MAX_DICTIONARY_BYTES)).use { stream ->
                    val builder = DictionaryBuilder()
                    InputStreamReader(stream, detectCharset(stream)).buffered().use { reader ->
                        builder.addQuickTranslatorLines(reader, onProgress)
                    }
                    builder.build()
                }
            } catch (error: OutOfMemoryError) {
                throw IllegalStateException("File từ điển quá lớn so với bộ nhớ thiết bị", error)
            }
            check(dictionary.size > 0) { "File từ điển không có mục hợp lệ" }
            dictionary.writeTo(dictionaryFile(kind))
            loadedLayers = null
            _revision.update { it + 1 }
            dictionary.size
        }
    }

    /**
     * Xóa một từ điển đã nhập.
     *
     * @param kind Loại từ điển.
     */
    suspend fun deleteDictionary(kind: ConvertDictionaryKind) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dictionaryFile(kind).delete()
            loadedLayers = null
            _revision.update { it + 1 }
        }
    }

    /**
     * Tạo bộ convert cho một cuốn sách: Name của truyện → Name chung → VietPhrase → Phiên âm.
     *
     * @param bookId ID sách.
     * @return Bộ convert, hoặc null nếu chưa nhập từ điển nền.
     */
    suspend fun createConverter(bookId: String): HanVietConverter? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!hasBaseDictionary()) return@withLock null
            val shared = loadedLayers ?: ConvertDictionaryKind.entries.mapNotNull { kind ->
                val file = dictionaryFile(kind)
                if (!file.isFile) null else runCatching { kind to PhraseDictionary.load(file) }.getOrNull()
            }.toMap().also { loadedLayers = it }
            val bookNames = readBookNames(bookId)
            val layers = buildList {
                if (bookNames.isNotEmpty()) add(PhraseDictionary.fromEntries(bookNames))
                ConvertDictionaryKind.entries.forEach { kind -> shared[kind]?.let(::add) }
            }
            HanVietConverter(layers)
        }
    }

    /**
     * Đọc bảng Name riêng của truyện.
     *
     * @param bookId ID sách.
     * @return Danh sách (chữ Hán, tên tiếng Việt) theo thứ tự đã lưu.
     */
    suspend fun bookNames(bookId: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        readBookNames(bookId)
    }

    /**
     * Thêm hoặc sửa một Name cho truyện.
     *
     * @param bookId ID sách.
     * @param source Chữ Hán.
     * @param target Tên tiếng Việt.
     */
    suspend fun saveBookName(bookId: String, source: String, target: String) = withContext(Dispatchers.IO) {
        val key = source.trim()
        val value = target.trim()
        if (key.isEmpty() || value.isEmpty() || key.contains('=') || key.contains('\n') || value.contains('\n')) {
            return@withContext
        }
        mutex.withLock {
            val updated = readBookNames(bookId).filterNot { it.first == key } + (key to value)
            writeBookNames(bookId, updated)
        }
        _revision.update { it + 1 }
    }

    /**
     * Xóa một Name của truyện.
     *
     * @param bookId ID sách.
     * @param source Chữ Hán cần xóa.
     */
    suspend fun deleteBookName(bookId: String, source: String) = withContext(Dispatchers.IO) {
        mutex.withLock { writeBookNames(bookId, readBookNames(bookId).filterNot { it.first == source }) }
        _revision.update { it + 1 }
    }

    /**
     * Đọc trạng thái bật Convert của sách.
     *
     * @param bookId ID sách.
     * @return true nếu người dùng đã bật.
     */
    fun isConvertEnabled(bookId: String): Boolean = prefs.getBoolean(enabledKey(bookId), false)

    /**
     * Ghi trạng thái bật Convert của sách.
     *
     * @param bookId ID sách.
     * @param enabled Trạng thái mới.
     */
    fun setConvertEnabled(bookId: String, enabled: Boolean) {
        prefs.edit().putBoolean(enabledKey(bookId), enabled).apply()
    }

    /**
     * Xóa dữ liệu Convert riêng của sách khi sách bị xóa khỏi thư viện.
     *
     * @param bookId ID sách.
     */
    suspend fun clearBook(bookId: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(enabledKey(bookId)).apply()
        bookNamesFile(bookId).delete()
    }

    /**
     * Đọc file Name của truyện theo định dạng `中文=Tên`.
     *
     * @param bookId ID sách.
     * @return Danh sách Name.
     */
    private fun readBookNames(bookId: String): List<Pair<String, String>> {
        val file = bookNamesFile(bookId)
        if (!file.isFile) return emptyList()
        return file.readLines(Charsets.UTF_8).mapNotNull(DictionaryBuilder::parseQuickTranslatorLine)
    }

    /**
     * Ghi file Name của truyện atomically.
     *
     * @param bookId ID sách.
     * @param names Danh sách Name.
     */
    private fun writeBookNames(bookId: String, names: List<Pair<String, String>>) {
        val file = bookNamesFile(bookId)
        if (names.isEmpty()) {
            file.delete()
            return
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(names.joinToString("\n") { (key, value) -> "$key=$value" }, Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    /**
     * Đọc kích thước file do provider báo.
     *
     * @param uri URI file.
     * @return Kích thước byte, hoặc null nếu provider không cung cấp.
     */
    private fun querySize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) cursor.getLong(column) else null
        }
    }.getOrNull()

    /**
     * Luồng đọc ném [DictionaryTooLargeException] khi vượt số byte cho phép.
     *
     * @param source Luồng nguồn.
     * @param limit Số byte tối đa.
     */
    private class LimitedInputStream(source: InputStream, private val limit: Long) : FilterInputStream(source) {
        private var total = 0L

        /**
         * Đọc một byte và cộng dồn bộ đếm.
         *
         * @return Byte đã đọc hoặc -1 khi hết luồng.
         */
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count(1)
            return value
        }

        /**
         * Đọc nhiều byte và cộng dồn bộ đếm.
         *
         * @param buffer Bộ đệm đích.
         * @param offset Vị trí bắt đầu ghi.
         * @param length Số byte tối đa.
         * @return Số byte đã đọc hoặc -1 khi hết luồng.
         */
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) count(read.toLong())
            return read
        }

        /**
         * Cộng số byte đã đọc và kiểm tra giới hạn.
         *
         * @param bytes Số byte vừa đọc.
         * @throws DictionaryTooLargeException Nếu tổng vượt giới hạn.
         */
        private fun count(bytes: Long) {
            total += bytes
            if (total > limit) throw DictionaryTooLargeException(limit)
        }
    }

    /**
     * Nhận charset từ BOM; mặc định UTF-8.
     *
     * @param stream Luồng hỗ trợ mark/reset.
     * @return Charset phù hợp.
     */
    private fun detectCharset(stream: InputStream): Charset {
        stream.mark(BOM_PROBE_BYTES)
        val first = stream.read()
        val second = stream.read()
        stream.reset()
        return when {
            first == 0xFF && second == 0xFE -> Charsets.UTF_16LE
            first == 0xFE && second == 0xFF -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
    }

    /**
     * Đường dẫn file nhị phân của một loại từ điển.
     *
     * @param kind Loại từ điển.
     * @return File trong thư mục từ điển.
     */
    private fun dictionaryFile(kind: ConvertDictionaryKind): File = File(root, kind.fileName)

    /**
     * Đường dẫn file Name của một sách (tên băm để không lộ ID trong đường dẫn).
     *
     * @param bookId ID sách.
     * @return File Name.
     */
    private fun bookNamesFile(bookId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(bookId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return File(bookNamesDir, "$digest.txt")
    }

    /**
     * Khóa SharedPreferences lưu trạng thái bật Convert.
     *
     * @param bookId ID sách.
     * @return Khóa lưu trữ.
     */
    private fun enabledKey(bookId: String): String = "enabled_$bookId"

    companion object {
        private const val BOM_PROBE_BYTES = 4

        /** Dung lượng tối đa của file từ điển nhập vào (VietPhrase lớn nhất thường dưới 100 MiB). */
        const val MAX_DICTIONARY_BYTES: Long = 200L * 1024L * 1024L
    }
}
