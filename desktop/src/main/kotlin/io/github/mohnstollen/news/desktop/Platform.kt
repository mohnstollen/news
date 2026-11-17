package io.github.mohnstollen.news.desktop

import io.github.mohnstollen.news.data.ArticleListItem
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/** Linux desktop integration through the usual freedesktop tools, with Java fallbacks. */
object Platform {

    private fun run(vararg command: String, timeoutSeconds: Long = 5): Pair<Int, String>? = runCatching {
        val proc = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = proc.inputStream.readAllBytes().toString(Charsets.UTF_8)
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroy()
            null
        } else proc.exitValue() to out
    }.getOrNull()

    private fun has(tool: String): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, tool).canExecute() }

    /** Opens a web address in the default browser. Returns false if that was not possible. */
    fun openInBrowser(url: String): Boolean {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
        if (has("xdg-open")) {
            runCatching { ProcessBuilder("xdg-open", url).start() }.onSuccess { return true }
        }
        return runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url)); true
            } else false
        }.getOrDefault(false)
    }

    fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    /** Desktop notification via `notify-send`; returns false if it is not installed. */
    fun notify(title: String, body: String): Boolean {
        if (!has("notify-send")) return false
        return run("notify-send", "--app-name=News", "--icon=news-reader", title, body)?.first == 0
    }

    /** Text of the "new articles" notification; mirrors the Android notification. */
    fun newArticlesNotification(articles: List<ArticleListItem>): Pair<String, String> {
        val single = articles.singleOrNull()
        if (single != null) return single.title to single.feedTitle
        val lines = articles.take(5).joinToString("\n") { "${it.feedTitle}: ${it.title}" }
        val more = if (articles.size > 5) "\nund ${articles.size - 5} weitere" else ""
        return "${articles.size} neue Artikel" to lines + more
    }

    /** Native file chooser: zenity (GNOME) or kdialog (KDE), otherwise the AWT dialog. */
    fun chooseFile(title: String, save: Boolean, suggestedName: String? = null, parent: Frame? = null): File? {
        val home = System.getProperty("user.home")
        if (has("zenity")) {
            val args = mutableListOf("zenity", "--file-selection", "--title=$title")
            if (save) {
                args += listOf("--save", "--confirm-overwrite")
                if (suggestedName != null) args += "--filename=$home/$suggestedName"
            } else {
                args += listOf("--file-filter=OPML-Dateien | *.opml *.xml", "--file-filter=Alle Dateien | *")
            }
            run(*args.toTypedArray(), timeoutSeconds = 3600)?.let { (code, out) ->
                return if (code == 0) out.trim().takeIf { it.isNotEmpty() }?.let(::File) else null
            }
        }
        if (has("kdialog")) {
            val args = if (save) listOf("kdialog", "--title", title, "--getsavefilename", "$home/${suggestedName.orEmpty()}")
            else listOf("kdialog", "--title", title, "--getopenfilename", home, "*.opml *.xml|OPML-Dateien")
            run(*args.toTypedArray(), timeoutSeconds = 3600)?.let { (code, out) ->
                return if (code == 0) out.trim().takeIf { it.isNotEmpty() }?.let(::File) else null
            }
        }
        val dialog = FileDialog(parent, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
        if (suggestedName != null) dialog.file = suggestedName
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name)
    }
}

/** Start at login, hidden in the tray, through an XDG autostart entry. */
object Autostart {
    /** Path of the installed launcher; null when running from Gradle, where autostart makes no sense. */
    val launcher: String? = System.getProperty("jpackage.app-path")?.takeIf { File(it).canExecute() }

    private val file: File
        get() {
            val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.config")
            return File(config, "autostart/news-reader.desktop")
        }

    val enabled: Boolean get() = file.exists()

    fun set(enabled: Boolean) {
        val exec = launcher ?: return
        if (!enabled) {
            file.delete()
            return
        }
        file.parentFile.mkdirs()
        file.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=News
            Comment=RSS- und Atom-Reader, im Hintergrund
            Exec="$exec" --hidden
            Icon=news-reader
            X-GNOME-Autostart-enabled=true
            """.trimIndent() + "\n",
        )
    }
}
