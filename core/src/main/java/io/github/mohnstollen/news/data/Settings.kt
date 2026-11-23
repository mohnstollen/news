package io.github.mohnstollen.news.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val MIN_INTERVAL_MINUTES = 15
const val DEFAULT_INTERVAL_MINUTES = 60

data class Prefs(
    val autoUpdate: Boolean = true,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    val wifiOnly: Boolean = false,
    val markReadOnScroll: Boolean = true,
    val unreadOnly: Boolean = false,
    val newestFirst: Boolean = true,
    /** Read articles older than this are removed once their feed no longer lists them. 0 = keep. */
    val retentionDays: Int = 90,
    /** Minutes a read article stays in the lists; see [ReadVisibility]. */
    val hideReadAfterMinutes: Int = 24 * 60,
    /** Announce articles found by a background update. */
    val notifyNewArticles: Boolean = false,
    /** How often a placed home screen widget loads the feeds and redraws. */
    val widgetIntervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    val ncServer: String = "",
    val ncUser: String = "",
    val ncFolder: String = "News",
    /** Nextcloud user id for the WebDAV path, looked up for the login in [ncDavUserFor]. */
    val ncDavUser: String = "",
    val ncDavUserFor: String = "",
    val ncAutoSync: Boolean = true,
    val lastSync: Long = 0,
    val lastSyncError: String? = null,
    /** Speed for podcast playback; 1 is normal. */
    val playbackSpeed: Float = 1f,
    /** Episode downloads wait for a network without volume limit. */
    val downloadWifiOnly: Boolean = true,
    /** Delete an episode's file once it was played to the end. */
    val deletePlayedDownloads: Boolean = true,
    /** Seconds before the end at which an episode counts as played and the rest is skipped; 0 = off. */
    val endBufferSeconds: Int = 0,
    /** A downloaded episode is put into the queue. */
    val downloadEnqueues: Boolean = true,
    /** An episode put into the queue is downloaded (on Wi-Fi if [downloadWifiOnly]). */
    val queueDownloads: Boolean = true,
    /** Next to play/pause on the lock screen: back 10 s / forward 30 s instead of previous/next episode. */
    val lockScreenSeek: Boolean = false,
    /** Podfang: also sync subscriptions and listening positions with gPodder Sync on the Nextcloud. */
    val gpodderSync: Boolean = true,
    /** Outcome of the last gPodder Sync round: null if it went well, else why not. */
    val gpodderStatus: String? = null,
) {
    val syncConfigured: Boolean get() = ncServer.isNotBlank() && ncUser.isNotBlank()
}

/** [defaults] are used for what was never set, e.g. the app's own Nextcloud folder. */
class SettingsStore(context: Context, private val defaults: Prefs = Prefs()) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Prefs> = _flow

    val current: Prefs get() = _flow.value

    /** The app's own Nextcloud folder, for an empty folder field. */
    val defaultFolder: String get() = defaults.ncFolder

    private fun load(): Prefs {
        val d = defaults
        return Prefs(
            autoUpdate = sp.getBoolean("autoUpdate", d.autoUpdate),
            intervalMinutes = sp.getInt("intervalMinutes", d.intervalMinutes),
            wifiOnly = sp.getBoolean("wifiOnly", d.wifiOnly),
            markReadOnScroll = sp.getBoolean("markReadOnScroll", d.markReadOnScroll),
            unreadOnly = sp.getBoolean("unreadOnly", d.unreadOnly),
            newestFirst = sp.getBoolean("newestFirst", d.newestFirst),
            retentionDays = sp.getInt("retentionDays", d.retentionDays),
            hideReadAfterMinutes = sp.getInt("hideReadAfterMinutes", d.hideReadAfterMinutes),
            notifyNewArticles = sp.getBoolean("notifyNewArticles", d.notifyNewArticles),
            widgetIntervalMinutes = sp.getInt("widgetIntervalMinutes", d.widgetIntervalMinutes),
            ncServer = sp.getString("ncServer", d.ncServer)!!,
            ncUser = sp.getString("ncUser", d.ncUser)!!,
            ncFolder = sp.getString("ncFolder", d.ncFolder)!!,
            ncDavUser = sp.getString("ncDavUser", d.ncDavUser)!!,
            ncDavUserFor = sp.getString("ncDavUserFor", d.ncDavUserFor)!!,
            ncAutoSync = sp.getBoolean("ncAutoSync", d.ncAutoSync),
            lastSync = sp.getLong("lastSync", d.lastSync),
            lastSyncError = sp.getString("lastSyncError", d.lastSyncError),
            playbackSpeed = sp.getFloat("playbackSpeed", d.playbackSpeed),
            downloadWifiOnly = sp.getBoolean("downloadWifiOnly", d.downloadWifiOnly),
            deletePlayedDownloads = sp.getBoolean("deletePlayedDownloads", d.deletePlayedDownloads),
            endBufferSeconds = sp.getInt("endBufferSeconds", d.endBufferSeconds),
            downloadEnqueues = sp.getBoolean("downloadEnqueues", d.downloadEnqueues),
            queueDownloads = sp.getBoolean("queueDownloads", d.queueDownloads),
            lockScreenSeek = sp.getBoolean("lockScreenSeek", d.lockScreenSeek),
            gpodderSync = sp.getBoolean("gpodderSync", d.gpodderSync),
            gpodderStatus = sp.getString("gpodderStatus", d.gpodderStatus),
        )
    }

    @Synchronized
    fun update(transform: (Prefs) -> Prefs) {
        val p = transform(_flow.value)
        sp.edit()
            .putBoolean("autoUpdate", p.autoUpdate)
            .putInt("intervalMinutes", p.intervalMinutes)
            .putBoolean("wifiOnly", p.wifiOnly)
            .putBoolean("markReadOnScroll", p.markReadOnScroll)
            .putBoolean("unreadOnly", p.unreadOnly)
            .putBoolean("newestFirst", p.newestFirst)
            .putInt("retentionDays", p.retentionDays)
            .putInt("hideReadAfterMinutes", p.hideReadAfterMinutes)
            .putBoolean("notifyNewArticles", p.notifyNewArticles)
            .putInt("widgetIntervalMinutes", p.widgetIntervalMinutes)
            .putString("ncServer", p.ncServer)
            .putString("ncUser", p.ncUser)
            .putString("ncFolder", p.ncFolder)
            .putString("ncDavUser", p.ncDavUser)
            .putString("ncDavUserFor", p.ncDavUserFor)
            .putBoolean("ncAutoSync", p.ncAutoSync)
            .putLong("lastSync", p.lastSync)
            .putString("lastSyncError", p.lastSyncError)
            .putFloat("playbackSpeed", p.playbackSpeed)
            .putBoolean("downloadWifiOnly", p.downloadWifiOnly)
            .putBoolean("deletePlayedDownloads", p.deletePlayedDownloads)
            .putInt("endBufferSeconds", p.endBufferSeconds)
            .putBoolean("downloadEnqueues", p.downloadEnqueues)
            .putBoolean("queueDownloads", p.queueDownloads)
            .putBoolean("lockScreenSeek", p.lockScreenSeek)
            .putBoolean("gpodderSync", p.gpodderSync)
            .putString("gpodderStatus", p.gpodderStatus)
            .apply()
        _flow.value = p
    }

    var ncPassword: String
        get() = secrets.get("ncPassword").orEmpty()
        set(value) = secrets.put("ncPassword", value)
}

/** Stores small secrets encrypted with a key that never leaves the Android Keystore. */
class SecretStore(context: Context) {
    private val sp = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    fun put(name: String, value: String) {
        if (value.isEmpty()) {
            sp.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(value.toByteArray())
        sp.edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val stored = sp.getString(name, null) ?: return null
        return runCatching {
            val data = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_LENGTH))
            String(cipher.doFinal(data, IV_LENGTH, data.size - IV_LENGTH))
        }.getOrNull()
    }

    private companion object {
        const val ALIAS = "news_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
