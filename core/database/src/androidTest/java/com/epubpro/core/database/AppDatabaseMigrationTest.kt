package com.epubpro.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.epubpro.core.database.entity.BookEntity
import com.epubpro.core.database.entity.BookSearchEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Kiểm tra migration thật trên SQLite của thiết bị: Room đối chiếu schema sau migration với file schema đã
 * export (`schemas/.../8.json`), nên bắt được mọi chỗ lệch cột, NOT NULL, giá trị mặc định hay index mà test
 * mock chỉ so chuỗi SQL không phát hiện được.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    /** Nâng từ schema version 2 (bản đầu tiên còn hỗ trợ migration) lên version hiện tại và giữ dữ liệu cũ. */
    @Test
    fun migrateFromVersion2ToLatestKeepsUserData() {
        createVersion2Database { db ->
            db.execSQL(
                "INSERT INTO books (id, title, author, coverPath, filePath, addedAt, lastReadAt) " +
                    "VALUES ('b1', 'Tựa', 'Tác giả', NULL, '/books/b1.epub', 100, 5000)"
            )
            db.execSQL(
                "INSERT INTO reading_progress (bookId, currentCfi, chapterIndex, pageIndex, progressPercentage) " +
                    "VALUES ('b1', 'cfi', 3, 2, 0.5)"
            )
            db.execSQL(
                "INSERT INTO bookmarks (id, bookId, chapterIndex, chapterTitle, cfi, createdAt) " +
                    "VALUES ('m1', 'b1', 3, 'Chương 4', 'cfi', 200)"
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            LATEST_VERSION,
            true,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8
        )

        db.query("SELECT sourceFormat, totalChapters, onlineNovelId FROM books WHERE id = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("EPUB", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(true, cursor.isNull(2))
        }
        db.query("SELECT chapterIndex, updatedAt FROM reading_progress WHERE bookId = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(3, cursor.getInt(0))
            // MIGRATION_7_8 lấy mốc thời gian tiến độ từ lastReadAt của sách.
            assertEquals(5000L, cursor.getLong(1))
        }
        db.query("SELECT COUNT(*) FROM bookmarks WHERE bookId = 'b1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
    }

    /** Xóa sách phải xóa luôn chỉ mục toàn văn của sách đó nhưng giữ chỉ mục của sách khác. */
    @Test
    fun deleteBookRemovesItsSearchIndex() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val bookDao = database.bookDao()
            listOf("b1", "b2").forEach { id ->
                bookDao.insertBook(
                    BookEntity(
                        id = id,
                        title = id,
                        author = "",
                        coverPath = null,
                        filePath = "/books/$id.epub",
                        addedAt = 0L,
                        lastReadAt = 0L
                    )
                )
                database.searchDao().insertSearchIndex(
                    listOf(BookSearchEntity(bookId = id, chapterIndex = "0", chapterTitle = "C1", textContent = "nội dung"))
                )
            }

            bookDao.deleteBook("b1")

            database.openHelper.readableDatabase
                .query("SELECT bookId FROM book_search_fts")
                .use { cursor ->
                    assertEquals(1, cursor.count)
                    cursor.moveToFirst()
                    assertEquals("b2", cursor.getString(0))
                }
        } finally {
            database.close()
        }
    }

    /**
     * Tạo file database ở schema version 2 bằng đúng câu lệnh Room sinh ra cho các entity thời đó
     * (schema version 2 chưa được export nên phải dựng tay).
     *
     * @param seed Thao tác chèn dữ liệu mẫu sau khi tạo bảng.
     */
    private fun createVersion2Database(seed: (SupportSQLiteDatabase) -> Unit) {
        context.deleteDatabase(TEST_DB)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    VERSION_2_SCHEMA.forEach(db::execSQL)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { openHelper ->
            seed(openHelper.writableDatabase)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val LATEST_VERSION = 8

        val VERSION_2_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `books` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, " +
                "`coverPath` TEXT, `filePath` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, `lastReadAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `reading_progress` (`bookId` TEXT NOT NULL, `currentCfi` TEXT NOT NULL, " +
                "`chapterIndex` INTEGER NOT NULL, `pageIndex` INTEGER NOT NULL, `progressPercentage` REAL NOT NULL, " +
                "PRIMARY KEY(`bookId`))",
            "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` TEXT NOT NULL, `bookId` TEXT NOT NULL, " +
                "`chapterIndex` INTEGER NOT NULL, `chapterTitle` TEXT NOT NULL, `cfi` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `highlights` (`id` TEXT NOT NULL, `bookId` TEXT NOT NULL, " +
                "`chapterIndex` INTEGER NOT NULL, `startCfi` TEXT NOT NULL, `endCfi` TEXT NOT NULL, " +
                "`selectedText` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `note` TEXT, `createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
            "CREATE VIRTUAL TABLE IF NOT EXISTS `book_search_fts` USING FTS4(`bookId` TEXT NOT NULL, " +
                "`chapterIndex` TEXT NOT NULL, `chapterTitle` TEXT NOT NULL, `textContent` TEXT NOT NULL)"
        )
    }
}
