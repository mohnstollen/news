package io.github.mohnstollen.news.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Schema migrations of the desktop database, which started at version 3. They make the same
 * changes as the Android migrations (see Migrations.kt in the app), through the driver API.
 */
object DesktopMigrations {
    /** Records when the read state last changed, so that it can be synced. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE articles ADD COLUMN readUpdatedAt INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL = arrayOf(MIGRATION_3_4)
}
