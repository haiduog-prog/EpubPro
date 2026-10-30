package com.epubpro.domain.model

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val coverPath: String?,
    val filePath: String,
    val addedAt: Long,
    val lastReadAt: Long,
    val totalChapters: Int = 0,
    val onlineNovelId: String? = null,
    val sourceFormat: BookSourceFormat = BookSourceFormat.EPUB
)

/**
 * Định dạng nguồn mà người dùng đã nhập vào thư viện.
 *
 * Sách chữ (EPUB/PRC/MOBI/AZW3) luôn được lưu thành EPUB nội bộ; giá trị này chỉ phục vụ
 * hiển thị nguồn gốc và chẩn đoán quá trình chuyển đổi. Riêng [CBZ] được giữ nguyên
 * archive ảnh và phải mở bằng Reader truyện tranh thay vì Reader EPUB.
 */
enum class BookSourceFormat {
    EPUB,
    PRC,
    MOBI,
    AZW3,
    CBZ;

    /** true khi sách là archive ảnh, không có nội dung chữ để TTS/AI/FTS xử lý. */
    val isComic: Boolean get() = this == CBZ
}

data class ReadingProgress(
    val bookId: String,
    val currentCfi: String,
    val chapterIndex: Int,
    val pageIndex: Int = 1,
    val progressPercentage: Float,
    val totalChapters: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

data class Bookmark(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val cfi: String,
    val createdAt: Long
)

data class Highlight(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val startCfi: String,
    val endCfi: String,
    val selectedText: String,
    val colorHex: String,
    val note: String?,
    val createdAt: Long
)

data class SearchResultItem(
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val snippet: String,
    val cfi: String? = null
)

enum class ReaderEngineType {
    WEBVIEW,
    READIUM
}

const val MIN_PAGE_TURN_SPEED_MS = 100
const val MAX_PAGE_TURN_SPEED_MS = 600
val PAGE_TURN_SPEED_PRESETS_MS = listOf(120, 220, 450)

enum class ReaderThemeMode {
    LIGHT,
    DARK,
    SEPIA,
    PAPER,
    MIDNIGHT
}

/** Chế độ đọc sách */
enum class ReadingMode {
    SCROLL,             // Cuộn dọc
    SCROLL_HORIZONTAL,  // Cuộn ngang
    FLIP,               // Lật trang (horizontal pagination)
    CONTINUOUS          // Cuốn liên tục
}

/** Căn chỉnh văn bản */
enum class TextAlignment {
    LEFT,
    JUSTIFY
}

/** Preset phông chữ */
enum class FontPreset(val fontFamily: String, val displayName: String) {
    SERIF("serif", "Có chân"),
    SANS_SERIF("sans-serif", "Không chân"),
    MONOSPACE("monospace", "Đơn cách")
}
/** Layout vùng chạm lật trang */
enum class TapZoneLayout(val displayName: String) {
    HORIZONTAL("Vùng chạm ngang"),
    VERTICAL("Vùng chạm dọc"),
    BOTTOM_SPLIT("Vùng chạm chia trên dưới")
}

/** Hành động khi chạm vào vùng màn hình */
enum class TapZoneAction(val label: String) {
    PREV_PAGE("Trang trước"),
    NEXT_PAGE("Trang sau"),
    TOGGLE_CONTROLS("Hiện/Ẩn Menu")
}

fun defaultTapZoneActions(layout: TapZoneLayout): List<TapZoneAction> = when (layout) {
    TapZoneLayout.HORIZONTAL -> List(9) { index ->
        when (index % 3) {
            0 -> TapZoneAction.PREV_PAGE
            2 -> TapZoneAction.NEXT_PAGE
            else -> TapZoneAction.TOGGLE_CONTROLS
        }
    }
    TapZoneLayout.VERTICAL -> List(9) { index ->
        when (index / 3) {
            0 -> TapZoneAction.PREV_PAGE
            2 -> TapZoneAction.NEXT_PAGE
            else -> TapZoneAction.TOGGLE_CONTROLS
        }
    }
    TapZoneLayout.BOTTOM_SPLIT -> List(9) { index ->
        when {
            index < 6 -> TapZoneAction.TOGGLE_CONTROLS
            index == 6 -> TapZoneAction.PREV_PAGE
            index == 8 -> TapZoneAction.NEXT_PAGE
            else -> TapZoneAction.TOGGLE_CONTROLS
        }
    }
}
data class ReaderSettings(
    val engineType: ReaderEngineType = ReaderEngineType.WEBVIEW,
    val fontSizeSp: Float = 18f,
    val fontFamily: String = "serif",
    val lineHeightRatio: Float = 1.5f,
    val marginTopDp: Int = 16,
    val marginBottomDp: Int = 16,
    val marginLeftDp: Int = 16,
    val marginRightDp: Int = 16,
    val themeMode: ReaderThemeMode = ReaderThemeMode.LIGHT,
    val isHorizontalPagination: Boolean = true,
    // Extended fields for Reading Defaults screen
    val readingMode: ReadingMode = ReadingMode.FLIP,
    val paragraphSpacingDp: Int = 8,
    val firstLineIndentDp: Int = 0,
    val textAlignment: TextAlignment = TextAlignment.LEFT,
    val showStatusBar: Boolean = true,
    val showScrollBar: Boolean = false,
    val keepScreenOn: Boolean = false,
    // Extended fields for Page Turn Control
    val tapZoneLayout: TapZoneLayout = TapZoneLayout.HORIZONTAL,
    val tapZoneActions: List<TapZoneAction> = defaultTapZoneActions(TapZoneLayout.HORIZONTAL),
    val enablePageAnimation: Boolean = true,
    val enableKeyboardNavigation: Boolean = true,
    val enableVolumeKeyNavigation: Boolean = false,
    val pageTurnSpeedMs: Int = 220,
    val brightness: Float = 0.5f,
    val autoResumeLastBookOnStartup: Boolean = false
) {
    val marginDp: Int get() = (marginLeftDp + marginRightDp) / 2
}

/**
 * Ngưỡng kích hoạt lớp phủ siêu tối (Extra Dim).
 * Khi độ sáng nhỏ hơn ngưỡng này, đèn nền phần cứng giữ ở mức tối thiểu và kích hoạt lớp phủ đen mờ.
 */
const val EXTRA_DIM_THRESHOLD = 0.2f

/**
 * Độ mờ (alpha) tối đa của lớp phủ siêu tối để đảm bảo nội dung chữ vẫn đọc được trong phòng tối.
 */
const val MAX_EXTRA_DIM_ALPHA = 0.75f

/**
 * Mức độ sáng đèn nền phần cứng tối thiểu của thiết bị.
 */
const val MIN_HARDWARE_BRIGHTNESS = 0.01f

/**
 * Kết quả tính toán độ sáng hybrid cho màn hình đọc sách.
 *
 * @property hardwareBrightness Độ sáng đèn nền phần cứng áp dụng cho Window (từ 0.01f đến 1.0f).
 * @property extraDimAlpha Độ mờ của lớp phủ đen siêu tối (từ 0.0f đến 0.75f).
 */
data class BrightnessOutput(
    val hardwareBrightness: Float,
    val extraDimAlpha: Float
)

/**
 * Tính toán mức độ sáng phần cứng và độ mờ lớp phủ siêu tối dựa trên giá trị độ sáng người dùng thiết lập.
 *
 * Hàm thuần túy (pure function) ánh xạ dải giá trị độ sáng:
 * - Dải 0.2f..1.0f: Ánh xạ tuyến tính sang độ sáng phần cứng (0.01f..1.0f), tắt lớp phủ siêu tối (alpha = 0.0f).
 * - Dải 0.0f..<0.2f: Giữ đèn nền ở mức tối thiểu (0.01f) và tăng dần độ mờ lớp phủ đen từ 0.0f lên tối đa 0.75f.
 *
 * @param brightness Mức độ sáng người dùng chọn trong dải 0.0f đến 1.0f.
 * @return Đối tượng [BrightnessOutput] chứa độ sáng phần cứng và độ mờ lớp phủ siêu tối.
 */
fun calculateBrightnessOutput(brightness: Float): BrightnessOutput {
    val clamped = brightness.coerceIn(0.0f, 1.0f)
    return if (clamped >= EXTRA_DIM_THRESHOLD) {
        val fraction = (clamped - EXTRA_DIM_THRESHOLD) / (1.0f - EXTRA_DIM_THRESHOLD)
        val hw = MIN_HARDWARE_BRIGHTNESS + fraction * (1.0f - MIN_HARDWARE_BRIGHTNESS)
        BrightnessOutput(hardwareBrightness = hw, extraDimAlpha = 0.0f)
    } else {
        val dimFraction = 1.0f - (clamped / EXTRA_DIM_THRESHOLD)
        BrightnessOutput(
            hardwareBrightness = MIN_HARDWARE_BRIGHTNESS,
            extraDimAlpha = dimFraction * MAX_EXTRA_DIM_ALPHA
        )
    }
}
