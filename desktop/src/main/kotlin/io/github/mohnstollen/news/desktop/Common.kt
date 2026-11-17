package io.github.mohnstollen.news.desktop

import androidx.compose.foundation.DarkDefaultContextMenuRepresentation
import androidx.compose.foundation.LightDefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val Light = lightColorScheme(
    primary = Color(0xFFB33F0B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCE),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondaryContainer = Color(0xFFF7DED4),
    onSecondaryContainer = Color(0xFF2C160D),
    surface = Color(0xFFFFFBFF),
    surfaceContainerLow = Color(0xFFFBF1EC),
    surfaceContainer = Color(0xFFF7EBE6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB59A),
    onPrimary = Color(0xFF5C1900),
    primaryContainer = Color(0xFF822800),
    onPrimaryContainer = Color(0xFFFFDBCE),
    secondaryContainer = Color(0xFF5D4036),
    onSecondaryContainer = Color(0xFFFFDBCE),
    surface = Color(0xFF1A1110),
    surfaceContainerLow = Color(0xFF231917),
    surfaceContainer = Color(0xFF271D1B),
)

/**
 * Material colors plus the desktop-only parts that do not follow MaterialTheme by themselves:
 * the right-click menus and the scroll bars.
 */
@Composable
fun NewsTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) Dark else Light
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(
            LocalContextMenuRepresentation provides
                if (dark) DarkDefaultContextMenuRepresentation else LightDefaultContextMenuRepresentation,
            LocalScrollbarStyle provides defaultScrollbarStyle().copy(
                unhoverColor = colors.onSurface.copy(alpha = 0.2f),
                hoverColor = colors.onSurface.copy(alpha = 0.45f),
            ),
            content = content,
        )
    }
}

/**
 * Whether the desktop uses a dark appearance. Compose cannot tell on most Linux desktops, so this
 * asks the desktop settings: the GNOME/Cinnamon color scheme, the GTK theme (GNOME, Xfce, MATE,
 * Cinnamon) and the KDE color scheme. A theme counts as dark if its name says so.
 */
object SystemTheme {
    private fun run(vararg command: String): String? = runCatching {
        val proc = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = proc.inputStream.readAllBytes().toString(Charsets.UTF_8)
        if (proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0) out.trim() else null
    }.getOrNull()

    private fun kdeColorScheme(): String? {
        val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config")
        val file = java.io.File(config, "kdeglobals")
        if (!file.exists()) return null
        return runCatching { file.readLines().firstOrNull { it.startsWith("ColorScheme=") }?.substringAfter('=') }.getOrNull()
    }

    /** null if nothing could be found out. Blocking; call off the UI thread. */
    fun isDark(): Boolean? {
        val desktop = System.getenv("XDG_CURRENT_DESKTOP").orEmpty().uppercase()
        val hints = buildList {
            System.getenv("GTK_THEME")?.let(::add)
            if ("KDE" in desktop) kdeColorScheme()?.let(::add)
            if ("XFCE" in desktop) run("xfconf-query", "-c", "xsettings", "-p", "/Net/ThemeName")?.let(::add)
            if ("MATE" in desktop) run("gsettings", "get", "org.mate.interface", "gtk-theme")?.let(::add)
            run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")?.let(::add)
            run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")?.let(::add)
        }
        if (hints.isEmpty()) return null
        return hints.any { it.contains("dark", ignoreCase = true) }
    }
}

/** "vor 5 Minuten", like Android's DateUtils.getRelativeTimeSpanString. */
fun relativeTime(time: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - time
    val minutes = diff / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        diff < 0 -> date(time)
        minutes < 1 -> "gerade eben"
        minutes == 1L -> "vor 1 Minute"
        minutes < 60 -> "vor $minutes Minuten"
        hours == 1L -> "vor 1 Stunde"
        hours < 24 -> "vor $hours Stunden"
        days == 1L -> "gestern"
        days < 7 -> "vor $days Tagen"
        else -> date(time)
    }
}

private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.GERMANY)
private val dateTimeFormat =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.GERMANY)

fun date(time: Long): String = dateFormat.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
fun dateTime(time: Long): String = dateTimeFormat.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

@Composable
fun CountBadge(count: Int) {
    if (count > 0) Text(if (count > 9999) "9999+" else count.toString(), style = MaterialTheme.typography.labelMedium)
}

@Composable
fun CheckMenuItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = {
            if (checked) Icon(Icons.Default.Check, contentDescription = "aktiv") else Spacer(Modifier.size(24.dp))
        },
    )
}

/** The app icon (RSS waves), drawn as a vector so that it is sharp in tray and title bar. */
object AppIcons {
    val Logo: ImageVector by lazy {
        ImageVector.Builder("Logo", 108.dp, 108.dp, 108f, 108f)
            .addPath(
                pathData = addPathNodes("M24,8h60a16,16 0,0 1,16 16v60a16,16 0,0 1,-16 16h-60a16,16 0,0 1,-16 -16v-60a16,16 0,0 1,16 -16z"),
                fill = SolidColor(Color(0xFFE8590C)),
            )
            .addPath(pathData = addPathNodes("M39,69m-6,0a6,6 0,1 1,12 0a6,6 0,1 1,-12 0"), fill = SolidColor(Color.White))
            .addPath(pathData = addPathNodes("M33,47v8c11,0 20,9 20,20h8C61,59.5 48.5,47 33,47z"), fill = SolidColor(Color.White))
            .addPath(pathData = addPathNodes("M33,33v8c18.8,0 34,15.2 34,34h8C75,51.8 56.2,33 33,33z"), fill = SolidColor(Color.White))
            .build()
    }
}
