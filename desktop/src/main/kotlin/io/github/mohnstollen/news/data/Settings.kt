package io.github.mohnstollen.news.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.concurrent.TimeUnit

const val MIN_INTERVAL_MINUTES = 15
const val DEFAULT_INTERVAL_MINUTES = 60

/** Same settings as on Android, minus the ones that only make sense on a phone (Wi-Fi, widget). */
data class Prefs(
    val autoUpdate: Boolean = true,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    val markReadOnScroll: Boolean = true,
    val unreadOnly: Boolean = false,
    val newestFirst: Boolean = true,
    /** Read articles older than this are removed once their feed no longer lists them. 0 = keep. */
    val retentionDays: Int = 90,
    /** Minutes a read article stays in the lists; see [ReadVisibility]. */
    val hideReadAfterMinutes: Int = 24 * 60,
    /** Announce articles found by a background update. */
    val notifyNewArticles: Boolean = false,
    /** Closing the window keeps the app running in the system tray, so updates go on. */
    val keepInTray: Boolean = true,
    val ncServer: String = "",
    val ncUser: String = "",
    val ncFolder: String = "News",
    val ncAutoSync: Boolean = true,
    val lastSync: Long = 0,
    val lastSyncError: String? = null,
) {
    val syncConfigured: Boolean get() = ncServer.isNotBlank() && ncUser.isNotBlank()
}

/** Where the app keeps its files, following the XDG base directory spec. */
object AppDirs {
    private const val NAME = "news-reader"
    private fun xdg(variable: String, fallback: String): File =
        File(System.getenv(variable)?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/" + fallback))

    val config: File get() = File(xdg("XDG_CONFIG_HOME", ".config"), NAME).also { it.mkdirs() }
    val data: File get() = File(xdg("XDG_DATA_HOME", ".local/share"), NAME).also { it.mkdirs() }
}

/** Settings in a properties file. Thread-safe; [flow] tells the UI about changes. */
class SettingsStore(
    private val file: File = File(AppDirs.config, "settings.properties"),
    useKeyring: Boolean = true,
) {
    private val secrets = SecretStore(File(file.parentFile, "secrets.properties"), useKeyring)

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Prefs> = _flow

    val current: Prefs get() = _flow.value

    private fun load(): Prefs {
        val p = Properties()
        if (file.exists()) runCatching { file.inputStream().use(p::load) }
        val d = Prefs()
        fun bool(k: String, def: Boolean) = p.getProperty(k)?.toBooleanStrictOrNull() ?: def
        fun int(k: String, def: Int) = p.getProperty(k)?.toIntOrNull() ?: def
        return Prefs(
            autoUpdate = bool("autoUpdate", d.autoUpdate),
            intervalMinutes = int("intervalMinutes", d.intervalMinutes),
            markReadOnScroll = bool("markReadOnScroll", d.markReadOnScroll),
            unreadOnly = bool("unreadOnly", d.unreadOnly),
            newestFirst = bool("newestFirst", d.newestFirst),
            retentionDays = int("retentionDays", d.retentionDays),
            hideReadAfterMinutes = int("hideReadAfterMinutes", d.hideReadAfterMinutes),
            notifyNewArticles = bool("notifyNewArticles", d.notifyNewArticles),
            keepInTray = bool("keepInTray", d.keepInTray),
            ncServer = p.getProperty("ncServer") ?: d.ncServer,
            ncUser = p.getProperty("ncUser") ?: d.ncUser,
            ncFolder = p.getProperty("ncFolder") ?: d.ncFolder,
            ncAutoSync = bool("ncAutoSync", d.ncAutoSync),
            lastSync = p.getProperty("lastSync")?.toLongOrNull() ?: d.lastSync,
            lastSyncError = p.getProperty("lastSyncError"),
        )
    }

    @Synchronized
    fun update(transform: (Prefs) -> Prefs) {
        val v = transform(_flow.value)
        val p = Properties()
        p["autoUpdate"] = v.autoUpdate.toString()
        p["intervalMinutes"] = v.intervalMinutes.toString()
        p["markReadOnScroll"] = v.markReadOnScroll.toString()
        p["unreadOnly"] = v.unreadOnly.toString()
        p["newestFirst"] = v.newestFirst.toString()
        p["retentionDays"] = v.retentionDays.toString()
        p["hideReadAfterMinutes"] = v.hideReadAfterMinutes.toString()
        p["notifyNewArticles"] = v.notifyNewArticles.toString()
        p["keepInTray"] = v.keepInTray.toString()
        p["ncServer"] = v.ncServer
        p["ncUser"] = v.ncUser
        p["ncFolder"] = v.ncFolder
        p["ncAutoSync"] = v.ncAutoSync.toString()
        p["lastSync"] = v.lastSync.toString()
        v.lastSyncError?.let { p["lastSyncError"] = it }
        writeAtomically(file) { out -> p.store(out, "News") }
        _flow.value = v
    }

    var ncPassword: String
        get() = secrets.get("ncPassword").orEmpty()
        set(value) = secrets.put("ncPassword", value)
}

/**
 * Small secrets. Uses the desktop keyring (Secret Service, via `secret-tool` from libsecret) when
 * it is available; otherwise a file that only the user can read.
 */
class SecretStore(private val file: File, useKeyring: Boolean = true) {
    private val keyring: Boolean by lazy {
        useKeyring && runCatching {
            ProcessBuilder("secret-tool", "--version").redirectErrorStream(true).start()
                .apply { inputStream.readAllBytes() }.waitFor(5, TimeUnit.SECONDS)
        }.getOrDefault(false)
    }

    @Synchronized
    fun put(name: String, value: String) {
        if (keyring && runKeyring(name, value)) {
            removeFromFile(name)
            return
        }
        val p = readFile()
        if (value.isEmpty()) p.remove(name) else p[name] = value
        writeAtomically(file, private = true) { out -> p.store(out, null) }
    }

    @Synchronized
    fun get(name: String): String? {
        if (keyring) {
            val fromKeyring = runCatching {
                val proc = ProcessBuilder("secret-tool", "lookup", "application", APP, "name", name).start()
                val out = proc.inputStream.readAllBytes().toString(Charsets.UTF_8)
                if (proc.waitFor(10, TimeUnit.SECONDS) && proc.exitValue() == 0) out else null
            }.getOrNull()
            if (!fromKeyring.isNullOrEmpty()) return fromKeyring
        }
        return readFile().getProperty(name)
    }

    /** Stores or clears the value. Returns false if the keyring could not be used. */
    private fun runKeyring(name: String, value: String): Boolean = runCatching {
        val proc = if (value.isEmpty()) {
            ProcessBuilder("secret-tool", "clear", "application", APP, "name", name).start()
        } else {
            ProcessBuilder("secret-tool", "store", "--label=News: $name", "application", APP, "name", name).start()
                .also { p -> p.outputStream.use { it.write(value.toByteArray()) } }
        }
        proc.inputStream.readAllBytes()
        proc.waitFor(30, TimeUnit.SECONDS) && proc.exitValue() == 0
    }.getOrDefault(false)

    private fun readFile(): Properties = Properties().apply {
        if (file.exists()) runCatching { file.inputStream().use(::load) }
    }

    private fun removeFromFile(name: String) {
        val p = readFile()
        if (p.remove(name) != null) writeAtomically(file, private = true) { out -> p.store(out, null) }
    }

    private companion object {
        const val APP = "news-reader"
    }
}

private fun writeAtomically(target: File, private: Boolean = false, write: (java.io.OutputStream) -> Unit) {
    target.parentFile.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    if (private) {
        tmp.delete()
        runCatching {
            Files.createFile(tmp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }
    }
    tmp.outputStream().use(write)
    Files.move(
        tmp.toPath(), target.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
    )
}
