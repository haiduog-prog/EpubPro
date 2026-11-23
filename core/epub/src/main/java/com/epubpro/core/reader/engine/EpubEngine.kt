package com.epubpro.core.reader.engine

import android.content.Context
import com.epubpro.core.reader.engine.EpubReadLimits.readBoundedText
import com.epubpro.domain.model.Book
import com.epubpro.domain.repository.SearchRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import org.jsoup.Jsoup

/**
 * Tiêu đề và vị trí định danh của một chương sách EPUB.
 *
 * @property index Thứ tự chỉ số chương trong toàn bộ cuốn sách (0-indexed).
 * @property title Tiêu đề hiển thị của chương.
 * @property entryName Đường dẫn tệp entry bên trong tệp nén Zip.
 */
data class EpubChapterHeader(
    val index: Int,
    val title: String,
    val entryName: String
)

/**
 * Nội dung chi tiết của một chương sách EPUB.
 *
 * @property index Thứ tự chỉ số chương trong sách.
 * @property title Tiêu đề chương.
 * @property htmlContent Nội dung mã HTML/XHTML đã được chuẩn hóa của chương.
 */
data class EpubChapterContent(
    val index: Int,
    val title: String,
    val htmlContent: String
)

typealias ReadiumEngine = EpubEngine

/**
 * Engine xử lý trích xuất metadata, phân tích cấu trúc chương sách và nạp nội dung EPUB.
 *
 * Hỗ trợ tối ưu hóa hiệu năng mở sách bằng:
 * 1. Persistent Structure Cache ([EpubStructureCache]) lưu trữ danh sách chương trên đĩa.
 * 2. Phân tích TOC-first ([EpubPackageStructureParser]) trích xuất tiêu đề từ Navigation Document/NCX trong 1 lượt I/O.
 * 3. Byte-Budget Memory Cache ([EpubChapterMemoryCache]) lưu trữ RAM cho các chương đã chuẩn hóa.
 *
 * @param context Context ứng dụng Android.
 * @param structureCache Bộ quản lý cache cấu trúc chương trên đĩa.
 * @param chapterMemoryCache Bộ nhớ đệm RAM cho nội dung các chương.
 */
@Singleton
class EpubEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val structureCache: EpubStructureCache,
    private val chapterMemoryCache: EpubChapterMemoryCache
) {
    /**
     * Constructor phụ phục vụ khởi tạo trong môi trường Unit Test hoặc khi không có DI container.
     */
    constructor(
        context: Context
    ) : this(
        context = context,
        structureCache = EpubStructureCache(context),
        chapterMemoryCache = EpubChapterMemoryCache()
    )

    /**
     * Trích xuất tệp ảnh bìa từ file EPUB và lưu vào thư mục covers nội bộ của ứng dụng.
     *
     * @param file Tệp EPUB trên bộ nhớ thiết bị.
     * @return Đường dẫn tuyệt đối đến tệp ảnh bìa đã trích xuất hoặc null nếu không tìm thấy.
     */
    suspend fun extractCoverImage(file: File): String? = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() > EpubReadLimits.MAX_EPUB_FILE_SIZE) return@withContext null
        try {
            ZipFile(file).use { zip ->
                val parsed = EpubPackageStructureParser.parseStructure(zip)
                val coverEntry = parsed.coverEntry ?: return@withContext null
                extractCoverEntry(zip, file, coverEntry)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Trích xuất một entry ảnh bìa vào file tạm rồi đổi tên nguyên tử sang file đích.
     *
     * @param zip Archive EPUB đang mở.
     * @param file Tệp EPUB nguồn.
     * @param coverEntry Entry ảnh bìa đã được parser xác định.
     * @return Đường dẫn tuyệt đối đến ảnh bìa hoặc null nếu không thể ghi ảnh.
     * @throws Exception Nếu entry vượt giới hạn hoặc thao tác đọc/ghi thất bại.
     */
    private fun extractCoverEntry(zip: ZipFile, file: File, coverEntry: ZipEntry): String? {
        EpubReadLimits.validateZipEntry(coverEntry)
        val baseDir = try { context.filesDir } catch (_: Exception) { null } ?: file.parentFile ?: return null
        val coversDir = File(baseDir, "covers").apply { if (!exists() && !mkdirs()) return null }
        val extension = coverEntry.name.substringAfterLast('.', "jpg").lowercase()
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
        val coverFile = File(coversDir, "${file.nameWithoutExtension}_cover.$extension")
        val temporaryFile = File.createTempFile("${file.nameWithoutExtension}_cover", ".tmp", coversDir)
        return try {
            zip.getInputStream(coverEntry).use { input ->
                temporaryFile.outputStream().use { output ->
                    EpubReadLimits.copyBounded(input, output)
                }
            }
            if (temporaryFile.length() <= 0L) return null
            try {
                Files.move(
                    temporaryFile.toPath(),
                    coverFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(
                    temporaryFile.toPath(),
                    coverFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            coverFile.absolutePath
        } finally {
            if (temporaryFile.exists()) temporaryFile.delete()
        }
    }

    /**
     * Trích xuất thông tin metadata cơ bản (tiêu đề, tác giả, số lượng chương) từ tệp EPUB.
     *
     * @param file Tệp EPUB trên bộ nhớ thiết bị.
     * @return Đối tượng [Book] chứa metadata đã trích xuất.
     * @throws IllegalStateException Nếu kích thước tệp vượt quá giới hạn an toàn [EpubReadLimits.MAX_EPUB_FILE_SIZE].
     */
    suspend fun parseEpubMetadata(file: File): Book = withContext(Dispatchers.IO) {
        if (file.length() > EpubReadLimits.MAX_EPUB_FILE_SIZE) {
            throw IllegalStateException("File EPUB vượt quá kích thước tối đa cho phép (${EpubReadLimits.MAX_EPUB_FILE_SIZE / (1024 * 1024)}MB)")
        }

        var title = file.nameWithoutExtension
        var author = "Unknown Author"
        var coverPath: String? = null

        try {
            ZipFile(file).use { zip ->
                val parsed = EpubPackageStructureParser.parseStructure(zip)
                if (parsed.title.isNotBlank() && parsed.title != "Unknown Title") {
                    title = parsed.title
                }
                if (parsed.author.isNotBlank() && parsed.author != "Unknown Author") {
                    author = parsed.author
                }
                if (parsed.coverEntry != null) {
                    try {
                        coverPath = extractCoverEntry(zip, file, parsed.coverEntry)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        coverPath = null
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val totalChapters = try {
            extractChapterHeaders(file).size
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        }

        Book(
            id = file.name,
            title = title,
            author = author,
            coverPath = coverPath,
            filePath = file.absolutePath,
            addedAt = System.currentTimeMillis(),
            lastReadAt = System.currentTimeMillis(),
            totalChapters = totalChapters
        )
    }

    /**
     * Kiểm tra EPUB tải xuống theo các điều kiện tối thiểu trước khi đưa vào thư viện.
     *
     * @param file Tệp EPUB đã tải xuống.
     * @return Metadata của sách sau khi cấu trúc EPUB hợp lệ.
     * @throws IllegalStateException Nếu file không phải EPUB hợp lệ hoặc vượt giới hạn đọc.
     */
    suspend fun parseEpubMetadataStrict(file: File): Book = withContext(Dispatchers.IO) {
        if (!file.isFile) {
            throw IllegalStateException("Không tìm thấy file EPUB tải xuống")
        }
        if (file.length() > EpubReadLimits.MAX_EPUB_FILE_SIZE) {
            throw IllegalStateException("File EPUB vượt quá kích thước tối đa cho phép")
        }

        try {
            ZipFile(file).use { zip ->
                val mimetypeEntry = zip.getEntry("mimetype")
                    ?: throw IllegalStateException("EPUB thiếu entry mimetype")
                val mimetype = zip.getInputStream(mimetypeEntry).use { input ->
                    val bytes = ByteArray(64)
                    val count = input.read(bytes)
                    String(bytes, 0, count.coerceAtLeast(0), Charsets.US_ASCII).trim()
                }
                check(mimetype == "application/epub+zip") {
                    "EPUB có mimetype không hợp lệ"
                }
                val structure = EpubPackageStructureParser.parseStructure(zip)
                check(structure.orderedEntries.isNotEmpty()) {
                    "EPUB không có nội dung chương"
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException("Không thể đọc file EPUB tải xuống", error)
        }

        parseEpubMetadata(file)
    }

    /**
     * Trích xuất danh sách tiêu đề chương gọn nhẹ ([EpubChapterHeader]) từ tệp EPUB mà không cần nạp toàn bộ HTML vào RAM.
     *
     * Tự động kiểm tra [EpubStructureCache] theo dấu vân tay [EpubCacheFingerprint]. Nếu trúng cache (hit),
     * trả về kết quả ngay lập tức (0ms) mà không mở tệp Zip.
     * Nếu trượt cache (miss), phân tích bằng [EpubPackageStructureParser] theo thứ tự spine và TOC mapping,
     * sau đó tự động lưu vào cache bền vững.
     *
     * @param file Tệp EPUB cần lấy danh sách chương.
     * @return Danh sách [EpubChapterHeader] theo đúng thứ tự đọc của tác giả.
     */
    suspend fun extractChapterHeaders(file: File): List<EpubChapterHeader> = withContext(Dispatchers.IO) {
        if (file.length() > EpubReadLimits.MAX_EPUB_FILE_SIZE) {
            throw IllegalArgumentException("File EPUB vượt quá kích thước tối đa cho phép (${EpubReadLimits.MAX_EPUB_FILE_SIZE / (1024 * 1024)}MB)")
        }
        val fingerprint = EpubCacheFingerprint.fromFile(file)
        val cachedHeaders = structureCache.readHeaders(fingerprint)
        if (cachedHeaders != null && cachedHeaders.isNotEmpty()) {
            return@withContext cachedHeaders
        }

        val headers = mutableListOf<EpubChapterHeader>()
        try {
            ZipFile(file).use { zip ->
                val parsed = EpubPackageStructureParser.parseStructure(zip)
                val tocTitles = parsed.chapterTitles

                parsed.orderedEntries.forEachIndexed { index, entry ->
                    val rawTitle = tocTitles[entry.name]
                        ?: tocTitles[entry.name.substringAfterLast('/')]
                        ?: tocTitles.entries.firstOrNull {
                            entry.name.equals(it.key, ignoreCase = true) ||
                                entry.name.endsWith("/${it.key}", ignoreCase = true)
                        }?.value
                        ?: try {
                            val buffer = ByteArray(8192)
                            val readBytes = zip.getInputStream(entry).use { it.read(buffer, 0, buffer.size) }
                            if (readBytes > 0) {
                                val sample = String(buffer, 0, readBytes, Charsets.UTF_8)
                                extractXmlTag(sample, "title")
                                    ?: extractXmlTag(sample, "h1")
                                    ?: extractXmlTag(sample, "h2")
                                    ?: "Chương ${index + 1}"
                            } else {
                                "Chương ${index + 1}"
                            }
                        } catch (e: Exception) {
                            "Chương ${index + 1}"
                        }

                    val title = sanitizeChapterTitle(rawTitle)
                        .ifBlank { "Chương ${index + 1}" }

                    headers.add(
                        EpubChapterHeader(
                            index = index,
                            title = title,
                            entryName = entry.name
                        )
                    )
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Không thể đọc cấu trúc EPUB", e)
        }

        structureCache.saveHeaders(fingerprint, headers)
        headers
    }

    /**
     * Nạp nội dung mã HTML của một chương theo yêu cầu (on-demand), áp dụng [HtmlNormalizer]
     * và lưu trữ trong [EpubChapterMemoryCache] với cơ chế Single-Flight.
     *
     * @param file Tệp EPUB chứa nội dung.
     * @param entryName Tên entry tệp chương cần nạp.
     * @return Chuỗi mã HTML đã được chuẩn hóa.
     */
    suspend fun loadChapterHtml(file: File, entryName: String): String = withContext(Dispatchers.IO) {
        val fingerprint = EpubCacheFingerprint.fromFile(file)
        chapterMemoryCache.getOrLoad(fingerprint, entryName) {
            try {
                ZipFile(file).use { zip ->
                    val entry = zip.getEntry(entryName)
                    if (entry != null) {
                        EpubReadLimits.validateZipEntry(entry)
                        val rawHtml = zip.getInputStream(entry).use { it.readBoundedText() }
                        return@getOrLoad HtmlNormalizer.normalize(rawHtml)
                    }
                }
            } catch (e: Exception) {
                throw IllegalStateException("Không thể tải nội dung chương: $entryName", e)
            }
            throw IllegalStateException("Không tìm thấy nội dung chương: $entryName")
        }
    }

    /**
     * Xóa cache cấu trúc đĩa và cache RAM khi tệp sách bị xóa khỏi thư viện.
     *
     * @param filePath Đường dẫn tệp sách bị xóa.
     */
    fun deleteBookCache(filePath: String) {
        val canonicalPath = File(filePath).canonicalFile.absolutePath
        structureCache.deleteCache(canonicalPath)
        chapterMemoryCache.evictBook(canonicalPath)
    }

    /**
     * Đánh chỉ mục tìm kiếm FTS ngầm cho nội dung toàn bộ sách theo từng đợt (batch).
     *
     * @param file Tệp EPUB cần đánh chỉ mục.
     * @param bookId Mã định danh của cuốn sách.
     * @param searchRepository Repository tìm kiếm dữ liệu.
     */
    suspend fun indexBookContent(file: File, bookId: String, searchRepository: SearchRepository) = withContext(Dispatchers.IO) {
        searchRepository.clearIndexForBook(bookId)
        try {
            indexBookContentResumable(
                file = file,
                bookId = bookId,
                searchRepository = searchRepository,
                startChapterIndex = 0
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.printStackTrace()
        }
    }

    /**
     * Đánh chỉ mục từ một chương cụ thể để tiếp tục sau khi Worker bị gián đoạn.
     *
     * @param file Tệp EPUB cần đánh chỉ mục.
     * @param bookId Mã định danh của cuốn sách.
     * @param searchRepository Repository tìm kiếm dữ liệu.
     * @param startChapterIndex Chỉ số chương bắt đầu, các chương trước đó được xem là đã hoàn tất.
     * @param onChapterIndexed Callback sau mỗi batch, nhận chỉ số chương cuối đã xử lý.
     */
    suspend fun indexBookContentResumable(
        file: File,
        bookId: String,
        searchRepository: SearchRepository,
        startChapterIndex: Int = 0,
        onChapterIndexed: suspend (lastChapterIndex: Int) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (file.length() > EpubReadLimits.MAX_EPUB_FILE_SIZE) {
            throw IllegalArgumentException("File EPUB vượt quá kích thước tối đa cho phép (${EpubReadLimits.MAX_EPUB_FILE_SIZE / (1024 * 1024)}MB)")
        }
        ZipFile(file).use { zip ->
            val parsed = EpubPackageStructureParser.parseStructure(zip)
            val indexedChapters = mutableListOf<Pair<Int, Pair<String, String>>>()
            var lastProcessedIndex = startChapterIndex - 1
            var totalExtractedChars = 0L

            parsed.orderedEntries.forEachIndexed { index, entry ->
                if (index < startChapterIndex) return@forEachIndexed
                try {
                    EpubReadLimits.validateZipEntry(entry)
                    val remainingBudget = EpubReadLimits.MAX_TOTAL_EXTRACTED_BYTES - totalExtractedChars
                    if (remainingBudget <= 0L) {
                        throw TotalExtractedBudgetExceededException(
                            "Tổng dung lượng giải nén vượt quá giới hạn an toàn (${EpubReadLimits.MAX_TOTAL_EXTRACTED_BYTES} bytes)"
                        )
                    }
                    val maxEntryBytes = minOf(EpubReadLimits.MAX_UNCOMPRESSED_ENTRY_SIZE, remainingBudget)
                    val rawHtml = zip.getInputStream(entry).use { it.readBoundedText(maxBytes = maxEntryBytes) }
                    totalExtractedChars += rawHtml.length.toLong()
                    if (totalExtractedChars > EpubReadLimits.MAX_TOTAL_EXTRACTED_BYTES) {
                        throw TotalExtractedBudgetExceededException(
                            "Tổng dung lượng giải nén vượt quá giới hạn an toàn (${EpubReadLimits.MAX_TOTAL_EXTRACTED_BYTES} bytes)"
                        )
                    }
                    val plainText = stripHtmlTags(rawHtml)
                    val title = parsed.chapterTitles[entry.name]
                        ?: extractXmlTag(rawHtml, "title")
                        ?: extractXmlTag(rawHtml, "h1")
                        ?: "Chương " + (index + 1)
                    indexedChapters.add(index to (title to plainText))
                    lastProcessedIndex = index

                    if (indexedChapters.size >= 5) {
                        searchRepository.indexBookContent(bookId, indexedChapters.toList())
                        indexedChapters.clear()
                        onChapterIndexed(lastProcessedIndex)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: TotalExtractedBudgetExceededException) {
                    throw error
                } catch (_: Exception) {
                    lastProcessedIndex = index
                }
            }

            if (indexedChapters.isNotEmpty()) {
                searchRepository.indexBookContent(bookId, indexedChapters.toList())
                onChapterIndexed(lastProcessedIndex)
            }
        }
    }

    private fun extractXmlTag(xml: String, tagName: String): String? {
        val regex = "(?i)<$tagName[^>]*>(.*?)</$tagName>".toRegex(RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.get(1)?.let { stripHtmlTags(it) }?.takeIf { it.isNotBlank() }
    }

    private fun stripHtmlTags(html: String): String {
        return Jsoup.parse(html).apply { select("script, style").remove() }.text()
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun sanitizeChapterTitle(rawTitle: String): String {
        if (rawTitle.isBlank()) return rawTitle

        var title = rawTitle.trim()

        // 1. Remove software watermarks / author generator tags
        title = title.replace("(?i)\\s*[-|–—]\\s*(Created|Written|Converted|Generated)(\\s+with|\\s+by).*$".toRegex(), "")
        title = title.replace("(?i)\\s*(Created|Written|Converted|Generated)(\\s+with|\\s+by).*$".toRegex(), "")
        title = title.replace("(?i)\\s*[-|–—]\\s*(truyenfull|metruyenchu|wikidich|tangthuvien|vbooks|mkbyme).*$".toRegex(), "")

        // 2. Remove appended Book Title / Series Title in parentheses or suffix after dash
        val parts = title.split(" - ")
        if (parts.size >= 2) {
            val lastPart = parts.last().trim()
            if (lastPart.contains("(") && lastPart.contains(")")) {
                title = parts.dropLast(1).joinToString(" - ").trim()
            }
        }

        return title.trim().removeSuffix("-").removeSuffix("|").trim()
    }

    private class TotalExtractedBudgetExceededException(message: String) : IllegalStateException(message)
}
