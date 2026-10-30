package com.epubpro.core.comic

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parser HTML chương truyện tranh từ NetTruyen và các trang cùng cấu trúc.
 *
 * Trích xuất danh sách ảnh trang, link điều hướng, tiêu đề và danh sách server ảnh.
 * Mọi lỗi parse nội bộ đều được bắt; chỉ ném [ComicChapterParseException] khi không
 * tìm được ảnh nào sau khi lọc.
 */
object NetTruyenChapterParser {

    // ---- Selector ưu tiên để tìm container chương ----
    private val CHAPTER_CONTAINER_SELECTORS = listOf(
        "div.reading-detail",
        "div.page-chapter",
        "div#chapter-content",
        "div[class*=\"chapter\"]"
    )

    // ---- Extension ảnh hợp lệ ----
    private val VALID_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

    // ---- Segment path đặc trưng ảnh trang truyện ----
    private val PAGE_PATH_SEGMENTS = listOf("/images/", "/manga/", "/truyen-tranh/", "/page/")

    // ---- Từ khoá nhận biết ảnh giao diện cần lọc ----
    private val UI_IMAGE_KEYWORDS = listOf("logo", "icon", "avatar", "banner", "sprite", "notice")

    // ---- Regex nhận biết quảng cáo theo từ nguyên (tránh trùng từ con như lozad, header) ----
    private val AD_WORD_REGEX = Regex("(^|[^a-z0-9])(ad|ads|advertisement)([^a-z0-9]|$)", RegexOption.IGNORE_CASE)

    // ---- Pattern text / rel nhận biết link "Chương sau" ----
    private val NEXT_TEXT_PATTERNS = listOf(
        "chương sau", "chuong sau", "tiếp theo", "tiep theo", "next"
    )
    private val NEXT_SYMBOLS = setOf(">", "»")

    // ---- Pattern text / rel nhận biết link "Chương trước" ----
    private val PREV_TEXT_PATTERNS = listOf(
        "chương trước", "chuong truoc", "previous", "prev"
    )
    private val PREV_SYMBOLS = setOf("<", "«")

    /**
     * Phân tích HTML của một chương truyện tranh và trả về [ComicChapterInfo].
     *
     * @param html Nội dung HTML đầy đủ của trang chương.
     * @param pageUrl URL tuyệt đối của trang (dùng để resolve URL tương đối và lọc host).
     * @return Thông tin chương đã phân tích.
     * @throws ComicChapterParseException Nếu không tìm được ảnh nào sau khi lọc.
     */
    fun parse(html: String, pageUrl: String): ComicChapterInfo {
        val document = Jsoup.parse(html, pageUrl)
        val pageUri = runCatching { URI(pageUrl) }.getOrNull()
        val pageHost = pageUri?.host?.lowercase().orEmpty()

        val servers = extractServers(document)
        val (pages, pagesByServer) = extractPages(document, pageUrl, pageHost, servers.map { it.id })
        if (pages.isEmpty()) throw ComicChapterParseException("Không tìm thấy ảnh trong chương")

        val (prevUrl, nextUrl) = extractNavLinks(document, pageUrl, pageHost)
        val title = extractTitle(document, pageUrl)
        val comicTitle = extractComicTitle(document, title)
        val coverUrl = extractCoverUrl(document, pages)
        val sourceHost = runCatching { URI(pageUrl).host }.getOrElse { pageHost }

        return ComicChapterInfo(
            sourceHost = sourceHost,
            chapterUrl = pageUrl,
            title = title,
            pages = pages,
            previousChapterUrl = prevUrl,
            nextChapterUrl = nextUrl,
            availableServers = servers,
            comicTitle = comicTitle,
            coverUrl = coverUrl,
            pagesByServer = pagesByServer
        )
    }

    // ---------------------------------------------------------------------------
    // Trích xuất ảnh trang
    // ---------------------------------------------------------------------------

    /**
     * Tìm container chương theo danh sách selector ưu tiên, fallback về `body`.
     *
     * @param document Tài liệu HTML.
     * @return Element container chứa ảnh trang.
     */
    private fun findChapterContainer(document: Document): Element {
        for (selector in CHAPTER_CONTAINER_SELECTORS) {
            val el = runCatching { document.selectFirst(selector) }.getOrNull()
            if (el != null) return el
        }
        return document.body() ?: document.root()
    }

    /**
     * Trích xuất danh sách URL ảnh trang từ container chương, áp dụng đầy đủ các bộ lọc.
     *
     * @param document Tài liệu HTML.
     * @param pageUrl URL tuyệt đối trang để resolve URL tương đối.
     * @param pageHost Host của trang chính dùng để phân biệt CDN ảnh.
     * @param serverIds Mã các server ảnh được khai báo trên trang.
     * @return Danh sách URL chính và các danh sách thay thế theo server.
     */
    private fun extractPages(
        document: Document,
        pageUrl: String,
        pageHost: String,
        serverIds: List<String>
    ): Pair<List<String>, Map<String, List<String>>> {
        val container = findChapterContainer(document)
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<String>()
        val serverPages = serverIds.associateWith { mutableListOf<String>() }

        container.select("img").forEach { img ->
            // Bước 3: chọn URL theo thứ tự ưu tiên attribute
            val rawUrl = listOf("data-src", "data-lazy-src", "data-original", "src")
                .map { img.attr(it).trim() }
                .firstOrNull { it.isNotEmpty() } ?: return@forEach

            // Bước 4: resolve thành URL tuyệt đối
            val absoluteUrl = resolveUrl(rawUrl, img, pageUrl, listOf("data-src", "data-lazy-src", "data-original", "src")) ?: return@forEach

            // Bước 5: lọc ảnh giao diện nhỏ (width/height ≤ 50) hoặc class/alt chứa từ UI
            if (isUiImage(img)) return@forEach

            // Bước 6: lọc URL không hợp lệ (data URI, non-http)
            if (!isValidHttpUrl(absoluteUrl)) return@forEach

            // Bước 7: lọc URL không phải ảnh trang
            if (!isPageImageUrl(absoluteUrl, pageHost)) return@forEach

            // Bước 9: kiểm tra PublicAddressPolicy (giữ nếu không resolve được)
            val urlHost = runCatching { URI(absoluteUrl).host }.getOrElse { "" }
            if (urlHost.isNotEmpty() && !isPublicHost(urlHost)) return@forEach

            // Bước 8: dedup theo URL tuyệt đối
            if (seen.add(absoluteUrl)) {
                result.add(absoluteUrl)
                serverPages.forEach { (serverId, urls) ->
                    val serverAttribute = "data-sv$serverId"
                    val serverRawUrl = img.attr(serverAttribute).trim()
                    val serverUrl = serverRawUrl.takeIf { it.isNotEmpty() }
                        ?.let { resolveUrl(it, img, pageUrl, listOf(serverAttribute)) }
                        ?.takeIf { isValidHttpUrl(it) && isPageImageUrl(it, pageHost) }
                        ?.takeIf { url ->
                            val host = runCatching { URI(url).host }.getOrNull()
                            host == null || isPublicHost(host)
                        }
                    urls += serverUrl ?: absoluteUrl
                }
            }
        }
        val pagesByServer = serverPages
            .mapValues { it.value.toList() }
            .filterValues { urls -> urls.indices.any { index -> urls[index] != result[index] } }
        return result to pagesByServer
    }

    /**
     * Resolve URL ảnh thành tuyệt đối: dùng absUrl() của Jsoup trước, fallback tự resolve.
     *
     * @param raw Giá trị attribute URL thô.
     * @param img Element `<img>`.
     * @param pageUrl URL trang để fallback.
     * @return URL tuyệt đối hoặc null nếu không resolve được.
     */
    private fun resolveUrl(raw: String, img: Element, pageUrl: String, attributes: List<String>): String? {
        if (raw.startsWith("data:")) return raw  // giữ nguyên, sẽ bị lọc ở bước 6
        // Thử dùng absUrl Jsoup (element đã được parse với base URL)
        // Tìm attribute nào chứa raw value và gọi absUrl
        for (attr in attributes) {
            if (img.attr(attr).trim() == raw) {
                val abs = img.absUrl(attr)
                if (abs.isNotEmpty()) return abs
                break
            }
        }
        // Fallback: tự resolve với pageUrl
        return runCatching {
            val base = URI(pageUrl)
            base.resolve(raw).toString()
        }.getOrNull()
    }

    /**
     * Kiểm tra `<img>` có phải ảnh giao diện (icon, logo, banner nhỏ) không.
     *
     * Bỏ khi width hoặc height attribute/style ≤ 50, hoặc class/alt chứa từ UI.
     *
     * @param img Element ảnh.
     * @return true nếu là ảnh giao diện cần lọc.
     */
    private fun isUiImage(img: Element): Boolean {
        val classAttr = img.className().lowercase()
        val altAttr = img.attr("alt").lowercase()
        if (UI_IMAGE_KEYWORDS.any { it in classAttr || it in altAttr }) return true
        if (AD_WORD_REGEX.containsMatchIn(classAttr) || AD_WORD_REGEX.containsMatchIn(altAttr)) return true

        val widthAttr = img.attr("width").toIntOrNull()
        val heightAttr = img.attr("height").toIntOrNull()
        if (widthAttr != null && widthAttr in 1..50) return true
        if (heightAttr != null && heightAttr in 1..50) return true

        // Kiểm tra style inline: width/height nhỏ
        val style = img.attr("style").lowercase()
        val widthInStyle = Regex("width\\s*:\\s*(\\d+)\\s*px").find(style)?.groupValues?.get(1)?.toIntOrNull()
        val heightInStyle = Regex("height\\s*:\\s*(\\d+)\\s*px").find(style)?.groupValues?.get(1)?.toIntOrNull()
        if (widthInStyle != null && widthInStyle in 1..50) return true
        if (heightInStyle != null && heightInStyle in 1..50) return true

        return false
    }

    /**
     * Kiểm tra URL có phải http/https và không phải data URI.
     *
     * @param url URL cần kiểm tra.
     * @return true nếu là URL http/https hợp lệ.
     */
    private fun isValidHttpUrl(url: String): Boolean {
        if (url.startsWith("data:")) return false
        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull() ?: return false
        return scheme == "http" || scheme == "https"
    }

    /**
     * Kiểm tra URL có phải ảnh trang truyện (theo extension hoặc path segment).
     *
     * Nếu URL không có extension rõ ràng, giữ nếu domain khác domain trang chính (CDN).
     *
     * @param url URL tuyệt đối.
     * @param pageHost Host của trang chính.
     * @return true nếu URL có thể là ảnh trang truyện.
     */
    private fun isPageImageUrl(url: String, pageHost: String): Boolean {
        val path = runCatching { URI(url).path?.lowercase() }.getOrElse { "" }.orEmpty()
        val urlHost = runCatching { URI(url).host?.lowercase() }.getOrElse { "" }.orEmpty()

        val extension = path.substringAfterLast('.', "").substringBefore('?').lowercase()
        if (extension in VALID_IMAGE_EXTENSIONS) return true

        if (PAGE_PATH_SEGMENTS.any { path.contains(it) }) return true

        // Không có extension rõ ràng nhưng domain khác (CDN)
        if (extension.isEmpty() || extension.length > 5) {
            return urlHost.isNotEmpty() && urlHost != pageHost
        }
        return false
    }

    /**
     * Kiểm tra host có phải địa chỉ Internet công khai không.
     *
     * Implement inline logic tương tự PublicAddressPolicy từ core/webnovel.
     * Trả về true nếu không resolve được DNS (tránh false positive khi offline).
     *
     * @param host Tên miền hoặc địa chỉ IP cần kiểm tra.
     * @return true nếu host công khai hoặc không resolve được.
     */
    private fun isPublicHost(host: String): Boolean {
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return true
        return addresses.all { isPublicAddress(it) }
    }

    /**
     * Kiểm tra một [InetAddress] có phải địa chỉ Internet công khai.
     *
     * Chặn loopback, LAN, link-local, CGNAT, multicast và IPv6 ULA.
     *
     * @param address Địa chỉ IP đã phân giải.
     * @return true nếu là địa chỉ công khai.
     */
    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false

        val bytes = address.address
        return when (address) {
            is Inet4Address -> {
                val first = bytes[0].toInt() and 0xFF
                val second = bytes[1].toInt() and 0xFF
                !(first == 100 && second in 64..127) && first != 0
            }
            is Inet6Address -> {
                val first = bytes[0].toInt() and 0xFF
                val ipv4Mapped = bytes.copyOfRange(0, 10).all { it.toInt() == 0 } &&
                    (bytes[10].toInt() and 0xFF) == 0xFF && (bytes[11].toInt() and 0xFF) == 0xFF
                when {
                    first and 0xFE == 0xFC -> false
                    ipv4Mapped -> isPublicAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
                    else -> true
                }
            }
            else -> false
        }
    }

    // ---------------------------------------------------------------------------
    // Trích xuất link điều hướng
    // ---------------------------------------------------------------------------

    /**
     * Trích xuất URL chương trước và chương sau từ document.
     *
     * Ưu tiên selector đặc trưng (class, rel attribute), sau đó tìm bằng text/aria-label.
     * Áp dụng loop detection: URL trùng pageUrl → null.
     *
     * @param document Tài liệu HTML.
     * @param pageUrl URL trang hiện tại.
     * @param pageHost Host trang hiện tại để lọc link ngoài site.
     * @return Pair(previousUrl, nextUrl), mỗi giá trị có thể null.
     */
    private fun extractNavLinks(document: Document, pageUrl: String, pageHost: String): Pair<String?, String?> {
        val pageUriStripped = pageUrl.substringBefore('#')

        fun validateNavUrl(raw: String): String? {
            if (raw.isBlank()) return null
            val abs = runCatching {
                if (raw.startsWith("http://") || raw.startsWith("https://")) raw
                else URI(pageUrl).resolve(raw).toString()
            }.getOrNull() ?: return null
            val scheme = runCatching { URI(abs).scheme?.lowercase() }.getOrNull() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = runCatching { URI(abs).host?.lowercase() }.getOrNull() ?: return null
            if (host != pageHost) return null
            // Loop detection
            val absStripped = abs.substringBefore('#')
            return if (absStripped == pageUriStripped) null else abs
        }

        // Tìm next
        val nextUrl = run {
            val candidates = listOf(
                "a.btn-next", "a[rel=next]", "a#next-chap", "a[class*=\"next\"]"
            )
            var found: String? = null
            for (sel in candidates) {
                val el = runCatching { document.selectFirst(sel) }.getOrNull() ?: continue
                val url = validateNavUrl(el.absUrl("href").ifEmpty { el.attr("href") })
                if (url != null) { found = url; break }
            }
            if (found == null) {
                // Tìm bằng text/aria-label
                found = findNavByText(document, pageUrl, pageHost, pageUriStripped, isNext = true)
            }
            found
        }

        // Tìm prev
        val prevUrl = run {
            val candidates = listOf(
                "a.btn-prev", "a[rel=prev]", "a#prev-chap", "a[class*=\"prev\"]"
            )
            var found: String? = null
            for (sel in candidates) {
                val el = runCatching { document.selectFirst(sel) }.getOrNull() ?: continue
                val url = validateNavUrl(el.absUrl("href").ifEmpty { el.attr("href") })
                if (url != null) { found = url; break }
            }
            if (found == null) {
                found = findNavByText(document, pageUrl, pageHost, pageUriStripped, isNext = false)
            }
            found
        }

        return Pair(prevUrl, nextUrl)
    }

    /**
     * Tìm link điều hướng bằng cách so khớp text/aria-label/title của các thẻ `<a>`.
     *
     * @param document Tài liệu HTML.
     * @param pageUrl URL trang hiện tại.
     * @param pageHost Host trang hiện tại.
     * @param pageUriStripped URL trang không có fragment.
     * @param isNext true để tìm chương sau, false để tìm chương trước.
     * @return URL hợp lệ hoặc null.
     */
    private fun findNavByText(
        document: Document,
        pageUrl: String,
        pageHost: String,
        pageUriStripped: String,
        isNext: Boolean
    ): String? {
        val patterns = if (isNext) NEXT_TEXT_PATTERNS else PREV_TEXT_PATTERNS
        val symbols = if (isNext) NEXT_SYMBOLS else PREV_SYMBOLS

        for (anchor in document.select("a[href]")) {
            val text = normalizeText(
                anchor.text().ifBlank { anchor.attr("aria-label").ifBlank { anchor.attr("title") } }
            )
            val relAttr = anchor.attr("rel").lowercase()
            val matchesRel = if (isNext) "next" in relAttr else "prev" in relAttr

            val matchesText = patterns.any { text.contains(it) }
            val matchesSymbol = text in symbols

            if (!matchesRel && !matchesText && !matchesSymbol) continue

            val rawHref = anchor.absUrl("href").ifEmpty { anchor.attr("href") }
            val abs = runCatching {
                if (rawHref.startsWith("http://") || rawHref.startsWith("https://")) rawHref
                else URI(pageUrl).resolve(rawHref).toString()
            }.getOrNull() ?: continue

            val scheme = runCatching { URI(abs).scheme?.lowercase() }.getOrNull() ?: continue
            if (scheme != "http" && scheme != "https") continue
            val host = runCatching { URI(abs).host?.lowercase() }.getOrNull() ?: continue
            if (host != pageHost) continue
            val absStripped = abs.substringBefore('#')
            if (absStripped == pageUriStripped) continue

            return abs
        }
        return null
    }

    /**
     * Chuẩn hóa text: chữ thường, gộp khoảng trắng, bỏ ký tự không in.
     *
     * @param value Chữ gốc.
     * @return Chữ đã chuẩn hóa.
     */
    private fun normalizeText(value: String): String =
        value.replace('\u00a0', ' ').trim().replace(Regex("\\s+"), " ").lowercase()

    // ---------------------------------------------------------------------------
    // Trích xuất tiêu đề
    // ---------------------------------------------------------------------------

    /**
     * Trích xuất tiêu đề chương từ trang, thử theo thứ tự: `<title>` → `<h1>` → fallback.
     *
     * @param document Tài liệu HTML.
     * @param pageUrl URL trang để tạo fallback.
     * @return Tiêu đề đã trích xuất.
     */
    private fun extractTitle(document: Document, pageUrl: String): String {
        // Thử <title>
        val titleTag = document.title().trim()
        if (titleTag.isNotEmpty()) return titleTag

        // Fallback: <h1> đầu tiên
        val h1 = document.selectFirst("h1")?.text()?.trim()
        if (!h1.isNullOrEmpty()) return h1

        // Fallback cuối: host + path cuối
        return runCatching {
            val uri = URI(pageUrl)
            val lastSegment = uri.path.trimEnd('/').substringAfterLast('/')
            "${uri.host}/$lastSegment"
        }.getOrElse { pageUrl }
    }

    /**
     * Trích xuất tên gốc của bộ truyện (bỏ phần chương), dùng hiển thị trên Kệ sách.
     *
     * Ưu tiên breadcrumbs truyện tranh -> h1 -> làm sạch chuỗi tiêu đề.
     *
     * @param document Tài liệu HTML.
     * @param fallbackTitle Tiêu đề đã trích xuất từ thẻ `<title>`.
     * @return Tên bộ truyện đã làm sạch.
     */
    private fun extractComicTitle(document: Document, fallbackTitle: String): String {
        val breadcrumbComic = document.select("ul.breadcrumb li a[href*=/truyen-tranh/]")
            .map { it.text().trim() }
            .firstOrNull { it.isNotBlank() && !it.contains("Chapter", ignoreCase = true) && !it.contains("Chap", ignoreCase = true) }
        if (!breadcrumbComic.isNullOrBlank()) return breadcrumbComic

        val h1 = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val cleanedH1 = cleanComicTitle(h1)
        if (cleanedH1.isNotBlank()) return cleanedH1

        return cleanComicTitle(fallbackTitle).ifBlank { fallbackTitle }
    }

    /**
     * Làm sạch chuỗi tiêu đề để giữ lại tên gốc của truyện.
     *
     * @param raw Chuỗi tiêu đề ban đầu.
     * @return Chuỗi tên truyện đã làm sạch.
     */
    private fun cleanComicTitle(raw: String): String {
        return raw
            .replace(Regex("(?i)\\s*-\\s*nettruyen.*"), "")
            .replace(Regex("(?i)\\s+(chap|chapter|chương)\\s+\\d+.*"), "")
            .trim()
    }

    /**
     * Trích xuất ảnh bìa của bộ truyện tranh.
     *
     * Ưu tiên meta og:image -> ảnh trang đầu tiên.
     *
     * @param document Tài liệu HTML.
     * @param pages Danh sách trang ảnh đã trích xuất.
     * @return URL ảnh bìa hoặc null.
     */
    private fun extractCoverUrl(document: Document, pages: List<String>): String? {
        val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        if (!ogImage.isNullOrBlank() && isValidHttpUrl(ogImage)) {
            return ogImage
        }
        return pages.firstOrNull()
    }

    // ---------------------------------------------------------------------------
    // Trích xuất servers
    // ---------------------------------------------------------------------------

    /**
     * Trích xuất danh sách server ảnh từ `<select>` hoặc `div[data-server]`.
     *
     * @param document Tài liệu HTML.
     * @return Danh sách [ComicImageServer] hoặc rỗng.
     */
    private fun extractServers(document: Document): List<ComicImageServer> {
        // Thử select#ImageList hoặc select[class*=server]
        val selectEl = runCatching {
            document.selectFirst("select#ImageList")
                ?: document.selectFirst("select[class*=\"server\"]")
        }.getOrNull()

        if (selectEl != null) {
            val servers = selectEl.select("option").mapIndexed { index, option ->
                val id = option.attr("value").ifEmpty { index.toString() }
                val label = option.text().trim()
                ComicImageServer(id = id, label = label)
            }
            if (servers.isNotEmpty()) return servers
        }

        // Thử div[data-server]
        val serverDivs = document.select("div[data-server]")
        if (serverDivs.isNotEmpty()) {
            return serverDivs.mapIndexed { index, div ->
                val id = div.attr("data-server").ifEmpty { index.toString() }
                val label = div.text().trim()
                ComicImageServer(id = id, label = label)
            }
        }

        return emptyList()
    }
}
