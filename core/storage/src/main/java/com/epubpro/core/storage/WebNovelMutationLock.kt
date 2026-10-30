package com.epubpro.core.storage

import kotlinx.coroutines.sync.Mutex

/** Đồng bộ thao tác thay EPUB truyện web giữa trình duyệt và Worker trong cùng tiến trình. */
object WebNovelMutationLock {
    val mutex = Mutex()
}
