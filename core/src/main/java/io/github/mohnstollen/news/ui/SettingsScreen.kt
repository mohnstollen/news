package io.github.mohnstollen.news.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import io.github.mohnstollen.news.playback.Episodes
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.mohnstollen.news.work.Notifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import android.text.format.Formatter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mohnstollen.news.data.DEFAULT_INTERVAL_MINUTES
import io.github.mohnstollen.news.data.MIN_INTERVAL_MINUTES
import io.github.mohnstollen.news.data.NcCredentials
import io.github.mohnstollen.news.data.NcLogin
import io.github.mohnstollen.news.data.NcLoginFlow
import io.github.mohnstollen.news.data.syncResultText
import io.github.mohnstollen.news.data.Prefs
import io.github.mohnstollen.news.data.ReadVisibility
import io.github.mohnstollen.news.work.RefreshWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val w = LocalContext.current.features
    val context = LocalContext.current
    val container = remember { context.container }
    val settings = container.settings
    val prefs by settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    /** Saves and re-plans the background job, which depends on these values. */
    fun updateSchedule(transform: (Prefs) -> Prefs) {
        settings.update(transform)
        RefreshWorker.schedule(context.applicationContext, settings.current)
    }

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
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
        ) {
            SectionTitle("Aktualisierung")
            SwitchRow(
                title = "Automatisch im Hintergrund aktualisieren",
                subtitle = "Ausgeschaltet werden " + w.pick("Feeds", "Podcasts") + " nur manuell geladen. Das spart Akku und Datenvolumen.",
                checked = prefs.autoUpdate,
                onChange = { value -> updateSchedule { it.copy(autoUpdate = value) } },
            )
            NumberField(
                label = "Intervall in Minuten",
                value = prefs.intervalMinutes,
                enabled = prefs.autoUpdate,
                hint = "Mindestens $MIN_INTERVAL_MINUTES Minuten, Standard $DEFAULT_INTERVAL_MINUTES. " +
                    "Android kann den Zeitpunkt im Energiesparmodus verschieben.",
                isValid = { it >= MIN_INTERVAL_MINUTES },
                onValid = { value -> updateSchedule { it.copy(intervalMinutes = value) } },
            )
            SwitchRow(
                title = "Nur im WLAN",
                subtitle = "Automatische Updates laufen nur in Netzen ohne Volumenbegrenzung.",
                checked = prefs.wifiOnly,
                enabled = prefs.autoUpdate,
                onChange = { value -> updateSchedule { it.copy(wifiOnly = value) } },
            )

            NotificationRow(
                enabled = prefs.notifyNewArticles,
                autoUpdate = prefs.autoUpdate,
                onChange = { value -> settings.update { it.copy(notifyNewArticles = value) } },
            )

            // Podfang has no widget.
            if (!w.podcastWords) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (!w.podcastWords) SectionTitle("Widget")
            if (!w.podcastWords) NumberField(
                label = "Widget-Intervall in Minuten",
                value = prefs.widgetIntervalMinutes,
                hint = "So oft lädt ein Widget auf dem Startbildschirm die Feeds und zeigt die neuesten " +
                    "ungelesenen Artikel. Mindestens $MIN_INTERVAL_MINUTES Minuten; gilt auch, wenn die automatische " +
                    "Aktualisierung aus ist. Hinzufügen: Startbildschirm lange drücken, „Widgets“, „News“.",
                isValid = { it >= MIN_INTERVAL_MINUTES },
                onValid = { value -> updateSchedule { it.copy(widgetIntervalMinutes = value) } },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle(w.pick("Lesen", "Liste"))
            SwitchRow(
                title = w.pick("Beim Scrollen als gelesen markieren", "Beim Scrollen als erledigt markieren"),
                subtitle = w.pick(
                    "Artikel, an denen du in der Liste vorbeiscrollst, gelten als gelesen.",
                    "Folgen, an denen du in der Liste vorbeiscrollst, gelten als erledigt.",
                ),
                checked = prefs.markReadOnScroll,
                onChange = { value -> settings.update { it.copy(markReadOnScroll = value) } },
            )
            HideReadField(
                minutes = prefs.hideReadAfterMinutes,
                onChange = { value -> settings.update { it.copy(hideReadAfterMinutes = value) } },
            )
            NumberField(
                label = w.pick("Gelesene Artikel löschen nach (Tagen)", "Erledigte Folgen löschen nach (Tagen)"),
                value = prefs.retentionDays,
                hint = w.pick(
                    "0 = nie löschen. Gelöscht wird, sobald der Feed den Artikel nicht mehr führt. " +
                        "Gemerkte Artikel und Artikel mit Notiz bleiben immer erhalten.",
                    "0 = nie löschen. Gelöscht wird, sobald der Podcast die Folge nicht mehr führt. " +
                        "Gemerkte, heruntergeladene und Folgen mit Notiz bleiben immer erhalten.",
                ),
                isValid = { it >= 0 },
                onValid = { value -> settings.update { it.copy(retentionDays = value) } },
            )

            if (container.features.podcasts) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("Podcasts")
                PodcastSection(prefs = prefs, onChange = settings::update)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Nextcloud-Synchronisation")
            NextcloudSection(
                prefs = prefs,
                initialPassword = remember { settings.ncPassword },
                onSave = { server, user, password, folder ->
                    settings.ncPassword = password
                    settings.update {
                        it.copy(ncServer = server.trim(), ncUser = user.trim(), ncFolder = folder.trim().ifBlank { settings.defaultFolder })
                    }
                },
                onSync = {
                    val added = container.sync.sync()
                    if (added > 0) container.repository.scope.launch { container.repository.refreshAll() }
                    added
                },
                loginFlow = container.loginFlow,
            )
            SwitchRow(
                title = "Bei jedem Hintergrund-Update synchronisieren",
                subtitle = null,
                checked = prefs.ncAutoSync,
                enabled = prefs.syncConfigured,
                onChange = { value -> settings.update { it.copy(ncAutoSync = value) } },
            )
            Spacer(Modifier.height(32.dp))
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
    val w = LocalContext.current.features
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
            label = { Text(w.pick("Gelesene Artikel ausblenden", "Erledigte Folgen ausblenden")) },
            supportingText = {
                Text(
                    w.pick("Gelesene Artikel", "Erledigte Folgen") + " verschwinden aus den Listen. Über die Suche " +
                        "und unter „Gemerkt“ bleiben sie auffindbar.",
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

/** The switch asks for the notification permission where Android requires one (13 and newer). */
@Composable
private fun NotificationRow(enabled: Boolean, autoUpdate: Boolean, onChange: (Boolean) -> Unit) {
    val w = LocalContext.current.features
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    // Re-read when the screen comes back, e.g. from the system settings.
    var allowed by remember { mutableStateOf(Notifier.canNotify(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = Notifier.canNotify(context)
        onPauseOrDispose {}
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = Notifier.canNotify(context)
        denied = !granted
        onChange(granted)
    }
    SwitchRow(
        title = w.pick("Bei neuen Artikeln benachrichtigen", "Bei neuen Folgen benachrichtigen"),
        subtitle = when {
            !autoUpdate -> "Benachrichtigt wird nur nach automatischen Updates im Hintergrund."
            enabled && !allowed || denied ->
                "Android blockiert die Benachrichtigungen dieser App. Erlaube sie in den System-Einstellungen."
            else -> "Nach einem Hintergrund-Update. " +
                w.pick("Einzelne Feeds lassen sich in der Feed-Verwaltung ausnehmen.", "Einzelne Podcasts lassen sich in der Podcast-Verwaltung ausnehmen.")
        },
        checked = enabled,
        enabled = autoUpdate,
        onChange = { value ->
            denied = false
            if (value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !allowed) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onChange(value)
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NextcloudSection(
    prefs: Prefs,
    initialPassword: String,
    onSave: (server: String, user: String, password: String, folder: String) -> Unit,
    onSync: suspend () -> Int,
    loginFlow: NcLoginFlow,
) {
    var server by rememberSaveable { mutableStateOf(prefs.ncServer) }
    var user by rememberSaveable { mutableStateOf(prefs.ncUser) }
    var password by remember { mutableStateOf(initialPassword) }
    var folder by rememberSaveable { mutableStateOf(prefs.ncFolder) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var startingLogin by remember { mutableStateOf(false) }
    val loginState by loginFlow.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fieldModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)

    fun saveAndSync() {
        onSave(server, user, password, folder)
        busy = true
        result = null
        scope.launch {
            result = try {
                syncResultText(onSync())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    /** Login data from a QR code (scanned or pasted): fill in, save and sync right away. */
    fun apply(credentials: NcCredentials) {
        server = credentials.server
        user = credentials.user
        password = credentials.password
        saveAndSync()
    }

    // Login Flow v2 runs in the app; this screen only shows its progress and result.
    LaunchedEffect(loginState) {
        when (val state = loginState) {
            is NcLoginFlow.State.Syncing -> {
                server = state.credentials.server
                user = state.credentials.user
                password = state.credentials.password
                result = null
            }
            is NcLoginFlow.State.Done -> {
                server = state.credentials.server
                user = state.credentials.user
                password = state.credentials.password
                result = state.message
                loginFlow.acknowledge()
            }
            is NcLoginFlow.State.Failed -> {
                result = state.message
                loginFlow.acknowledge()
            }
            else -> Unit
        }
    }
    val waitingForLogin = loginState as? NcLoginFlow.State.Waiting
    val working = busy || startingLogin || waitingForLogin != null || loginState is NcLoginFlow.State.Syncing

    fun startLogin() {
        startingLogin = true
        result = null
        scope.launch {
            try {
                context.openLoginPage(loginFlow.begin(server, folder))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result = e.message ?: e.javaClass.simpleName
            } finally {
                startingLogin = false
            }
        }
    }

    Text(
        "Gleicht " + LocalContext.current.features.pick("abonnierte Feeds, gemerkte Artikel", "abonnierte Podcasts, gemerkte Folgen") +
            " und Notizen über eine Datei in deiner Nextcloud ab. " +
            LocalContext.current.features.pick("Der Gelesen-Status", "Was erledigt oder gehört ist,") + " wird nicht synchronisiert.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    OutlinedTextField(
        value = server,
        // The text of a login QR code pasted here is taken over directly.
        onValueChange = { text -> NcLogin.parse(text)?.let(::apply) ?: run { server = text } },
        label = { Text("Server-Adresse") },
        placeholder = { Text("https://cloud.beispiel.de") },
        singleLine = true,
        enabled = !working,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = fieldModifier,
    )
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = ::startLogin, enabled = !working && server.isNotBlank()) {
            Text("Mit Nextcloud anmelden")
        }
        OutlinedButton(onClick = { scanning = true }, enabled = !working) {
            Icon(AppIcons.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Mit QR-Code anmelden")
        }
    }
    if (waitingForLogin != null) {
        Text(
            "Bitte im Browser bei Nextcloud anmelden und „Zugriff gewähren“. Danach den Browser schließen – " +
                "hier geht es automatisch weiter.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { context.openLoginPage(waitingForLogin.loginUrl) }) { Text("Seite erneut öffnen") }
            TextButton(onClick = loginFlow::cancel) { Text("Abbrechen") }
        }
    } else {
        Text(
            "„Mit Nextcloud anmelden“ öffnet die Anmeldeseite deiner Nextcloud; die App erhält dann ein eigenes " +
                "App-Passwort. Für den QR-Code in Nextcloud unter Persönliche Einstellungen → Sicherheit ein " +
                "App-Passwort erstellen. Oder Benutzer und App-Passwort unten von Hand eintragen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    if (scanning) {
        QrLoginScanner(
            onScanned = { scanning = false; apply(it) },
            onDismiss = { scanning = false },
        )
    }
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
        supportingText = { Text("Nicht das normale Passwort: In Nextcloud unter Persönliche Einstellungen → Sicherheit erstellen.") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
            enabled = !working && server.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
            onClick = ::saveAndSync,
        ) { Text("Speichern & jetzt synchronisieren") }
        if (busy || startingLogin || loginState is NcLoginFlow.State.Syncing) {
            Spacer(Modifier.size(12.dp))
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
    val status = result
        ?: prefs.lastSyncError?.let { "Letzter Versuch fehlgeschlagen: $it" }
        ?: if (prefs.lastSync > 0) {
            "Zuletzt synchronisiert " + DateUtils.getRelativeTimeSpanString(
                prefs.lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            )
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

/** Opens the Nextcloud login page as a Custom Tab on top of the app (or in the browser). */
private fun Context.openLoginPage(url: String) {
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, Uri.parse(url))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "Kein Browser gefunden", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun PodcastSection(prefs: Prefs, onChange: ((Prefs) -> Prefs) -> Unit) {
    val context = LocalContext.current
    val downloads = context.container.downloads
    val scope = rememberCoroutineScope()
    // Recounted when downloads finish or are deleted.
    val active by downloads.progress.collectAsStateWithLifecycle(emptyMap())
    var usage by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var refreshUsage by remember { mutableStateOf(0) }
    LaunchedEffect(active.size, refreshUsage) { usage = downloads.usage() }
    var confirmDelete by remember { mutableStateOf(false) }

    SwitchRow(
        title = "Downloads nur im WLAN",
        subtitle = "Folgen werden erst geladen, wenn ein Netz ohne Volumenbegrenzung da ist.",
        checked = prefs.downloadWifiOnly,
        onChange = { value -> onChange { it.copy(downloadWifiOnly = value) } },
    )
    SwitchRow(
        title = "Downloads nach dem Hören löschen",
        subtitle = "Die Datei einer Folge wird gelöscht, sobald sie zu Ende gehört oder als gehört markiert ist.",
        checked = prefs.deletePlayedDownloads,
        onChange = { value -> onChange { it.copy(deletePlayedDownloads = value) } },
    )
    EndBufferRow(seconds = prefs.endBufferSeconds, onChange = { value -> onChange { it.copy(endBufferSeconds = value) } })
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        val (count, bytes) = usage ?: (0 to 0L)
        Text(
            text = when (count) {
                0 -> "Keine Folgen heruntergeladen"
                1 -> "1 Folge heruntergeladen · " + Formatter.formatShortFileSize(context, bytes)
                else -> "$count Folgen heruntergeladen · " + Formatter.formatShortFileSize(context, bytes)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (count > 0 || active.isNotEmpty()) TextButton(onClick = { confirmDelete = true }) { Text("Alle löschen") }
    }
    Text(
        "Automatische Downloads schaltest du pro Podcast ein: Podcasts verwalten, Podcast antippen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Alle Downloads löschen?") },
            text = { Text("Die Folgen bleiben in der Liste und lassen sich weiter streamen.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        downloads.deleteAll()
                        refreshUsage++
                    }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

/** How much of the end counts as outro: then the episode counts as played and the rest is skipped. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EndBufferRow(seconds: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("Abspann überspringen")
        Text(
            if (seconds == 0) {
                "Folgen laufen bis zur letzten Sekunde."
            } else {
                "Bleiben nur noch $seconds Sekunden, gilt die Folge als gehört: Der Rest wird übersprungen, " +
                    "der Download gelöscht (wenn oben eingeschaltet) und die nächste Folge der Warteschlange startet."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Episodes.END_BUFFER_CHOICES.forEach { choice ->
                androidx.compose.material3.FilterChip(
                    selected = seconds == choice,
                    onClick = { onChange(choice) },
                    label = { Text(if (choice == 0) "Aus" else "$choice s") },
                )
            }
        }
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
    var text by rememberSaveable { mutableStateOf(value.toString()) }
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
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
