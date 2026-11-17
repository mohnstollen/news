package io.github.mohnstollen.news.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mohnstollen.news.data.DEFAULT_INTERVAL_MINUTES
import io.github.mohnstollen.news.data.MIN_INTERVAL_MINUTES
import io.github.mohnstollen.news.data.Prefs
import io.github.mohnstollen.news.data.ReadVisibility
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings = container.settings
    val prefs by settings.flow.collectAsState()

    /** The background updater follows these values by itself. */
    fun updateSchedule(transform: (Prefs) -> Prefs) = settings.update(transform)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 760.dp)) {
                SectionTitle("Aktualisierung")
                SwitchRow(
                    title = "Automatisch aktualisieren",
                    subtitle = "Solange die App läuft, auch im Systembereich der Leiste. Ausgeschaltet werden Feeds nur manuell geladen.",
                    checked = prefs.autoUpdate,
                    onChange = { value -> updateSchedule { it.copy(autoUpdate = value) } },
                )
                NumberField(
                    label = "Intervall in Minuten",
                    value = prefs.intervalMinutes,
                    enabled = prefs.autoUpdate,
                    hint = "Mindestens $MIN_INTERVAL_MINUTES Minuten, Standard $DEFAULT_INTERVAL_MINUTES.",
                    isValid = { it >= MIN_INTERVAL_MINUTES },
                    onValid = { value -> updateSchedule { it.copy(intervalMinutes = value) } },
                )
                SwitchRow(
                    title = "Bei neuen Artikeln benachrichtigen",
                    subtitle = if (!prefs.autoUpdate) "Benachrichtigt wird nur nach automatischen Updates."
                    else "Nach einem automatischen Update, über die Desktop-Benachrichtigungen (notify-send). " +
                        "Einzelne Feeds lassen sich in der Feed-Verwaltung ausnehmen.",
                    checked = prefs.notifyNewArticles,
                    enabled = prefs.autoUpdate,
                    onChange = { value -> settings.update { it.copy(notifyNewArticles = value) } },
                )
                SwitchRow(
                    title = "Beim Schließen im Systembereich weiterlaufen",
                    subtitle = "Das Fenster verschwindet in die Leiste, Updates und Benachrichtigungen laufen weiter. " +
                        "Beenden über das Symbol in der Leiste oder mit Strg+Q.",
                    checked = prefs.keepInTray,
                    onChange = { value -> settings.update { it.copy(keepInTray = value) } },
                )
                var autostart by remember { mutableStateOf(Autostart.enabled) }
                SwitchRow(
                    title = "Beim Anmelden starten",
                    subtitle = if (Autostart.launcher == null) "Nur in der installierten App verfügbar."
                    else "Startet unsichtbar im Systembereich, damit Updates und Benachrichtigungen laufen.",
                    checked = autostart,
                    enabled = Autostart.launcher != null,
                    onChange = { value -> Autostart.set(value); autostart = Autostart.enabled },
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("Lesen")
                SwitchRow(
                    title = "Beim Scrollen als gelesen markieren",
                    subtitle = "Artikel, an denen du in der Liste vorbeiscrollst, gelten als gelesen.",
                    checked = prefs.markReadOnScroll,
                    onChange = { value -> settings.update { it.copy(markReadOnScroll = value) } },
                )
                HideReadField(
                    minutes = prefs.hideReadAfterMinutes,
                    onChange = { value -> settings.update { it.copy(hideReadAfterMinutes = value) } },
                )
                NumberField(
                    label = "Gelesene Artikel löschen nach (Tagen)",
                    value = prefs.retentionDays,
                    hint = "0 = nie löschen. Gelöscht wird, sobald der Feed den Artikel nicht mehr führt. " +
                        "Gemerkte Artikel und Artikel mit Notiz bleiben immer erhalten.",
                    isValid = { it >= 0 },
                    onValid = { value -> settings.update { it.copy(retentionDays = value) } },
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("Nextcloud-Synchronisation")
                NextcloudSection(
                    prefs = prefs,
                    initialPassword = remember { settings.ncPassword },
                    onSave = { server, user, password, folder ->
                        settings.ncPassword = password
                        settings.update {
                            it.copy(ncServer = server.trim(), ncUser = user.trim(), ncFolder = folder.trim().ifBlank { "News" })
                        }
                    },
                    onSync = {
                        val added = container.sync.sync()
                        if (added > 0) container.repository.scope.launch { container.repository.refreshAll() }
                        added
                    },
                )
                SwitchRow(
                    title = "Bei jedem automatischen Update synchronisieren",
                    subtitle = null,
                    checked = prefs.ncAutoSync,
                    enabled = prefs.syncConfigured,
                    onChange = { value -> settings.update { it.copy(ncAutoSync = value) } },
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

private val hideReadOptions = listOf(
    0 to "Sofort",
    60 to "Nach 1 Stunde",
    24 * 60 to "Nach 1 Tag",
    7 * 24 * 60 to "Nach 1 Woche",
    ReadVisibility.NEVER to "Nie",
)

/** When read articles leave the article lists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HideReadField(minutes: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = hideReadOptions.firstOrNull { it.first == minutes }?.second ?: "Nach $minutes Minuten"
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Gelesene Artikel ausblenden") },
            supportingText = {
                Text(
                    "Gelesene Artikel verschwinden aus den Listen. Über die Suche und unter „Gemerkt“ " +
                        "bleiben sie auffindbar.",
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            hideReadOptions.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onChange(value); expanded = false })
            }
        }
    }
}

@Composable
private fun NextcloudSection(
    prefs: Prefs,
    initialPassword: String,
    onSave: (server: String, user: String, password: String, folder: String) -> Unit,
    onSync: suspend () -> Int,
) {
    var server by remember { mutableStateOf(prefs.ncServer) }
    var user by remember { mutableStateOf(prefs.ncUser) }
    var password by remember { mutableStateOf(initialPassword) }
    var folder by remember { mutableStateOf(prefs.ncFolder) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fieldModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)

    Text(
        "Gleicht abonnierte Feeds, gemerkte Artikel und Notizen über eine Datei in deiner Nextcloud ab. " +
            "Der Gelesen-Status wird nicht synchronisiert.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    OutlinedTextField(
        value = server,
        onValueChange = { server = it },
        label = { Text("Server-Adresse") },
        placeholder = { Text("https://cloud.beispiel.de") },
        singleLine = true,
        modifier = fieldModifier,
    )
    OutlinedTextField(
        value = user,
        onValueChange = { user = it },
        label = { Text("Benutzername") },
        singleLine = true,
        modifier = fieldModifier,
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("App-Passwort") },
        supportingText = { Text("In Nextcloud unter Einstellungen → Sicherheit → Geräte & Sitzungen erstellen.") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = fieldModifier,
    )
    OutlinedTextField(
        value = folder,
        onValueChange = { folder = it },
        label = { Text("Ordner in der Cloud") },
        singleLine = true,
        modifier = fieldModifier,
    )
    if (server.trim().startsWith("http://", ignoreCase = true)) {
        Text(
            "Achtung: Über http:// wird das Passwort unverschlüsselt übertragen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            enabled = !busy && server.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
            onClick = {
                onSave(server, user, password, folder)
                busy = true
                result = null
                scope.launch {
                    result = try {
                        val added = onSync()
                        when (added) {
                            0 -> "Synchronisiert."
                            1 -> "Synchronisiert, 1 Feed hinzugefügt."
                            else -> "Synchronisiert, $added Feeds hinzugefügt."
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.message ?: e.javaClass.simpleName
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text("Speichern & jetzt synchronisieren") }
        if (busy) {
            Spacer(Modifier.size(12.dp))
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
    val status = result
        ?: prefs.lastSyncError?.let { "Letzter Versuch fehlgeschlagen: $it" }
        ?: if (prefs.lastSync > 0) {
            "Zuletzt synchronisiert " + relativeTime(prefs.lastSync)
        } else null
    if (status != null) {
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (enabled) 1f else 0.5f
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A number input that saves each valid value right away and flags invalid ones. */
@Composable
private fun NumberField(
    label: String,
    value: Int,
    hint: String,
    enabled: Boolean = true,
    isValid: (Int) -> Boolean,
    onValid: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && isValid(parsed)
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.filter(Char::isDigit).take(6)
            text.toIntOrNull()?.takeIf(isValid)?.let(onValid)
        },
        label = { Text(label) },
        supportingText = { Text(hint) },
        isError = !valid,
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
