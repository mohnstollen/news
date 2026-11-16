package io.github.mohnstollen.news.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
data class SyncState(
    val version: Int = 1,
    val feeds: List<SyncFeed> = emptyList(),
    val articles: List<SyncArticle> = emptyList(),
)

@Serializable
data class SyncFeed(
    val url: String,
    val title: String = "",
    val siteUrl: String? = null,
    val category: String? = null,
    val fetchFullText: Boolean = false,
    val updatedAt: Long = 0,
    /** A tombstone: the feed was unsubscribed at [updatedAt]. */
    val deleted: Boolean = false,
)

/** Star and note of one article. Only articles the user starred or annotated are synced. */
@Serializable
data class SyncArticle(
    val feedUrl: String,
    val guid: String,
    val title: String = "",
    val link: String? = null,
    val pubDate: Long = 0,
    val starred: Boolean = false,
    val note: String? = null,
    val updatedAt: Long = 0,
)

object SyncMerge {
    private const val INACTIVE_KEEP_MS = 180L * 24 * 60 * 60 * 1000

    /** Per feed and per article the most recent change wins; on a tie the local side wins. */
    fun merge(local: SyncState, remote: SyncState, now: Long): SyncState {
        val feeds = (local.feeds + remote.feeds)
            .groupBy { it.url }
            .map { (_, versions) -> versions.maxBy { it.updatedAt } }
        val liveUrls = feeds.filter { !it.deleted }.map { it.url }.toSet()
        val articles = (local.articles + remote.articles)
            .groupBy { it.feedUrl to it.guid }
            .map { (_, versions) -> versions.maxBy { it.updatedAt } }
            .filter { it.feedUrl in liveUrls }
            // Un-starred entries are kept for a while so the removal reaches the other devices.
            .filter { it.starred || !it.note.isNullOrBlank() || now - it.updatedAt < INACTIVE_KEEP_MS }
        return SyncState(feeds = feeds.sortedBy { it.url }, articles = articles.sortedBy { it.updatedAt })
    }
}

class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

class WebDavClient(private val client: OkHttpClient, user: String, password: String) {
    private val auth = Credentials.basic(user, password, Charsets.UTF_8)

    private fun request(url: HttpUrl) = Request.Builder().url(url)
        .header("Authorization", auth)
        .header("User-Agent", USER_AGENT)

    private fun fail(code: Int): Nothing = throw SyncException(
        when (code) {
            401, 403 -> "Anmeldung abgelehnt. Bitte Benutzername und App-Passwort prüfen."
            404 -> "WebDAV-Adresse nicht gefunden. Bitte Server-Adresse und Benutzername prüfen."
            507 -> "Kein Speicherplatz mehr auf dem Server."
            else -> "Server antwortet mit HTTP $code"
        },
    )

    /** Returns null if the file does not exist. */
    fun get(url: HttpUrl): String? =
        client.newCall(request(url).get().build()).execute().use { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> response.body?.string()
                else -> fail(response.code)
            }
        }

    fun put(url: HttpUrl, content: String, contentType: String) {
        val body = content.toByteArray().toRequestBody(contentType.toMediaType())
        client.newCall(request(url).put(body).build()).execute().use { response ->
            if (!response.isSuccessful) fail(response.code)
        }
    }

    /** Creates the collection; an existing one is not an error. */
    fun mkcol(url: HttpUrl) {
        client.newCall(request(url).method("MKCOL", null).build()).execute().use { response ->
            // 405: already exists
            if (!response.isSuccessful && response.code != 405) fail(response.code)
        }
    }
}

object DavUrls {
    /**
     * The user's WebDAV root. A plain server address ("cloud.example.com") is expanded to the
     * Nextcloud files endpoint; an address that already points into WebDAV is used unchanged.
     */
    fun root(server: String, user: String): HttpUrl {
        val base = normalizeUrl(server.trim().trimEnd('/')).toHttpUrlOrNull()
            ?: throw SyncException("Ungültige Server-Adresse")
        val path = base.encodedPath
        if (path.contains("/remote.php/") || path.contains("/dav")) return base
        return base.newBuilder().addPathSegments("remote.php/dav/files").addPathSegment(user).build()
    }

    fun segments(folder: String): List<String> = folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }

    fun child(parent: HttpUrl, name: String): HttpUrl = parent.newBuilder().addPathSegment(name).build()
}

class SyncManager(
    private val client: OkHttpClient,
    private val settings: SettingsStore,
    private val repository: Repository,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /** Two-way sync. Returns the number of feeds that were added locally. Throws [SyncException]. */
    suspend fun sync(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = settings.current
            if (!prefs.syncConfigured) throw SyncException("Nextcloud ist nicht eingerichtet")
            try {
                val dav = WebDavClient(client, prefs.ncUser.trim(), settings.ncPassword)
                var folder = DavUrls.root(prefs.ncServer, prefs.ncUser.trim())
                for (segment in DavUrls.segments(prefs.ncFolder)) {
                    folder = DavUrls.child(folder, segment)
                    dav.mkcol(folder)
                }
                val stateUrl = DavUrls.child(folder, STATE_FILE)

                val remote = dav.get(stateUrl)?.takeIf { it.isNotBlank() }?.let {
                    try {
                        json.decodeFromString<SyncState>(it)
                    } catch (e: Exception) {
                        throw SyncException("Die Datei $STATE_FILE auf dem Server ist beschädigt", e)
                    }
                } ?: SyncState()

                val merged = SyncMerge.merge(repository.exportSyncState(), remote, System.currentTimeMillis())
                val added = repository.applySyncState(merged)
                dav.put(stateUrl, json.encodeToString(SyncState.serializer(), merged), "application/json")
                // A plain OPML copy, so the subscriptions are usable with other apps too.
                dav.put(DavUrls.child(folder, OPML_FILE), repository.exportOpml(), "text/x-opml")

                settings.update { it.copy(lastSync = System.currentTimeMillis(), lastSyncError = null) }
                added
            } catch (e: Exception) {
                val message = when (e) {
                    is SyncException -> e.message!!
                    is IOException -> "Server nicht erreichbar: ${e.message ?: e.javaClass.simpleName}"
                    else -> throw e
                }
                settings.update { it.copy(lastSyncError = message) }
                throw SyncException(message, e)
            }
        }
    }

    private companion object {
        const val STATE_FILE = "news-sync.json"
        const val OPML_FILE = "feeds.opml"
    }
}
