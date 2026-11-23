package com.epubpro.core.reader.engine

import androidx.collection.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bộ nhớ đệm RAM giới hạn theo dung lượng byte (Byte-Budget Memory Cache) cho nội dung HTML các chương sách EPUB đã được chuẩn hóa.
 *
 * Sử dụng [LruCache] với hạn mức bộ nhớ byte (mặc định 4 MiB) và cơ chế chống nạp trùng lặp đồng thời (single-flight loader)
 * để tránh việc Reader UI và TTS Service cùng giải nén và phân tích lại một entry EPUB giống nhau tại một thời điểm.
 */
@Singleton
class EpubChapterMemoryCache @Inject constructor() {

    private val singleFlightLocks = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val cacheGeneration = AtomicLong(0L)

    /**
     * LRU cache lưu trữ HTML đã chuẩn hóa theo key chuỗi định danh.
     * Dung lượng được tính theo số byte xấp xỉ của chuỗi (String.length * 2).
     */
    private val lruCache = object : LruCache<String, String>(DEFAULT_MAX_BYTES) {
        override fun sizeOf(key: String, value: String): Int {
            return value.length * 2
        }
    }

    private val lock = Any()

    /**
     * Lấy nội dung HTML đã chuẩn hóa từ RAM cache nếu tồn tại.
     *
     * @param fingerprint Dấu vân tay định danh của tệp EPUB.
     * @param entryName Tên entry tệp chương trong tệp zip.
     * @param normalizerVersion Phiên bản của bộ chuẩn hóa HTML.
     * @return Chuỗi HTML đã chuẩn hóa nếu trúng cache (hit), ngược lại trả về `null`.
     */
    fun get(fingerprint: EpubCacheFingerprint, entryName: String, normalizerVersion: Int = CURRENT_NORMALIZER_VERSION): String? {
        val cacheKey = buildCacheKey(fingerprint, entryName, normalizerVersion)
        synchronized(lock) {
            return lruCache.get(cacheKey)
        }
    }

    /**
     * Lưu trữ nội dung HTML đã chuẩn hóa vào RAM cache.
     *
     * @param fingerprint Dấu vân tay định danh của tệp EPUB.
     * @param entryName Tên entry tệp chương trong tệp zip.
     * @param html Chuỗi HTML đã chuẩn hóa.
     * @param normalizerVersion Phiên bản của bộ chuẩn hóa HTML.
     */
    fun put(fingerprint: EpubCacheFingerprint, entryName: String, html: String, normalizerVersion: Int = CURRENT_NORMALIZER_VERSION) {
        val cacheKey = buildCacheKey(fingerprint, entryName, normalizerVersion)
        synchronized(lock) {
            lruCache.put(cacheKey, html)
        }
    }

    /**
     * Thực thi nạp dữ liệu chương với cơ chế Single-Flight: nếu nhiều luồng cùng yêu cầu một chương,
     * chỉ một luồng thực hiện đọc/chuẩn hóa dữ liệu thật sự, các luồng khác sẽ đợi và nhận kết quả từ cache.
     *
     * @param fingerprint Dấu vân tay định danh của tệp EPUB.
     * @param entryName Tên entry tệp chương.
     * @param loader Lambda thực thi đọc và chuẩn hóa dữ liệu từ đĩa nếu cache miss.
     * @return Chuỗi HTML chương đã chuẩn hóa.
     */
    suspend fun getOrLoad(
        fingerprint: EpubCacheFingerprint,
        entryName: String,
        normalizerVersion: Int = CURRENT_NORMALIZER_VERSION,
        loader: suspend () -> String
    ): String {
        val cacheKey = buildCacheKey(fingerprint, entryName, normalizerVersion)

        while (true) {
            currentCoroutineContext().ensureActive()

            // Kiểm tra nhanh RAM cache
            val startGen: Long
            synchronized(lock) {
                lruCache.get(cacheKey)?.let { return it }
                startGen = cacheGeneration.get()
            }

            val newDeferred = CompletableDeferred<String>()
            val existingDeferred = singleFlightLocks.putIfAbsent(cacheKey, newDeferred)
            if (existingDeferred != null) {
                try {
                    return existingDeferred.await()
                } catch (cancelled: CancellationException) {
                    // Nếu coroutine đang đợi không bị hủy (mà chỉ là coroutine nạp trước bị hủy), nhường nhịp để finally xóa key rồi thử lại.
                    currentCoroutineContext().ensureActive()
                    yield()
                    continue
                }
            }

            return try {
                synchronized(lock) {
                    lruCache.get(cacheKey)?.let { cached ->
                        newDeferred.complete(cached)
                        return cached
                    }
                }

                val loadedHtml = loader()
                synchronized(lock) {
                    if (cacheGeneration.get() == startGen) {
                        lruCache.put(cacheKey, loadedHtml)
                    }
                }
                newDeferred.complete(loadedHtml)
                loadedHtml
            } catch (t: Throwable) {
                newDeferred.completeExceptionally(t)
                throw t
            } finally {
                singleFlightLocks.remove(cacheKey, newDeferred)
            }
        }
    }

    /**
     * Xóa toàn bộ các chương của một cuốn sách khỏi RAM cache khi sách bị xóa hoặc file bị sửa đổi.
     *
     * @param canonicalPath Đường dẫn chuẩn hóa của tệp sách cần dọn dẹp.
     */
    fun evictBook(canonicalPath: String) {
        synchronized(lock) {
            cacheGeneration.incrementAndGet()
            val prefix = canonicalPath + CACHE_KEY_SEPARATOR
            val snapshot = lruCache.snapshot()
            for (key in snapshot.keys) {
                if (key.startsWith(prefix)) {
                    lruCache.remove(key)
                }
            }
        }
    }

    /**
     * Xóa sạch toàn bộ RAM cache chương sách.
     */
    fun clear() {
        synchronized(lock) {
            cacheGeneration.incrementAndGet()
            lruCache.evictAll()
        }
        singleFlightLocks.clear()
    }

    private fun buildCacheKey(fingerprint: EpubCacheFingerprint, entryName: String, normalizerVersion: Int): String {
        return "${fingerprint.canonicalPath}$CACHE_KEY_SEPARATOR${fingerprint.fileLength}$CACHE_KEY_SEPARATOR${fingerprint.lastModified}$CACHE_KEY_SEPARATOR$entryName$CACHE_KEY_SEPARATOR$normalizerVersion"
    }

    companion object {
        /** Dung lượng RAM tối đa mặc định cho cache HTML chương: 4 MiB. */
        const val DEFAULT_MAX_BYTES = 4 * 1024 * 1024

        /** Phiên bản logic chuẩn hóa HTML. */
        const val CURRENT_NORMALIZER_VERSION = 1

        private const val CACHE_KEY_SEPARATOR = "::"
    }
}
