package com.epubpro.core.reader.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Intent contract shared by the TTS notification/bubble and the app navigation layer. */
object TtsOpenBookContract {
    const val ACTION_OPEN_BOOK = "com.epubpro.app.action.OPEN_TTS_BOOK"

    const val EXTRA_BOOK_ID = "com.epubpro.app.extra.TTS_BOOK_ID"
    const val EXTRA_CHAPTER_INDEX = "com.epubpro.app.extra.TTS_CHAPTER_INDEX"
    const val EXTRA_OPEN_TTS_PLAYER = "com.epubpro.app.extra.OPEN_TTS_PLAYER"

    const val NAV_ARGUMENT_CHAPTER_INDEX = "chapterIndex"
    const val NAV_ARGUMENT_OPEN_TTS_PLAYER = "openTtsPlayer"
    const val NO_CHAPTER_OVERRIDE = -1

    /**
     * Tên `activity-alias` không export trỏ tới MainActivity. Action nội bộ (mở sách, mở kệ sách)
     * chỉ được tin khi Intent đi qua alias này, nên app khác không giả được yêu cầu điều hướng.
     */
    const val INTERNAL_ENTRY_CLASS = "com.epubpro.app.InternalEntryActivity"

    /**
     * Tạo component của alias nội bộ cho package hiện tại.
     *
     * @param context Context dùng để lấy package name (hỗ trợ cả applicationIdSuffix).
     * @return [ComponentName] của `InternalEntryActivity`.
     */
    fun internalEntryComponent(context: Context): ComponentName =
        ComponentName(context.packageName, INTERNAL_ENTRY_CLASS)

    /**
     * Kiểm tra Intent có đi qua alias nội bộ không export hay không.
     *
     * @param intent Intent Activity nhận được.
     * @return `true` nếu component của Intent là [INTERNAL_ENTRY_CLASS].
     */
    fun isInternalEntry(intent: Intent?): Boolean =
        intent?.component?.className == INTERNAL_ENTRY_CLASS

    /**
     * Adds the complete request to an explicit MainActivity intent supplied by the caller.
     */
    fun configureIntent(
        intent: Intent,
        bookId: String,
        chapterIndex: Int,
        openTtsPlayer: Boolean = true
    ): Intent {
        require(bookId.isNotBlank()) { "A TTS open-book request requires a book id" }
        require(chapterIndex >= 0) { "A TTS open-book request requires a valid chapter index" }
        return intent.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            action = ACTION_OPEN_BOOK
            putExtra(EXTRA_BOOK_ID, bookId)
            putExtra(EXTRA_CHAPTER_INDEX, chapterIndex)
            putExtra(EXTRA_OPEN_TTS_PLAYER, openTtsPlayer)
        }
    }

    fun parse(intent: Intent?): TtsOpenBookRequest? {
        if (intent?.action != ACTION_OPEN_BOOK) return null
        // hasExtra unparcel toàn bộ Bundle; extra hỏng không được làm crash Activity.
        val hasRequiredExtras = runCatching {
            intent.hasExtra(EXTRA_CHAPTER_INDEX) && intent.hasExtra(EXTRA_OPEN_TTS_PLAYER)
        }.getOrDefault(false)
        if (!hasRequiredExtras) return null

        val bookId = runCatching { intent.getStringExtra(EXTRA_BOOK_ID) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val chapterIndex = runCatching {
            intent.getIntExtra(EXTRA_CHAPTER_INDEX, NO_CHAPTER_OVERRIDE)
        }.getOrDefault(NO_CHAPTER_OVERRIDE)
        if (chapterIndex < 0) return null

        val openTtsPlayer = runCatching {
            intent.getBooleanExtra(EXTRA_OPEN_TTS_PLAYER, false)
        }.getOrDefault(false)
        return TtsOpenBookRequest(
            bookId = bookId,
            chapterIndex = chapterIndex,
            openTtsPlayer = openTtsPlayer
        )
    }
}

data class TtsOpenBookRequest(
    val bookId: String,
    val chapterIndex: Int,
    val openTtsPlayer: Boolean = true
)
