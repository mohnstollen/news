package io.github.mohnstollen.news

import io.github.mohnstollen.news.data.DavUrls
import io.github.mohnstollen.news.data.ReadVisibility
import io.github.mohnstollen.news.data.SyncArticle
import io.github.mohnstollen.news.data.SyncFeed
import io.github.mohnstollen.news.data.SyncMerge
import io.github.mohnstollen.news.data.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1000 * day

    @Test
    fun newerChangeWinsPerFeed() {
        val local = SyncState(
            feeds = listOf(
                SyncFeed("a", "A local", updatedAt = 10),
                SyncFeed("b", "B", updatedAt = 10),
                SyncFeed("c", updatedAt = 30, deleted = true),
                SyncFeed("only-local", "L", updatedAt = 5),
            ),
        )
        val remote = SyncState(
            feeds = listOf(
                SyncFeed("a", "A remote", updatedAt = 20),
                SyncFeed("b", updatedAt = 20, deleted = true),
                SyncFeed("c", "C", updatedAt = 20),
                SyncFeed("only-remote", "R", updatedAt = 5),
            ),
        )
        val merged = SyncMerge.merge(local, remote, now).feeds.associateBy { it.url }
        assertEquals("A remote", merged.getValue("a").title)
        assertTrue("deleted remotely after the local change", merged.getValue("b").deleted)
        assertTrue("deleted locally after the remote change", merged.getValue("c").deleted)
        assertFalse(merged.getValue("only-local").deleted)
        assertFalse(merged.getValue("only-remote").deleted)
        assertEquals(5, merged.size)
    }

    @Test
    fun resubscribingBeatsOlderTombstone() {
        val local = SyncState(feeds = listOf(SyncFeed("a", "A", updatedAt = 50)))
        val remote = SyncState(feeds = listOf(SyncFeed("a", updatedAt = 40, deleted = true)))
        assertFalse(SyncMerge.merge(local, remote, now).feeds.single().deleted)
    }

    @Test
    fun articleStateMerges() {
        val feeds = listOf(SyncFeed("f", "F", updatedAt = 1), SyncFeed("gone", updatedAt = 2, deleted = true))
        val local = SyncState(
            feeds = feeds,
            articles = listOf(
                SyncArticle("f", "1", starred = true, updatedAt = now - 10),
                SyncArticle("f", "2", starred = false, note = "lokal", updatedAt = now - 5),
                SyncArticle("gone", "3", starred = true, updatedAt = now - 5),
            ),
        )
        val remote = SyncState(
            feeds = feeds,
            articles = listOf(
                SyncArticle("f", "1", starred = false, updatedAt = now - 1),
                SyncArticle("f", "2", starred = true, note = "alt", updatedAt = now - 50),
                SyncArticle("f", "4", starred = false, updatedAt = now - 200 * day),
                SyncArticle("f", "5", starred = true, updatedAt = now - 200 * day),
            ),
        )
        val merged = SyncMerge.merge(local, remote, now).articles.associateBy { it.guid }
        assertFalse("un-starred remotely later", merged.getValue("1").starred)
        assertEquals("lokal", merged.getValue("2").note)
        assertFalse("article of a deleted feed is dropped", "3" in merged)
        assertFalse("stale inactive entry is pruned", "4" in merged)
        assertTrue("old but still starred is kept", "5" in merged)
    }

    @Test
    fun readArticlesLeaveTheListAfterTheChosenTime() {
        val hour = 60 * 60_000L
        fun visible(read: Boolean, readAt: Long, minutes: Int) = ReadVisibility.isVisible(read, readAt, minutes, now)

        // Unread articles always stay.
        assertTrue(visible(false, 0, 0))
        assertTrue(visible(false, 0, 60))
        // "Never" keeps everything, "at once" nothing that is read.
        assertTrue(visible(true, 0, ReadVisibility.NEVER))
        assertFalse(visible(true, now, 0))
        // Otherwise for the chosen time after reading.
        assertTrue(visible(true, now - hour + 1000, 60))
        assertFalse(visible(true, now - hour - 1000, 60))
        assertTrue(visible(true, now - 23 * hour, 24 * 60))
        assertFalse(visible(true, now - 25 * hour, 24 * 60))
        // Read before the time was recorded: counts as long ago.
        assertFalse(visible(true, 0, 7 * 24 * 60))
    }

    @Test
    fun davUrls() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/erika",
            DavUrls.root("cloud.example.com/", "erika").toString(),
        )
        assertEquals(
            "https://example.com/nextcloud/remote.php/dav/files/e%20m",
            DavUrls.root("https://example.com/nextcloud", "e m").toString(),
        )
        assertEquals(
            "https://dav.example.com/remote.php/dav/files/x",
            DavUrls.root("https://dav.example.com/remote.php/dav/files/x/", "erika").toString(),
        )
        assertEquals(listOf("News", "Unter ordner"), DavUrls.segments("/News/ Unter ordner /"))
        assertEquals(
            "https://c.example/remote.php/dav/files/u/Meine%20News/news-sync.json",
            DavUrls.child(DavUrls.child(DavUrls.root("c.example", "u"), "Meine News"), "news-sync.json").toString(),
        )
    }
}
