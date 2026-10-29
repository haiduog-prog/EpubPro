package com.epubpro.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ai_rules (
                id TEXT NOT NULL PRIMARY KEY,
                scope TEXT NOT NULL,
                bookId TEXT,
                source TEXT NOT NULL,
                action TEXT NOT NULL,
                replacement TEXT,
                caseSensitive INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_ai_rules_bookId ON ai_rules(bookId)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ai_chapter_cache (
                id TEXT NOT NULL PRIMARY KEY,
                bookId TEXT NOT NULL,
                chapterIndex INTEGER NOT NULL,
                sourceHash TEXT NOT NULL,
                configHash TEXT NOT NULL,
                status TEXT NOT NULL,
                filePath TEXT,
                modelId TEXT NOT NULL,
                completedParts INTEGER NOT NULL,
                totalParts INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_ai_chapter_cache_bookId_chapterIndex
            ON ai_chapter_cache(bookId, chapterIndex)
            """.trimIndent()
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN totalChapters INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE reading_progress ADD COLUMN totalChapters INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS book_bible_editions (
                localSourceKey TEXT NOT NULL PRIMARY KEY,
                backendBookId TEXT NOT NULL,
                backendEditionId TEXT NOT NULL,
                mappingRevision INTEGER NOT NULL,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                chapterCount INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS book_bible_submissions (
                id TEXT NOT NULL PRIMARY KEY,
                localSourceKey TEXT NOT NULL,
                chapterNumber INTEGER NOT NULL,
                sourceHash TEXT NOT NULL,
                payloadPath TEXT,
                submissionId TEXT,
                state TEXT NOT NULL,
                attempts INTEGER NOT NULL,
                errorCode INTEGER,
                errorMessage TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_book_bible_submissions_localSourceKey_chapterNumber_sourceHash 
            ON book_bible_submissions(localSourceKey, chapterNumber, sourceHash)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_book_bible_submissions_localSourceKey_chapterNumber 
            ON book_bible_submissions(localSourceKey, chapterNumber)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS book_bible_snapshots (
                id TEXT NOT NULL PRIMARY KEY,
                editionId TEXT NOT NULL,
                localSourceKey TEXT NOT NULL,
                chapterNumber INTEGER NOT NULL,
                canonicalChapter INTEGER NOT NULL,
                status TEXT NOT NULL,
                coverageJson TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                revision INTEGER NOT NULL,
                byteSize INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                lastAccessedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_book_bible_snapshots_editionId_chapterNumber 
            ON book_bible_snapshots(editionId, chapterNumber)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_book_bible_snapshots_localSourceKey_chapterNumber 
            ON book_bible_snapshots(localSourceKey, chapterNumber)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_book_bible_snapshots_lastAccessedAt 
            ON book_bible_snapshots(lastAccessedAt)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS book_bible_timelines (
                id TEXT NOT NULL PRIMARY KEY,
                editionId TEXT NOT NULL,
                localSourceKey TEXT NOT NULL,
                chapterNumber INTEGER NOT NULL,
                characterId TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                byteSize INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                lastAccessedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_book_bible_timelines_editionId_chapterNumber_characterId 
            ON book_bible_timelines(editionId, chapterNumber, characterId)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_book_bible_timelines_localSourceKey_chapterNumber_characterId 
            ON book_bible_timelines(localSourceKey, chapterNumber, characterId)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_book_bible_timelines_lastAccessedAt 
            ON book_bible_timelines(lastAccessedAt)
            """.trimIndent()
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    /**
     * Bổ sung định danh truyện online vào sách local để nhận diện bản tải về ổn định.
     *
     * @param db Cơ sở dữ liệu SQLite cần nâng cấp.
     */
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN onlineNovelId TEXT")
    }
}

/**
 * Bổ sung định dạng nguồn của sách để thư viện hiển thị nguồn gốc file.
 *
 * @param db Cơ sở dữ liệu SQLite cần nâng cấp.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN sourceFormat TEXT NOT NULL DEFAULT 'EPUB'")
    }
}

/**
 * Bổ sung timestamp cho tiến độ để gộp chính xác lịch sử đọc giữa các thiết bị.
 *
 * @param db Cơ sở dữ liệu SQLite cần nâng cấp.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reading_progress ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            UPDATE reading_progress
            SET updatedAt = COALESCE((SELECT lastReadAt FROM books WHERE books.id = reading_progress.bookId), 0)
            """.trimIndent()
        )
    }
}
