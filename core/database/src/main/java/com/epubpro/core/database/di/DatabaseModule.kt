package com.epubpro.core.database.di

import android.content.Context
import androidx.room.Room
import com.epubpro.core.database.AppDatabase
import com.epubpro.core.database.MIGRATION_2_3
import com.epubpro.core.database.MIGRATION_3_4
import com.epubpro.core.database.MIGRATION_4_5
import com.epubpro.core.database.MIGRATION_5_6
import com.epubpro.core.database.MIGRATION_6_7
import com.epubpro.core.database.MIGRATION_7_8
import com.epubpro.core.database.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "epubpro.db"
        ).addMigrations(
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8
        ).build()
    }

    @Provides
    fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()

    @Provides
    fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()

    @Provides
    fun provideSearchDao(db: AppDatabase): SearchDao = db.searchDao()

    @Provides
    fun provideAiRuleDao(db: AppDatabase): AiRuleDao = db.aiRuleDao()

    @Provides
    fun provideAiChapterDao(db: AppDatabase): AiChapterDao = db.aiChapterDao()

    @Provides
    fun provideBookBibleDao(db: AppDatabase): BookBibleDao = db.bookBibleDao()
}
