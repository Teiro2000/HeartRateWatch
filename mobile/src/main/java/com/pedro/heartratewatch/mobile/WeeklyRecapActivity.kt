package com.pedro.heartratewatch.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.mobile.theme.PipBoyTheme
import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.DistanceUnit
import com.pedro.heartratewatch.shared.TrainingSettings
import com.pedro.heartratewatch.shared.formatDistance
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A look back at a week of cardio and workouts, with last week beside it for comparison. */
class WeeklyRecapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PipBoyTheme { WeeklyRecapScreen() } }
    }
}

@Composable
private fun WeeklyRecapScreen() {
    val context = LocalContext.current
    val app = context.applicationContext
    val runs by remember { RunHistoryRepository(app).historyFlow }.collectAsStateWithLifecycle(initialValue = emptyList())
    val workouts by remember { WorkoutRepository(app).historyFlow }.collectAsStateWithLifecycle(initialValue = emptyList())
    val records by remember { RecordsRepository(app).eventsFlow }.collectAsStateWithLifecycle(initialValue = emptyList())
    val weightUnit by remember { WorkoutRepository(app).weightUnitFlow }.collectAsStateWithLifecycle(initialValue = WeightUnit.LB)
    val settings by remember { SettingsRepository(app).settingsFlow }.collectAsStateWithLifecycle(initialValue = TrainingSettings())
    val displayOverride by remember { DisplayDistanceUnitRepository(app).overrideFlow }.collectAsStateWithLifecycle(initialValue = null)
    val distanceUnit = displayOverride ?: settings.distanceUnit
    val back = rememberSystemBack()

    val thisMonday = remember { WeeklyRecap.mondayOf(LocalDate.now()) }
    var weeksBack by remember { mutableIntStateOf(0) }
    val weekStart = thisMonday.minusWeeks(weeksBack.toLong())
    val week = remember(runs, workouts, records, weekStart) { WeeklyRecap.summarize(runs, workouts, records, weekStart) }
    val previous = remember(runs, workouts, records, weekStart) { WeeklyRecap.summarize(runs, workouts, records, weekStart.minusWeeks(1)) }
    val trend = remember(runs, weekStart) { WeeklyRecap.heartRateTrend(runs, weekStart) }
    val dayFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                NavButtons(onBack = back, onMainMenu = { goToMainMenu(context) })
                Text(
                    "Weekly Recap",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { weeksBack++ }) { Text("<") }
                Text(
                    if (weeksBack == 0) "This week" else "${week.start.format(dayFormat)} - ${week.end.format(dayFormat)}",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { weeksBack-- }, enabled = weeksBack > 0) { Text(">") }
            }
            HorizontalDivider()

            if (week.activeDays == 0) {
                Text("Nothing logged this week.", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }

            RecapCard("Overview") {
                RecapLine("Active days", "${week.activeDays} of 7", deltaInt(week.activeDays, previous.activeDays, ""))
                RecapLine("XP earned", "${week.xp}", deltaInt(week.xp, previous.xp, " XP"))
            }

            RecapCard("Cardio") {
                RecapLine("Sessions", "${week.cardioSessions}", deltaInt(week.cardioSessions, previous.cardioSessions, ""))
                RecapLine(
                    "Distance",
                    formatDistance(week.cardioMeters.toFloat(), distanceUnit),
                    deltaDistance(week.cardioMeters, previous.cardioMeters, distanceUnit)
                )
                RecapLine("Time moving", hoursMinutes(week.cardioMinutes), deltaInt(week.cardioMinutes, previous.cardioMinutes, " min"))
                ACTIVITY_ORDER.filter { week.cardioMetersByActivity.getValue(it) > 0 }.forEach { type ->
                    RecapLine("  ${type.displayName()}", formatDistance(week.cardioMetersByActivity.getValue(type).toFloat(), distanceUnit), null)
                }
            }

            RecapCard("Workouts") {
                RecapLine("Workouts", "${week.workouts}", deltaInt(week.workouts, previous.workouts, ""))
                RecapLine("Sets", "${week.sets}", deltaInt(week.sets, previous.sets, ""))
                RecapLine(
                    "Weight lifted",
                    "${formatWeightWhole(weightUnit.fromLb(week.liftedLb))} ${weightUnit.symbol}",
                    deltaInt(
                        weightUnit.fromLb(week.liftedLb).roundToInt(),
                        weightUnit.fromLb(previous.liftedLb).roundToInt(),
                        " ${weightUnit.symbol}"
                    )
                )
            }

            RecapCard("Heart rate") {
                if (week.avgBpm == null) {
                    Text("No heart rate readings this week.", style = MaterialTheme.typography.bodySmall)
                } else {
                    RecapLine("Average", "${week.avgBpm} bpm", previous.avgBpm?.let { deltaInt(week.avgBpm, it, " bpm") })
                }
                val points = trend.filter { it.second != null }
                if (points.size >= 2) {
                    val zone = remember { java.time.ZoneId.systemDefault() }
                    LineChart(
                        xMillis = points.map { it.first.atStartOfDay(zone).toInstant().toEpochMilli() },
                        series = listOf(LineSeries(MaterialTheme.colorScheme.primary, points.map { it.second!!.toDouble() }, points.map { false })),
                        selected = null,
                        onSelect = {},
                        xLabel = { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().format(DateTimeFormatter.ofPattern("MMM d")) },
                        yLabel = { "${it.roundToInt()}" }
                    )
                    Text("Average heart rate per week, last 8 weeks.", style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("Do a few weeks of sessions with the watch to see a trend.", style = MaterialTheme.typography.bodySmall)
                }
            }

            RecapCard("Personal bests") {
                if (week.records.isEmpty()) {
                    Text("None this week.", style = MaterialTheme.typography.bodySmall)
                } else {
                    week.records.forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Text("", modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun RecapCard(title: String, content: @Composable () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** A label and value, with how it compares to the week before ("+3") in the colour of its direction. */
@Composable
private fun RecapLine(label: String, value: String, delta: Delta?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (delta != null) {
                Text(
                    delta.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (delta.up) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private data class Delta(val text: String, val up: Boolean)

private fun deltaInt(now: Int, before: Int, suffix: String): Delta? {
    if (now == before) return null
    val diff = now - before
    return Delta((if (diff > 0) "+" else "-") + abs(diff) + suffix + " vs last week", diff > 0)
}

private fun deltaDistance(now: Double, before: Double, unit: DistanceUnit): Delta? {
    val diff = now - before
    if (abs(diff) < 1.0) return null
    return Delta((if (diff > 0) "+" else "-") + formatDistance(abs(diff).toFloat(), unit) + " vs last week", diff > 0)
}

private fun hoursMinutes(minutes: Int) = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

private fun formatWeightWhole(value: Double) = String.format(Locale.US, "%,.0f", value)
