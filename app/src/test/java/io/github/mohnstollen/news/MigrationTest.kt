package io.github.mohnstollen.news

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mohnstollen.news.data.AppDatabase
import io.github.mohnstollen.news.data.Migrations
import io.github.mohnstollen.news.data.SearchQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34])
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Builds a database exactly as version 1 of the app created it, from the exported schema. */
    private fun createVersion1(file: File) {
        val schema = JSONObject(File("schemas/io.github.mohnstollen.news.data.AppDatabase/1.json").readText())
            .getJSONObject("database")
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL(
                "INSERT INTO feeds (id, url, title, fetchFullText, lastFetched, updatedAt) " +
                    "VALUES (1, 'https://example.org/feed', 'Alt', 0, 0, 5)",
            )
            db.execSQL(
                "INSERT INTO articles (id, feedId, guid, title, summary, content, pubDate, fetchedAt, read, starred, note, stateUpdatedAt) " +
                    "VALUES (7, 1, 'g', 'Bestandsartikel', 'Kurz', '<p>Langer Inhalt</p>', 100, 100, 1, 1, 'Meine Notiz', 9)",
            )
            db.version = 1
        }
    }

    @Test
    fun version1DatabaseIsUpgradedAndKeepsItsData() = runBlocking {
        val file = context.getDatabasePath("migration.db")
        createVersion1(file)

        // Room checks the migrated schema against the current entities when it opens the file.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "migration.db")
            .addMigrations(*Migrations.ALL).build()
        try {
            val feed = db.feedDao().all().single()
            assertEquals("Alt", feed.title)
            assertTrue("existing feeds notify", feed.notify)

            val article = db.articleDao().byId(7)!!
            assertEquals("Meine Notiz", article.note)
            assertTrue(article.starred)
            assertTrue(article.read)
            assertEquals("read before this was recorded", 0L, article.readAt)
            assertEquals("no read marking to sync yet", 0L, article.readUpdatedAt)

            // Existing articles are searchable, and so are later changes.
            suspend fun search(text: String) =
                db.articleDao().observeSearch(SearchQuery.toMatch(text)!!, null, false).first().map { it.id }
            assertEquals(listOf(7L), search("bestand"))
            assertEquals(listOf(7L), search("inhalt"))
            assertEquals(listOf(7L), search("notiz"))
            db.articleDao().setNote(7, "Geändert", 10)
            assertEquals(emptyList<Long>(), search("meine"))
            assertEquals(listOf(7L), search("geändert"))
        } finally {
            db.close()
        }
    }
}
