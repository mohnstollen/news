package io.github.mohnstollen.news.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Serializable
data class GpodderSubscriptions(val add: List<String> = emptyList(), val remove: List<String> = emptyList(), val timestamp: Long = 0)

@Serializable
data class GpodderSubscriptionChange(val add: List<String>, val remove: List<String>)

/** One entry of the gPodder episode action API; times in seconds. */
@Serializable
data class GpodderAction(
    val podcast: String,
    val episode: String,
    val guid: String? = null,
    val action: String,
    /** "2024-01-31T12:00:00", UTC. */
    val timestamp: String? = null,
    val started: Long? = null,
    val position: Long? = null,
    val total: Long? = null,
)

@Serializable
data class GpodderActions(val actions: List<GpodderAction> = emptyList(), val timestamp: Long = 0)

/** The server has no gPodder Sync app. */
class GpodderMissingException : Exception("gPodder Sync ist auf dem Nextcloud-Server nicht installiert")

/** The API of the Nextcloud app "gPodder Sync", as AntennaPod and others use it. */
class GpodderClient(private val client: OkHttpClient, server: String, user: String, password: String) {
    private val auth = Credentials.basic(user, password, Charsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val base: HttpUrl = baseUrl(server)

    private fun call(request: Request.Builder): String {
        val built = request.header("Authorization", auth).header("User-Agent", USER_AGENT).build()
        client.newCall(built).execute().use { response ->
            when {
                response.isSuccessful -> return response.body?.string().orEmpty()
                response.code == 404 -> throw GpodderMissingException()
                response.code == 401 || response.code == 403 ->
                    throw SyncException("gPodder Sync: Anmeldung abgelehnt. Bitte Benutzername und App-Passwort prüfen.")
                else -> throw SyncException("gPodder Sync antwortet mit HTTP ${response.code}")
            }
        }
    }

    private fun url(path: String, since: Long? = null) = base.newBuilder().addPathSegments(path).apply {
        if (since != null) addQueryParameter("since", since.toString())
    }.build()

    fun subscriptions(since: Long): GpodderSubscriptions =
        json.decodeFromString(call(Request.Builder().url(url("subscriptions", since)).get()))

    fun changeSubscriptions(add: List<String>, remove: List<String>) {
        if (add.isEmpty() && remove.isEmpty()) return
        val body = json.encodeToString(GpodderSubscriptionChange.serializer(), GpodderSubscriptionChange(add, remove))
        call(Request.Builder().url(url("subscription_change/create")).post(body.toRequestBody(JSON)))
    }

    fun actions(since: Long): GpodderActions =
        json.decodeFromString(call(Request.Builder().url(url("episode_action", since)).get()))

    fun upload(actions: List<GpodderAction>) {
        if (actions.isEmpty()) return
        val body = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(GpodderAction.serializer()), actions)
        call(Request.Builder().url(url("episode_action/create")).post(body.toRequestBody(JSON)))
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** The app's address on the server; an address pointing into WebDAV is cut back to the server. */
        fun baseUrl(server: String): HttpUrl {
            val text = normalizeUrl(server.trim().trimEnd('/'))
            val root = text.substringBefore("/remote.php").substringBefore("/index.php")
            val url = root.toHttpUrlOrNull() ?: throw SyncException("Ungültige Server-Adresse")
            return url.newBuilder().addPathSegments("index.php/apps/gpoddersync").build()
        }
    }
}

/**
 * Podfang's subscriptions and listening positions with gPodder Sync: what changed here goes up,
 * what changed elsewhere (another phone, AntennaPod) comes down. Bookkeeping per account in its
 * own preferences file.
 */
class GpodderSync(context: Context, private val repository: Repository) {
    private val sp = context.getSharedPreferences("gpodder", Context.MODE_PRIVATE)

    /** Changes the server listed since the last time, and the subscriptions known then. */
    private data class State(val account: String, val subsSince: Long, val actionsSince: Long, val known: Set<String>)

    private fun load(account: String): State {
        if (sp.getString("account", null) != account) return State(account, 0, 0, emptySet())
        return State(
            account,
            sp.getLong("subsSince", 0),
            sp.getLong("actionsSince", 0),
            sp.getStringSet("known", emptySet())!!.toSet(),
        )
    }

    private fun save(state: State) {
        sp.edit().putString("account", state.account).putLong("subsSince", state.subsSince)
            .putLong("actionsSince", state.actionsSince).putStringSet("known", state.known).apply()
    }

    /** One round with [client]; returns the number of podcasts added here. Throws as [GpodderClient] does. */
    suspend fun sync(client: GpodderClient, account: String): Int {
        var state = load(account)
        val first = state.subsSince == 0L && state.known.isEmpty()

        // Subscriptions. What changed here since last time is not undone by older news from the server.
        val local = repository.feedDao.all()
        val localKeys = local.map { key(it.url) }.toSet()
        val knownKeys = state.known.map(::key).toSet()
        val addedHere = local.filter { key(it.url) !in knownKeys }.map { key(it.url) }.toSet()
        val removedHere = state.known.filter { key(it) !in localKeys }
        val removedKeys = removedHere.map(::key).toSet()
        val remote = client.subscriptions(state.subsSince)
        var added = 0
        for (url in remote.add.distinctBy(::key)) {
            val k = key(url)
            if (k in localKeys || k in removedKeys) continue
            if (repository.subscribeFromSync(url)) added++
        }
        // At the first round the podcasts here are kept: they are sent up below.
        if (!first) {
            val byKey = local.associateBy { key(it.url) }
            for (url in remote.remove) {
                val k = key(url)
                if (k in addedHere) continue
                byKey[k]?.let { repository.deleteFeed(it.id) }
            }
        }
        val now = repository.feedDao.all()
        client.changeSubscriptions(
            add = now.filter { first || key(it.url) in addedHere }.map { it.url },
            remove = removedHere,
        )
        state = state.copy(subsSince = remote.timestamp, known = now.map { it.url }.toSet())
        save(state)

        // Listening positions: first what came from elsewhere, then what happened here.
        val actions = client.actions(state.actionsSince)
        applyActions(actions.actions)
        val pending = repository.playActionDao.pending()
        client.upload(pending.mapNotNull(::toAction))
        pending.forEach { repository.playActionDao.sent(it.articleId, it.at) }
        save(state.copy(actionsSince = actions.timestamp))
        return added
    }

    private suspend fun applyActions(actions: List<GpodderAction>) {
        val feeds = repository.feedDao.all().associateBy { key(it.url) }
        // Per episode only the latest counts.
        val latest = actions.filter { it.action.equals("play", ignoreCase = true) && it.position != null }
            .groupBy { it.podcast to (it.guid ?: it.episode) }
            .map { (_, list) -> list.maxBy { parseTime(it.timestamp) } }
        for (action in latest) {
            val feed = feeds[key(action.podcast)] ?: continue
            val article = action.guid?.let { repository.articleDao.byGuid(feed.id, it) }
                ?: repository.articleDao.byEnclosure(feed.id, action.episode)
                ?: continue
            val position = action.position!!.coerceAtLeast(0)
            val total = action.total?.takeIf { it > 0 } ?: article.durationSeconds?.takeIf { it > 0 }
            val played = total != null && position >= total - END_MARGIN_S
            repository.applyRemotePlay(article.id, position * 1000, played, parseTime(action.timestamp))
        }
    }

    private fun toAction(row: PlayActionRow): GpodderAction? {
        val episode = row.enclosureUrl ?: return null
        val total = row.durationSeconds?.takeIf { it > 0 }
        return GpodderAction(
            podcast = row.feedUrl,
            episode = episode,
            guid = row.guid,
            action = "play",
            timestamp = formatTime(row.at),
            started = row.startedMs / 1000,
            position = row.positionMs / 1000,
            total = total ?: (row.positionMs / 1000),
        )
    }

    companion object {
        /** This close to the end counts as heard, as with the player's own margin. */
        private const val END_MARGIN_S = 15L

        private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        /** Addresses as compared: other apps may write them a little differently. */
        fun key(url: String) = normalizeUrl(url.trim()).trimEnd('/').lowercase()

        fun formatTime(ms: Long): String =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC).format(FORMAT)

        /** ms since 1970; 0 if missing or not understood. */
        fun parseTime(text: String?): Long {
            if (text.isNullOrBlank()) return 0
            return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(text.take(19)).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
                ?: 0
        }
    }
}
