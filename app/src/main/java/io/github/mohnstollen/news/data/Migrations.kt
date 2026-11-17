package io.github.mohnstollen.news.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations of the Android database. Kept apart from [AppDatabase] so that the
 * Linux version can compile the shared database classes without the Android SQLite API.
 */
object Migrations {
    /** Adds the per-feed notification switch and the search index. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE feeds ADD COLUMN notify INTEGER NOT NULL DEFAULT 1")
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `articles_fts` USING FTS4(`title` TEXT NOT NULL, " +
                    "`summary` TEXT NOT NULL, `content` TEXT, `fullText` TEXT, `note` TEXT, " +
                    "tokenize=unicode61, content=`articles`)",
            )
            db.execSQL("INSERT INTO articles_fts(articles_fts) VALUES('rebuild')")
        }
    }

    /** Records when an article was read. Articles read before count as read long ago. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE articles ADD COLUMN readAt INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
