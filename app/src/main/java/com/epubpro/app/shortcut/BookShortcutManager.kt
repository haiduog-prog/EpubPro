package com.epubpro.app.shortcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.epubpro.core.designsystem.cover.GeneratedCoverPalette
import com.epubpro.domain.model.Book
import com.epubpro.domain.repository.BookRepository
import com.epubpro.domain.repository.BookShortcutPublisher
import com.epubpro.domain.repository.PinShortcutResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import com.epubpro.core.designsystem.R as DesignR

/**
 * Quản lý lối tắt mở sách trên launcher: lối tắt ghim ra màn hình chính và lối tắt động
 * (danh sách sách đọc gần đây khi nhấn giữ icon app).
 *
 * Mọi lối tắt dùng [BookEntryContract] nên trỏ tới alias nội bộ không export và chỉ mang `bookId`.
 */
@Singleton
class BookShortcutManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository
) : BookShortcutPublisher {

    /**
     * Ghim lối tắt của một cuốn sách ra màn hình chính.
     *
     * Ảnh bìa được giải mã trên [Dispatchers.IO]. Nếu lối tắt cùng ID từng bị vô hiệu hoá (sách bị xoá
     * rồi nạp lại cùng ID) thì bật lại trước, vì hệ thống từ chối ghim lối tắt đang bị vô hiệu hoá.
     *
     * @param book Cuốn sách cần tạo lối tắt.
     * @return Kết quả gửi yêu cầu tới launcher.
     */
    override suspend fun requestPin(book: Book): PinShortcutResult {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            return PinShortcutResult.UNSUPPORTED
        }
        return try {
            val shortcut = withContext(Dispatchers.IO) { buildShortcut(book, rank = 0) }
            val disabledPinned = ShortcutManagerCompat
                .getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .any { it.id == shortcut.id && !it.isEnabled }
            if (disabledPinned) {
                ShortcutManagerCompat.enableShortcuts(context, listOf(shortcut))
            }
            if (ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)) {
                PinShortcutResult.REQUESTED
            } else {
                PinShortcutResult.FAILED
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            PinShortcutResult.FAILED
        }
    }

    /**
     * Bắt đầu đồng bộ lối tắt với thư viện trong suốt vòng đời process.
     *
     * - Cập nhật lối tắt động theo [MAX_DYNAMIC_SHORTCUTS] sách đọc gần nhất.
     * - Vô hiệu hoá lối tắt đã ghim của sách không còn trong thư viện, bật lại nếu sách xuất hiện lại.
     * - Vẽ lại icon lối tắt đã ghim khi sách đổi tiêu đề hoặc có ảnh bìa mới.
     *
     * Room phát lại danh sách sách mỗi lần lưu tiến độ, nên chỉ xử lý khi phần dữ liệu liên quan thay đổi.
     *
     * @param scope Scope sống cùng Application, chạy trên luồng nền.
     */
    fun startSync(scope: CoroutineScope) {
        scope.launch {
            bookRepository.getAllBooks()
                .map(::toLibraryShortcutState)
                .distinctUntilChanged()
                .collect { state ->
                    // Cập nhật lối tắt ghim trước: setDynamicShortcuts đặt lại thứ tự lối tắt động sau cùng.
                    runCatching { syncPinnedShortcuts(state.books) }
                    runCatching { syncDynamicShortcuts(state.recentBooks) }
                }
        }
    }

    /**
     * Thay danh sách lối tắt động bằng các sách đọc gần nhất.
     *
     * @param recentBooks Sách đã đọc, mới nhất trước.
     */
    private fun syncDynamicShortcuts(recentBooks: List<Book>) {
        val limit = minOf(
            MAX_DYNAMIC_SHORTCUTS,
            ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        )
        val shortcuts = recentBooks.take(limit).mapIndexed { index, book -> buildShortcut(book, index) }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    /**
     * Vô hiệu hoá lối tắt ghim của sách đã bị xoá (app không thể tự gỡ lối tắt đã ghim), bật lại
     * lối tắt của sách vẫn còn và vẽ lại icon/nhãn của chúng theo dữ liệu sách hiện tại.
     *
     * @param books Sách hiện có trong thư viện, theo ID.
     */
    private fun syncPinnedShortcuts(books: Map<String, Book>) {
        val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .filter { it.id.startsWith(SHORTCUT_ID_PREFIX) }
        val (alive, removed) = pinned.partition { bookIdOf(it.id) in books }
        val toDisable = removed.filter { it.isEnabled }.map { it.id }
        if (toDisable.isNotEmpty()) {
            ShortcutManagerCompat.disableShortcuts(
                context,
                toDisable,
                context.getString(DesignR.string.book_shortcut_disabled_deleted)
            )
        }
        val toEnable = alive.filterNot { it.isEnabled }
        if (toEnable.isNotEmpty()) {
            ShortcutManagerCompat.enableShortcuts(context, toEnable)
        }
        val refreshed = alive.mapNotNull { shortcut ->
            books[bookIdOf(shortcut.id)]?.let { book -> buildShortcut(book, shortcut.rank) }
        }
        if (refreshed.isNotEmpty()) {
            ShortcutManagerCompat.updateShortcuts(context, refreshed)
        }
    }

    /**
     * Dựng [ShortcutInfoCompat] cho một cuốn sách. Có I/O đọc ảnh bìa nên không gọi trên main thread.
     *
     * @param book Cuốn sách.
     * @param rank Thứ tự hiển thị trong danh sách lối tắt động (0 là trên cùng).
     * @return Lối tắt sẵn sàng gửi cho launcher.
     */
    private fun buildShortcut(book: Book, rank: Int): ShortcutInfoCompat {
        val label = book.title.ifBlank { context.getString(DesignR.string.tts_default_book_title) }
        return ShortcutInfoCompat.Builder(context, shortcutIdOf(book.id))
            .setShortLabel(label.take(MAX_LABEL_LENGTH))
            .setLongLabel(label.take(MAX_LABEL_LENGTH))
            .setIcon(buildIcon(book))
            .setIntent(BookEntryContract.buildIntent(context, book.id))
            .setRank(rank)
            .build()
    }

    /**
     * Tạo icon adaptive từ ảnh bìa (cắt giữa cho vừa khung vuông); sách không có ảnh bìa cục bộ
     * dùng bìa tự sinh cùng màu với Kệ sách.
     *
     * @param book Cuốn sách cần icon.
     * @return Icon cho lối tắt.
     */
    private fun buildIcon(book: Book): IconCompat {
        val cover = book.coverPath?.let(::decodeCover)
            ?: return IconCompat.createWithAdaptiveBitmap(drawGeneratedIcon(book.title))
        val icon = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val side = minOf(cover.width, cover.height)
        val source = Rect(
            (cover.width - side) / 2,
            (cover.height - side) / 2,
            (cover.width + side) / 2,
            (cover.height + side) / 2
        )
        Canvas(icon).drawBitmap(
            cover,
            source,
            Rect(0, 0, ICON_SIZE_PX, ICON_SIZE_PX),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        cover.recycle()
        return IconCompat.createWithAdaptiveBitmap(icon)
    }

    /**
     * Vẽ icon bìa tự sinh: gradient theo [GeneratedCoverPalette] và chữ viết tắt của tiêu đề ở giữa.
     *
     * Chữ nằm trong vùng an toàn của icon adaptive (khoảng 61% giữa) nên không bị launcher cắt mất.
     *
     * @param title Tiêu đề sách.
     * @return Bitmap vuông [ICON_SIZE_PX] px.
     */
    private fun drawGeneratedIcon(title: String): Bitmap {
        val size = ICON_SIZE_PX.toFloat()
        val (top, bottom) = GeneratedCoverPalette.gradientFor(title)
        val icon = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(icon)
        canvas.drawRect(
            0f,
            0f,
            size,
            size,
            Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, size, top.toInt(), bottom.toInt(), Shader.TileMode.CLAMP)
            }
        )
        val initials = titleInitials(title)
        if (initials.isNotEmpty()) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = size * if (initials.length > 1) 0.26f else 0.32f
                setShadowLayer(size * 0.02f, 0f, size * 0.01f, 0x55000000)
            }
            val baseline = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(initials, size / 2f, baseline, textPaint)
        }
        return icon
    }

    /**
     * Giải mã ảnh bìa đã thu nhỏ để icon không vượt giới hạn Binder khi gửi cho launcher.
     *
     * @param coverPath Đường dẫn ảnh bìa; chấp nhận cả dạng `file://`.
     * @return Bitmap đã thu nhỏ, hoặc `null` nếu không phải file cục bộ hoặc giải mã lỗi.
     */
    private fun decodeCover(coverPath: String): Bitmap? {
        val file = File(coverPath.removePrefix("file://"))
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= ICON_SIZE_PX) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull()
    }

    /**
     * Phần dữ liệu thư viện mà lối tắt quan tâm, dùng để bỏ qua các lần Room phát lại không liên quan.
     *
     * @property recentBooks Sách đã đọc, mới nhất trước, giới hạn [MAX_DYNAMIC_SHORTCUTS].
     * @property books Sách hiện có theo ID (đã bỏ `lastReadAt`), dùng để vô hiệu hoá và vẽ lại lối tắt ghim.
     */
    private data class LibraryShortcutState(
        val recentBooks: List<Book>,
        val books: Map<String, Book>
    )

    /**
     * Rút gọn danh sách sách thành [LibraryShortcutState]; bỏ `lastReadAt` khỏi phép so sánh
     * để việc lưu tiến độ của cùng một cuốn không kích hoạt cập nhật lối tắt.
     *
     * @param books Danh sách sách từ Room, sắp theo `lastReadAt` giảm dần.
     * @return Trạng thái rút gọn.
     */
    private fun toLibraryShortcutState(books: List<Book>): LibraryShortcutState =
        LibraryShortcutState(
            recentBooks = books
                .filter { it.lastReadAt > 0L }
                .take(MAX_DYNAMIC_SHORTCUTS)
                .map { it.copy(lastReadAt = 0L) },
            books = books.associate { it.id to it.copy(lastReadAt = 0L) }
        )

    companion object {
        private const val SHORTCUT_ID_PREFIX = "book_"
        private const val MAX_DYNAMIC_SHORTCUTS = 4
        private const val MAX_LABEL_LENGTH = 40
        private const val ICON_SIZE_PX = 192
        private val WHITESPACE = Regex("\\s+")

        /**
         * Tạo ID lối tắt ổn định cho một cuốn sách.
         *
         * @param bookId ID sách.
         * @return ID lối tắt.
         */
        internal fun shortcutIdOf(bookId: String): String = SHORTCUT_ID_PREFIX + bookId

        /**
         * Lấy lại ID sách từ ID lối tắt.
         *
         * @param shortcutId ID lối tắt có tiền tố [SHORTCUT_ID_PREFIX].
         * @return ID sách.
         */
        internal fun bookIdOf(shortcutId: String): String = shortcutId.removePrefix(SHORTCUT_ID_PREFIX)

        /**
         * Lấy chữ viết tắt cho icon bìa tự sinh: chữ cái đầu của tối đa hai từ đầu tiên.
         *
         * Bỏ qua ký tự không phải chữ/số (dấu câu, ngoặc), giữ dấu tiếng Việt (ví dụ "Đấu Phá" → "ĐP").
         *
         * @param title Tiêu đề sách.
         * @return Chuỗi viết hoa 0–2 ký tự; rỗng khi tiêu đề không có chữ/số.
         */
        internal fun titleInitials(title: String): String =
            title.split(WHITESPACE)
                .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
                .take(2)
                .joinToString("")
                .uppercase(Locale.ROOT)
    }
}
