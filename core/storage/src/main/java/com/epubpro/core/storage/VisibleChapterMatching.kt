package com.epubpro.core.storage

import com.epubpro.core.webnovel.WebArticle
import com.epubpro.core.webnovel.WebNovelChapter

/**
 * Xác định chương đã lưu bằng URL, nội dung EPUB cũ hoặc mốc nguồn đã biết.
 *
 * @param article Chương đang xem trên web.
 * @param source Nguồn web của sách.
 * @param chapters Các chương đã có theo thứ tự.
 * @return Chỉ số chương trùng hoặc -1 khi không đủ bằng chứng.
 */
internal fun findVisibleChapterIndex(article: WebArticle, source: WebNovelSource, chapters: List<WebNovelChapter>): Int {
    if (chapters.isEmpty()) return -1
    val url = article.url.substringBefore('#')
    val match = chapters.indexOfFirst { chapter ->
        chapter.sourceUrl?.substringBefore('#') == url ||
            (chapter.sourceUrl == null && chapter.paragraphs == article.paragraphs)
    }
    return when {
        match >= 0 -> match
        source.startUrl.substringBefore('#') == url -> 0
        source.lastChapterUrl.substringBefore('#') == url -> chapters.lastIndex
        else -> -1
    }
}
