package com.epubpro.app.shortcut

import com.epubpro.domain.repository.BookShortcutPublisher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Gắn [BookShortcutManager] (module `:app`) vào interface [BookShortcutPublisher] để các module feature
 * tạo lối tắt mà không phụ thuộc `:app`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BookShortcutModule {
    /**
     * Cung cấp cài đặt lối tắt dùng chung cho toàn app.
     *
     * @param manager Singleton quản lý lối tắt.
     * @return [BookShortcutPublisher] cho các ViewModel.
     */
    @Binds
    abstract fun bindBookShortcutPublisher(manager: BookShortcutManager): BookShortcutPublisher
}
