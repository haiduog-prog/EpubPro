package com.epubpro.core.reader.engine

import com.epubpro.core.reader.engine.EpubReadLimits.readBoundedText
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Kết quả phân tích cấu trúc gói sách EPUB.
 *
 * @property title Tiêu đề sách đọc từ metadata.
 * @property author Tác giả sách đọc từ metadata.
 * @property orderedEntries Danh sách các [ZipEntry] nội dung chương theo đúng thứ tự đọc (spine).
 * @property chapterTitles Bản đồ ánh xạ từ tên tệp entry chuẩn hóa sang tiêu đề chương trích xuất từ TOC.
 * @property coverEntry Entry ảnh bìa được xác định từ manifest hoặc tên file fallback.
 */
data class ParsedEpubStructure(
    val title: String,
    val author: String,
    val orderedEntries: List<ZipEntry>,
    val chapterTitles: Map<String, String>,
    val coverEntry: ZipEntry? = null
)

/**
 * Bộ phân tích cấu trúc gói tệp EPUB (Package Structure Parser).
 *
 * Chịu trách nhiệm phân tích `META-INF/container.xml`, file `.opf` (Package Document),
 * thứ tự đọc `<spine>`, danh mục `<manifest>`, và trích xuất tiêu đề chương theo chuẩn
 * **TOC First**:
 * 1. EPUB 3 Navigation Document (`nav.xhtml` chứa `<nav epub:type="toc">`).
 * 2. EPUB 2 NCX (`toc.ncx` chứa `<navMap><navPoint>`).
 * 3. Fallback phân tích mẫu 8 KiB cho các entry chưa có tiêu đề trong mục lục.
 */
object EpubPackageStructureParser {

    /**
     * Đọc ID xuất bản được unique-identifier của OPF tham chiếu, không suy đoán từ tên truyện.
     *
     * @param zip Gói EPUB đang mở.
     * @return ID không rỗng, hoặc null nếu không có tham chiếu hợp lệ.
     * @throws java.io.IOException Nếu không đọc được dữ liệu ZIP.
     */
    fun readPublicationIdentifier(zip: ZipFile): String? {
        val entries = zip.entries().toList()
        val opf = findOpfEntry(zip, entries, entries.associateBy { it.name }) ?: return null
        EpubReadLimits.validateZipEntry(opf)
        val content = zip.getInputStream(opf).use { it.readBoundedText() }
        val document = Jsoup.parse(content, "", Parser.xmlParser())
        val packageElement = document.getAllElements().firstOrNull {
            it.tagName().substringAfter(':') == "package"
        } ?: return null
        val reference = packageElement.attr("unique-identifier").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val metadata = packageElement.children().firstOrNull {
            it.tagName().substringAfter(':') == "metadata"
        } ?: return null
        return metadata.children().firstOrNull {
            it.tagName().substringAfter(':') == "identifier" && it.attr("id") == reference
        }?.text()?.trim()?.takeIf { it.isNotEmpty() }
    }


    /**
     * Phân tích toàn diện cấu trúc gói EPUB từ tệp [ZipFile].
     *
     * @param zip Đối tượng [ZipFile] đang mở của tệp sách.
     * @return Đối tượng [ParsedEpubStructure] chứa metadata, danh sách entry theo spine và tiêu đề TOC.
     */
    fun parseStructure(zip: ZipFile): ParsedEpubStructure {
        val allEntries = zip.entries().toList()
        val entryMap = allEntries.associateBy { it.name }

        var bookTitle = "Unknown Title"
        var bookAuthor = "Unknown Author"

        val opfEntry = findOpfEntry(zip, allEntries, entryMap)
        if (opfEntry == null) {
            // Fallback khi không tìm thấy OPF: xếp theo natural numeric comparator
            val htmlEntries = allEntries.filter { isHtmlEntry(it.name) }
            val comparator = naturalOrderComparator()
            val sorted = htmlEntries.sortedWith(Comparator { e1, e2 -> comparator.compare(e1.name, e2.name) })
            val fallbackCover = allEntries.find { entry ->
                val lower = entry.name.lowercase()
                (lower.contains("cover") || lower.contains("frontcover") || lower.contains("titlepage")) &&
                    (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp"))
            }
            return ParsedEpubStructure(
                title = bookTitle,
                author = bookAuthor,
                orderedEntries = sorted,
                chapterTitles = emptyMap(),
                coverEntry = fallbackCover
            )
        }

        try {
            EpubReadLimits.validateZipEntry(opfEntry)
            val opfContent = zip.getInputStream(opfEntry).use { it.readBoundedText() }
            val opfDir = opfEntry.name.substringBeforeLast('/', "")
            val dirPrefix = if (opfDir.isNotEmpty()) "$opfDir/" else ""

            val opfDoc = Jsoup.parse(opfContent, "", Parser.xmlParser())

            // 1. Trích xuất metadata sách
            val dcTitle = opfDoc.select("dc|title, title").text().trim()
            val dcCreator = opfDoc.select("dc|creator, creator").text().trim()
            if (dcTitle.isNotBlank()) bookTitle = dcTitle
            if (dcCreator.isNotBlank()) bookAuthor = dcCreator

            // 2. Trích xuất Manifest
            data class ManifestItem(
                val id: String,
                val href: String,
                val mediaType: String,
                val properties: String
            )

            val manifestMap = mutableMapOf<String, ManifestItem>()
            var navHref: String? = null
            var ncxHref: String? = null

            val spineTocId = opfDoc.selectFirst("spine")?.attr("toc")?.trim()

            opfDoc.select("manifest > item").forEach { item ->
                val id = item.attr("id").trim()
                val href = item.attr("href").trim()
                val mediaType = item.attr("media-type").trim()
                val properties = item.attr("properties").trim()

                if (id.isNotEmpty() && href.isNotEmpty()) {
                    manifestMap[id] = ManifestItem(id, href, mediaType, properties)

                    if (properties.contains("nav", ignoreCase = true)) {
                        navHref = href
                    }
                    if (mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) || id.equals("ncx", ignoreCase = true)) {
                        ncxHref = href
                    }
                }
            }

            if (ncxHref == null && !spineTocId.isNullOrEmpty()) {
                ncxHref = manifestMap[spineTocId]?.href
            }

            // 3. Trích xuất Spine theo đúng thứ tự đọc của tác giả
            val spineIds = mutableListOf<String>()
            opfDoc.select("spine > itemref").forEach { itemref ->
                val idref = itemref.attr("idref").trim()
                if (idref.isNotEmpty()) {
                    spineIds.add(idref)
                }
            }

            val orderedEntries = mutableListOf<ZipEntry>()
            for (id in spineIds) {
                val item = manifestMap[id] ?: continue
                val decodedHref = safeDecodeHref(item.href)
                val fullPath = resolveRelativePath(dirPrefix, decodedHref)

                val entry = entryMap[fullPath]
                    ?: entryMap[decodedHref]
                    ?: allEntries.find {
                        it.name.equals(fullPath, ignoreCase = true) ||
                            it.name.equals(decodedHref, ignoreCase = true) ||
                            it.name.endsWith("/$decodedHref", ignoreCase = true)
                    }

                if (entry != null && isHtmlEntry(entry.name) && !orderedEntries.contains(entry)) {
                    orderedEntries.add(entry)
                }
            }

            // 4. Trích xuất TOC titles (Ưu tiên EPUB 3 Navigation Document -> EPUB 2 NCX)
            val chapterTitles = mutableMapOf<String, String>()

            if (!navHref.isNullOrEmpty()) {
                val decodedNav = safeDecodeHref(navHref!!)
                val navFullPath = resolveRelativePath(dirPrefix, decodedNav)
                val navEntry = entryMap[navFullPath]
                    ?: entryMap[decodedNav]
                    ?: allEntries.find {
                        it.name.equals(navFullPath, ignoreCase = true) ||
                            it.name.equals(decodedNav, ignoreCase = true) ||
                            it.name.endsWith("/$decodedNav", ignoreCase = true)
                    }

                if (navEntry != null) {
                    parseEpub3NavDocument(zip, navEntry, navFullPath.substringBeforeLast('/', ""), chapterTitles)
                }
            }

            if (chapterTitles.isEmpty() && !ncxHref.isNullOrEmpty()) {
                val decodedNcx = safeDecodeHref(ncxHref!!)
                val ncxFullPath = resolveRelativePath(dirPrefix, decodedNcx)
                val ncxEntry = entryMap[ncxFullPath]
                    ?: entryMap[decodedNcx]
                    ?: allEntries.find {
                        it.name.equals(ncxFullPath, ignoreCase = true) ||
                            it.name.equals(decodedNcx, ignoreCase = true) ||
                            it.name.endsWith("/$decodedNcx", ignoreCase = true)
                    }

                if (ncxEntry != null) {
                    parseEpub2NcxDocument(zip, ncxEntry, ncxFullPath.substringBeforeLast('/', ""), chapterTitles)
                }
            }

            // 5. Trích xuất Cover Image (EPUB 3 properties="cover-image" -> EPUB 2 meta name="cover" -> Manifest id/href -> Filename Fallback)
            var coverHref: String? = null

            for ((_, item) in manifestMap) {
                if (item.properties.contains("cover-image", ignoreCase = true) &&
                    item.mediaType.startsWith("image/", ignoreCase = true)
                ) {
                    coverHref = item.href
                    break
                }
            }

            if (coverHref == null) {
                val metaCoverId = opfDoc.select("metadata > meta[name=cover]").attr("content").trim()
                if (metaCoverId.isNotEmpty()) {
                    coverHref = manifestMap[metaCoverId]?.href
                }
            }

            if (coverHref == null) {
                val coverItem = manifestMap.values.find { item ->
                    item.mediaType.startsWith("image/", ignoreCase = true) &&
                        (item.id.equals("cover", ignoreCase = true) ||
                            item.id.equals("cover-image", ignoreCase = true) ||
                            item.id.contains("cover", ignoreCase = true) ||
                            item.href.contains("cover", ignoreCase = true))
                }
                coverHref = coverItem?.href
            }

            var coverEntry: ZipEntry? = null
            if (!coverHref.isNullOrEmpty()) {
                val decodedCover = safeDecodeHref(coverHref)
                val coverFullPath = resolveRelativePath(dirPrefix, decodedCover)
                coverEntry = entryMap[coverFullPath]
                    ?: entryMap[decodedCover]
                    ?: allEntries.find {
                        it.name.equals(coverFullPath, ignoreCase = true) ||
                            it.name.equals(decodedCover, ignoreCase = true) ||
                            it.name.endsWith("/$decodedCover", ignoreCase = true)
                    }
            }

            if (coverEntry == null) {
                coverEntry = allEntries.find { entry ->
                    val lower = entry.name.lowercase()
                    (lower.contains("cover") || lower.contains("frontcover") || lower.contains("titlepage")) &&
                        (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp"))
                }
            }

            val finalEntries = if (orderedEntries.isNotEmpty()) {
                orderedEntries
            } else {
                val htmlEntries = allEntries.filter { isHtmlEntry(it.name) }
                val comparator = naturalOrderComparator()
                htmlEntries.sortedWith(Comparator { e1, e2 -> comparator.compare(e1.name, e2.name) })
            }

            return ParsedEpubStructure(
                title = bookTitle,
                author = bookAuthor,
                orderedEntries = finalEntries,
                chapterTitles = chapterTitles,
                coverEntry = coverEntry
            )
        } catch (e: Exception) {
            e.printStackTrace()
            val htmlEntries = allEntries.filter { isHtmlEntry(it.name) }
            val comparator = naturalOrderComparator()
            val sorted = htmlEntries.sortedWith(Comparator { e1, e2 -> comparator.compare(e1.name, e2.name) })
            val fallbackCover = allEntries.find { entry ->
                val lower = entry.name.lowercase()
                    (lower.contains("cover") || lower.contains("frontcover") || lower.contains("titlepage")) &&
                    (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp"))
            }
            return ParsedEpubStructure(
                title = bookTitle,
                author = bookAuthor,
                orderedEntries = sorted,
                chapterTitles = emptyMap(),
                coverEntry = fallbackCover
            )
        }
    }

    private fun findOpfEntry(zip: ZipFile, allEntries: List<ZipEntry>, entryMap: Map<String, ZipEntry>): ZipEntry? {
        val containerEntry = entryMap["META-INF/container.xml"]
            ?: allEntries.find { it.name.equals("META-INF/container.xml", ignoreCase = true) }

        if (containerEntry != null) {
            try {
                EpubReadLimits.validateZipEntry(containerEntry)
                val containerXml = zip.getInputStream(containerEntry).use { it.readBoundedText() }
                val doc = Jsoup.parse(containerXml, "", Parser.xmlParser())
                val rootfile = doc.select("rootfile").firstOrNull()
                val fullPath = rootfile?.attr("full-path")?.trim()
                if (!fullPath.isNullOrBlank()) {
                    val decodedPath = safeDecodeHref(fullPath)
                    val opf = entryMap[decodedPath]
                        ?: entryMap[fullPath]
                        ?: allEntries.find { it.name.equals(decodedPath, ignoreCase = true) || it.name.equals(fullPath, ignoreCase = true) }
                    if (opf != null) return opf
                }
            } catch (e: Exception) {
                // Fallback scan
            }
        }

        return allEntries.find { it.name.endsWith(".opf", ignoreCase = true) }
    }

    private fun parseEpub3NavDocument(
        zip: ZipFile,
        navEntry: ZipEntry,
        baseDir: String,
        outMap: MutableMap<String, String>
    ) {
        try {
            EpubReadLimits.validateZipEntry(navEntry)
            val content = zip.getInputStream(navEntry).use { it.readBoundedText() }
            val doc = Jsoup.parse(content)

            // Tìm <nav epub:type="toc"> hoặc <nav id="toc"> hoặc thẻ <nav> đầu tiên
            val navElement = doc.selectFirst("nav[*|type=toc], nav[type=toc], nav#toc, nav") ?: return
            val links = navElement.select("a[href]")

            for (link in links) {
                val rawHref = link.attr("href").trim()
                if (rawHref.isBlank()) continue
                val decodedHref = safeDecodeHref(rawHref)
                val targetPath = resolveRelativePath(baseDir, decodedHref)
                val title = link.text().trim()

                if (title.isNotBlank() && !outMap.containsKey(targetPath)) {
                    outMap[targetPath] = title
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseEpub2NcxDocument(
        zip: ZipFile,
        ncxEntry: ZipEntry,
        baseDir: String,
        outMap: MutableMap<String, String>
    ) {
        try {
            EpubReadLimits.validateZipEntry(ncxEntry)
            val content = zip.getInputStream(ncxEntry).use { it.readBoundedText() }
            val doc = Jsoup.parse(content, "", Parser.xmlParser())

            val navPoints = doc.select("navMap navPoint")
            for (point in navPoints) {
                val text = point.selectFirst("navLabel > text")?.text()?.trim().orEmpty()
                val src = point.selectFirst("content")?.attr("src")?.trim().orEmpty()
                if (src.isBlank()) continue

                val decodedSrc = safeDecodeHref(src)
                val targetPath = resolveRelativePath(baseDir, decodedSrc)

                if (text.isNotBlank() && !outMap.containsKey(targetPath)) {
                    outMap[targetPath] = text
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Giải mã an toàn chuỗi đường dẫn `href` hoặc `src` trong EPUB:
     * loại bỏ fragment (`#`) và query (`?`), bảo toàn ký tự `+` không bị đổi thành khoảng trắng,
     * và fallback về chuỗi gốc nếu chứa chuỗi `%` không hợp lệ.
     *
     * @param raw Chuỗi đường dẫn thô từ thuộc tính XML/HTML.
     * @return Chuỗi đường dẫn đã được giải mã URL an toàn.
     */
    private fun safeDecodeHref(raw: String): String {
        val clean = raw.substringBefore('#').substringBefore('?')
        return runCatching {
            URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(clean)
    }

    /**
     * Chuẩn hóa đường dẫn tương đối kết hợp với thư mục cơ sở (base directory).
     *
     * @param baseDir Đường dẫn thư mục cha (ví dụ `OEBPS` hoặc `OEBPS/Text`).
     * @param relativePath Đường dẫn tương đối từ tài liệu TOC (ví dụ `../Text/chap1.xhtml` hoặc `chap1.xhtml`).
     * @return Đường dẫn chuẩn hóa hoàn chỉnh bên trong file Zip.
     */
    fun resolveRelativePath(baseDir: String, relativePath: String): String {
        val cleanRelative = relativePath.trim().replace('\\', '/')
        if (cleanRelative.startsWith("/")) return normalizePathSegments(cleanRelative.removePrefix("/"))
        if (baseDir.isBlank()) return normalizePathSegments(cleanRelative)

        val combined = "${baseDir.trimEnd('/')}/$cleanRelative"
        return normalizePathSegments(combined)
    }

    private fun normalizePathSegments(path: String): String {
        val segments = path.split('/')
        val result = mutableListOf<String>()
        for (seg in segments) {
            when (seg) {
                "", "." -> continue
                ".." -> if (result.isNotEmpty()) result.removeAt(result.size - 1)
                else -> result.add(seg)
            }
        }
        return result.joinToString("/")
    }

    private fun isHtmlEntry(name: String): Boolean {
        return name.endsWith(".xhtml", ignoreCase = true) ||
            name.endsWith(".html", ignoreCase = true) ||
            name.endsWith(".htm", ignoreCase = true)
    }

    private fun naturalOrderComparator(): Comparator<String> {
        val regex = Regex("(\\d+)|(\\D+)")
        return Comparator { s1, s2 ->
            val m1 = regex.findAll(s1).map { it.value }.toList()
            val m2 = regex.findAll(s2).map { it.value }.toList()
            for (i in 0 until minOf(m1.size, m2.size)) {
                val token1 = m1[i]
                val token2 = m2[i]
                val isNum1 = token1.isNotEmpty() && token1.all { it.isDigit() }
                val isNum2 = token2.isNotEmpty() && token2.all { it.isDigit() }
                if (isNum1 && isNum2) {
                    val norm1 = token1.trimStart('0').ifEmpty { "0" }
                    val norm2 = token2.trimStart('0').ifEmpty { "0" }
                    val lenCmp = norm1.length.compareTo(norm2.length)
                    if (lenCmp != 0) return@Comparator lenCmp
                    val valCmp = norm1.compareTo(norm2)
                    if (valCmp != 0) return@Comparator valCmp
                } else {
                    val res = token1.compareTo(token2, ignoreCase = true)
                    if (res != 0) return@Comparator res
                }
            }
            m1.size.compareTo(m2.size)
        }
    }
}
