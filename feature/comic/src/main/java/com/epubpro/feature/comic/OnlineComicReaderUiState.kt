package com.epubpro.feature.comic

import androidx.annotation.StringRes
import com.epubpro.core.comic.ComicChapterInfo
import com.epubpro.core.comic.ComicImageServer
import com.epubpro.domain.model.ComicReadingMode

/**
 * Trạng thái màn hình đọc truyện tranh trực tuyến.
 *
 * @property isLoading Đang tải thông tin chương ban đầu.
 * @property errorRes Mã string resource lỗi khi tải chương thất bại không thể phục hồi.
 * @property errorMessage Lỗi chi tiết nếu có để hiển thị giải thích.
 * @property isProtectionBlocked true nếu trang web yêu cầu xác minh bảo vệ trình duyệt hoặc JavaScript.
 * @property chapterInfo Dữ liệu chương hiện tại (danh sách trang, tiêu đề, liên kết điều hướng).
 * @property currentPage Chỉ số trang hiện tại (0-based).
 * @property mode Chế độ đọc (cuộn dọc webtoon, lật trang LTR, RTL).
 * @property showControls Hiển thị thanh điều khiển trên và dưới.
 * @property isChangingChapter Đang tải chương mới khi người dùng bấm chuyển chương trước/sau.
 * @property keepScreenOn Giữ màn hình luôn sáng theo cài đặt người dùng.
 * @property volumeKeyNavigation Cho phép lật trang bằng phím âm lượng.
 * @property selectedServer Server ảnh người dùng đang chọn (nếu trang hỗ trợ).
 * @property messageRes Thông báo một lần (toast/snackbar).
 */
data class OnlineComicReaderUiState(
    val isLoading: Boolean = true,
    @StringRes val errorRes: Int? = null,
    val errorMessage: String? = null,
    val isProtectionBlocked: Boolean = false,
    val chapterInfo: ComicChapterInfo? = null,
    val currentPage: Int = 0,
    val mode: ComicReadingMode = ComicReadingMode.WEBTOON,
    val showControls: Boolean = false,
    val isChangingChapter: Boolean = false,
    val keepScreenOn: Boolean = false,
    val volumeKeyNavigation: Boolean = false,
    val selectedServer: ComicImageServer? = null,
    @StringRes val messageRes: Int? = null
)
