package io.github.mohnstollen.news.data

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLDecoder

data class NcCredentials(val server: String, val user: String, val password: String)

/**
 * The login data in the QR code that Nextcloud shows for a new app password
 * (Personal settings → Security → "Create new app password" → "Show QR code for mobile apps"):
 * `nc://login/user:<login>&password:<app password>&server:<address>`, values URL-encoded,
 * in any order.
 */
object NcLogin {
    private const val PREFIX = "nc://login/"

    /** Returns null for any other text. */
    fun parse(text: String): NcCredentials? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
        val values = HashMap<String, String>()
        for (part in trimmed.substring(PREFIX.length).split('&')) {
            val colon = part.indexOf(':')
            if (colon <= 0) continue
            val raw = part.substring(colon + 1)
            // Like the official Nextcloud app: URL-decoded, the raw value if that fails.
            values[part.substring(0, colon).lowercase()] =
                try { URLDecoder.decode(raw, "UTF-8") } catch (e: IllegalArgumentException) { raw }
        }
        val server = values["server"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val user = values["user"]?.takeIf { it.isNotEmpty() } ?: return null
        val password = values["password"]?.takeIf { it.isNotEmpty() } ?: return null
        return NcCredentials(server, user, password)
    }
}

/** Finds a QR code in a camera frame. Not thread-safe; use one instance per analysis thread. */
class QrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    /**
     * [luminance] is the frame's brightness plane (the Y plane of a YUV image), [rowStride] bytes
     * per row. Also finds light-on-dark codes. Returns the code's text, or null.
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return decode(source) ?: decode(source.invert())
    }

    private fun decode(source: LuminanceSource): String? =
        try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: ReaderException) {
            null
        } finally {
            reader.reset()
        }
}

/** What the settings screen shows after a sync. */
fun syncResultText(addedFeeds: Int): String = when (addedFeeds) {
    0 -> "Synchronisiert."
    1 -> "Synchronisiert, 1 Feed hinzugefügt."
    else -> "Synchronisiert, $addedFeeds Feeds hinzugefügt."
}

/**
 * Nextcloud Login Flow v2: the app asks the server for a login, the user signs in on the
 * server's own page in the browser and grants access, and the app, which keeps asking in the
 * meantime, receives the server address, login name and a new app password for itself.
 * Lives as long as the app, so the waiting goes on while the user is in the browser.
 */
class NcLoginFlow(
    private val client: OkHttpClient,
    private val settings: SettingsStore,
    private val sync: SyncManager,
    private val repository: Repository,
    private val pollIntervalMs: Long = 2_000,
    private val timeoutMs: Long = 20 * 60_000,
    /** How the app introduces itself; Nextcloud names the app password after it. */
    private val clientName: String = CLIENT_NAME,
) {
    sealed interface State {
        data object Idle : State
        /** The login page is open; [loginUrl] can be opened again. */
        data class Waiting(val loginUrl: String) : State
        data class Syncing(val credentials: NcCredentials) : State
        data class Done(val credentials: NcCredentials, val message: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    /**
     * Asks [server] for a login and starts waiting for it. Returns the login page to open in the
     * browser. Throws [SyncException] with a message for the user.
     */
    suspend fun begin(server: String, folder: String): String = withContext(Dispatchers.IO) {
        cancel()
        val request = Request.Builder().url(initUrl(server))
            // Nextcloud names the new app password after this.
            .header("User-Agent", clientName)
            .post(ByteArray(0).toRequestBody())
            .build()
        val init = try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> throw SyncException("Unter dieser Adresse antwortet keine Nextcloud.")
                    !response.isSuccessful -> throw SyncException("Nextcloud antwortet mit HTTP ${response.code}")
                }
                parseInit(response.body?.string())
                    ?: throw SyncException("Unerwartete Antwort – ist die Adresse richtig?")
            }
        } catch (e: IOException) {
            throw SyncException("Server nicht erreichbar: ${e.message ?: e.javaClass.simpleName}", e)
        }
        _state.value = State.Waiting(init.loginUrl)
        job = repository.scope.launch { poll(init, folder) }
        init.loginUrl
    }

    /** Stops waiting. */
    fun cancel() {
        job?.cancel()
        job = null
        if (_state.value is State.Waiting) _state.value = State.Idle
    }

    /** The screen has shown the result. */
    fun acknowledge() {
        val current = _state.value
        if (current is State.Done || current is State.Failed) _state.value = State.Idle
    }

    private class Init(val loginUrl: String, val endpoint: HttpUrl, val token: String)

    private fun parseInit(body: String?): Init? = try {
        val json = Json.parseToJsonElement(body ?: return null).jsonObject
        val poll = json["poll"]?.jsonObject ?: return null
        val token = poll.string("token") ?: return null
        val endpoint = poll.string("endpoint")?.toHttpUrlOrNull() ?: return null
        // Only a web page may be opened from what the server says.
        val login = json.string("login")?.takeIf { it.toHttpUrlOrNull() != null } ?: return null
        Init(login, endpoint, token)
    } catch (e: Exception) {
        null
    }

    private suspend fun poll(init: Init, folder: String) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val credentials = try {
                pollOnce(init)
            } catch (e: IOException) {
                null // keep trying, e.g. while the connection changes
            }
            if (credentials != null) {
                finish(credentials, folder)
                return
            }
            delay(pollIntervalMs)
        }
        _state.value = State.Failed("Die Anmeldung ist abgelaufen. Bitte erneut versuchen.")
    }

    /** 404 means the user has not granted access yet. */
    private fun pollOnce(init: Init): NcCredentials? {
        val request = Request.Builder().url(init.endpoint)
            .header("User-Agent", clientName)
            .post(FormBody.Builder().add("token", init.token).build())
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 200) return null
            val json = try {
                Json.parseToJsonElement(response.body?.string() ?: return null).jsonObject
            } catch (e: Exception) {
                return null
            }
            val server = json.string("server")?.trim()?.trimEnd('/') ?: return null
            val user = json.string("loginName") ?: return null
            val password = json.string("appPassword") ?: return null
            return NcCredentials(server, user, password)
        }
    }

    private suspend fun finish(credentials: NcCredentials, folder: String) {
        _state.value = State.Syncing(credentials)
        val message = try {
            settings.ncPassword = credentials.password
            settings.update {
                it.copy(ncServer = credentials.server, ncUser = credentials.user, ncFolder = folder.trim().ifBlank { settings.defaultFolder })
            }
            val added = sync.sync()
            if (added > 0) repository.scope.launch { repository.refreshAll() }
            syncResultText(added)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Angemeldet, aber: ${e.message ?: e.javaClass.simpleName}"
        }
        _state.value = State.Done(credentials, message)
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        const val CLIENT_NAME = "News (Android)"

        /** The address to start the login at, also from an address that points into WebDAV. */
        fun initUrl(server: String): HttpUrl {
            if (server.isBlank()) throw SyncException("Bitte zuerst die Adresse deiner Nextcloud eintragen.")
            val base = normalizeUrl(server.trim().trimEnd('/'))
                .replace(Regex("/remote\\.php(/.*)?$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("/index\\.php$", RegexOption.IGNORE_CASE), "")
                .toHttpUrlOrNull() ?: throw SyncException("Ungültige Server-Adresse")
            return base.newBuilder().addPathSegments("index.php/login/v2").build()
        }
    }
}
