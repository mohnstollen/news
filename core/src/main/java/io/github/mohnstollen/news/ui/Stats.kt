package io.github.mohnstollen.news.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mohnstollen.news.data.ListenTotals
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Start days of the periods the statistics show, as days since 1970-01-01. */
data class StatsPeriods(val week: Long, val month: Long, val year: Long, val last7: Long) {
    companion object {
        fun of(today: LocalDate) = StatsPeriods(
            week = today.with(DayOfWeek.MONDAY).toEpochDay(),
            month = today.withDayOfMonth(1).toEpochDay(),
            year = today.withDayOfYear(1).toEpochDay(),
            last7 = today.minusDays(6).toEpochDay(),
        )

        /** "203 Std 12 Min", "45 Min", "0 Min". */
        fun duration(ms: Long): String {
            val minutes = ms / 60_000
            val hours = minutes / 60
            return when {
                hours == 0L -> "$minutes Min"
                minutes % 60 == 0L -> "$hours Std"
                else -> "$hours Std ${minutes % 60} Min"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val dao = LocalContext.current.container.repository.listenDao
    val periods = remember { StatsPeriods.of(LocalDate.now()) }
    val zero = ListenTotals(0, 0, 0, 0)
    val week by remember { dao.observeTotals(periods.week) }.collectAsStateWithLifecycle(zero)
    val month by remember { dao.observeTotals(periods.month) }.collectAsStateWithLifecycle(zero)
    val year by remember { dao.observeTotals(periods.year) }.collectAsStateWithLifecycle(zero)
    val all by remember { dao.observeTotals(0) }.collectAsStateWithLifecycle(zero)
    val topYear by remember { dao.observeTopFeeds(periods.year, 5) }.collectAsStateWithLifecycle(emptyList())
    val topAll by remember { dao.observeTopFeeds(0, 5) }.collectAsStateWithLifecycle(emptyList())
    val days by remember { dao.observeDays(periods.last7) }.collectAsStateWithLifecycle(emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistik") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (all.listenedMs == 0L) {
                Text(
                    "Noch nichts gehört. Sobald du Podcasts abspielst, steht hier, wie viel und was du hörst.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Diese Woche", StatsPeriods.duration(week.listenedMs), Modifier.weight(1f))
                StatTile("Dieser Monat", StatsPeriods.duration(month.listenedMs), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Dieses Jahr", StatsPeriods.duration(year.listenedMs), Modifier.weight(1f))
                StatTile("Insgesamt", StatsPeriods.duration(all.listenedMs), Modifier.weight(1f))
            }

            // This year's favourite; early in January the overall one.
            val top = topYear.ifEmpty { topAll }
            top.firstOrNull()?.let { favourite ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            if (topYear.isNotEmpty()) "Dein Lieblingspodcast dieses Jahr" else "Dein Lieblingspodcast",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            favourite.feedTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            StatsPeriods.duration(favourite.listenedMs) + " · " +
                                if (favourite.episodes == 1) "1 Folge" else "${favourite.episodes} Folgen",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            if (top.size > 1) {
                Section(if (topYear.isNotEmpty()) "Meistgehört dieses Jahr" else "Meistgehört") {
                    val max = top.first().listenedMs.coerceAtLeast(1)
                    top.forEach { feed ->
                        HorizontalBar(feed.feedTitle, StatsPeriods.duration(feed.listenedMs), feed.listenedMs.toFloat() / max)
                    }
                }
            }

            Section("Letzte 7 Tage") {
                val today = LocalDate.now()
                val byDay = days.associate { it.day to it.listenedMs }
                val values = (6 downTo 0).map { today.minusDays(it.toLong()) }.map { it to (byDay[it.toEpochDay()] ?: 0L) }
                val max = values.maxOf { it.second }.coerceAtLeast(1)
                Row(
                    Modifier.fillMaxWidth().height(120.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    values.forEach { (date, ms) ->
                        val day = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN)
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clearAndSetSemantics {
                                contentDescription = "$day: ${StatsPeriods.duration(ms)}"
                            },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Box(
                                Modifier.fillMaxWidth(0.6f)
                                    .fillMaxHeight((ms.toFloat() / max * 0.8f).coerceAtLeast(0.01f))
                                    .background(
                                        if (ms > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                                    ),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(day, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Section("Dieses Jahr") {
                Fact("Folgen zu Ende gehört", year.finished.toString())
                Fact("Verschiedene Folgen angehört", year.episodes.toString())
                val saved = year.contentMs - year.listenedMs
                if (saved > 60_000) Fact("Durch höhere Geschwindigkeit gespart", StatsPeriods.duration(saved))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

/** One podcast: name and value as text, the bar only shows the proportion. */
@Composable
private fun HorizontalBar(label: String, value: String, fraction: Float) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(8.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
