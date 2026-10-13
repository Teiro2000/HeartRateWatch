package com.pedro.heartratewatch.mobile

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.mobile.theme.PipBoyTheme
import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.DistanceUnit
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private val MONTH_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM yy")

/** What the chart shows. More kinds of cardio will be added here as the app learns them. */
private enum class CardioKind(val label: String, val type: ActivityType?) {
    ALL("All cardio", null),
    RUN("Running", ActivityType.RUN),
    WALK("Walking", ActivityType.WALK),
    BIKE("Biking", ActivityType.BIKE),
    STATIONARY_BIKE("Stationary bike", ActivityType.STATIONARY_BIKE)
}

/**
 * Line chart of total distance per month for one kind of cardio (running, stationary biking, or all of it) --
 * in the same style as the workout Progress chart (see [LineChart]): pick the activity from a dropdown,
 * pick a range, tap a month for its numbers, and a ring marks every month that set a new record.
 * Built from run history (see [monthlyTotals]), so it grows automatically as workouts come in; the
 * export button writes the monthly totals as a spreadsheet-friendly CSV (always in km).
 */
class MonthlyChartActivity : ComponentActivity() {

    private val repository by lazy { RunHistoryRepository(applicationContext) }
    private val unitPreferences by lazy { UnitPreferencesRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PipBoyTheme {
                MonthlyChartScreen(repository, unitPreferences)
            }
        }
    }
}

@Composable
private fun MonthlyChartScreen(repository: RunHistoryRepository, unitPreferences: UnitPreferencesRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runs by repository.historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // Reuses the pace unit (km or miles) as the display unit for totals.
    val paceUnit by unitPreferences.paceUnitFlow.collectAsStateWithLifecycle(initialValue = DistanceUnit.KILOMETERS)
    // The unit picked in the main Settings for showing distances beats this default.
    val displayOverride by remember { DisplayDistanceUnitRepository(context.applicationContext).overrideFlow }
        .collectAsStateWithLifecycle(initialValue = null)
    val unit = displayOverride ?: paceUnit
    val totals = remember(runs) { monthlyTotals(runs) }
    val snackbarHostState = remember { SnackbarHostState() }

    var kind by remember { mutableStateOf(CardioKind.RUN) }
    var kindMenuOpen by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf<Pair<java.time.LocalDate, java.time.LocalDate>?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)
                    ?.use { it.write(monthlyTotalsCsv(totals).toByteArray()) }
                    ?: error("Could not open the chosen file for writing")
            }
            snackbarHostState.showSnackbar(if (result.isSuccess) "Exported monthly totals" else "Export failed")
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NavButtons(onBack = rememberSystemBack(), onMainMenu = { goToMainMenu(context) })
            Text("Monthly Distance", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

            if (totals.isEmpty()) {
                Text("No workouts recorded yet.")
                return@Column
            }

            fun display(meters: Double) = meters / unit.metersPerUnit

            val visible = range?.let { (from, to) ->
                totals.filter { it.month >= java.time.YearMonth.from(from) && it.month <= java.time.YearMonth.from(to) }
            } ?: totals

            Box {
                OutlinedButton(onClick = { kindMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(kind.label + "  v")
                }
                DropdownMenu(expanded = kindMenuOpen, onDismissRequest = { kindMenuOpen = false }) {
                    CardioKind.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = { kind = option; selected = null; kindMenuOpen = false }
                        )
                    }
                }
            }
            DateRangeChips(range) { range = it; selected = null }

            // A ring marks each month that beat every earlier month for that line (the first
            // month is only a baseline), judged against all-time history even when a range hides
            // the earlier months.
            fun records(values: List<Double>): List<Boolean> {
                var best = Double.NEGATIVE_INFINITY
                val flags = values.map { v ->
                    val isRecord = best != Double.NEGATIVE_INFINITY && v > best && v > 0
                    best = maxOf(best, v)
                    isRecord
                }
                return flags
            }
            val offset = visible.firstOrNull()?.let { totals.indexOf(it) } ?: 0
            fun series(color: Color, pick: (MonthTotal) -> Double): LineSeries {
                val all = totals.map { display(pick(it)) }
                return LineSeries(color, all.drop(offset).take(visible.size), records(all).drop(offset).take(visible.size))
            }
            val zone = remember { ZoneId.systemDefault() }
            val lines = listOf(
                series(MaterialTheme.colorScheme.primary) {
                    kind.type?.let { type -> it.of(type) } ?: it.totalMeters
                }
            )
            if (visible.isEmpty()) Text("No workouts in that range.") else {
            LineChart(
                xMillis = visible.map { it.month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() },
                series = lines,
                selected = selected?.takeIf { it in visible.indices },
                onSelect = { selected = it },
                xLabel = { MONTH_LABEL_FORMAT.format(java.time.Instant.ofEpochMilli(it).atZone(zone)) },
                yLabel = { String.format(Locale.US, "%.1f", it) }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(MaterialTheme.colorScheme.primary, kind.label)
                Text("Ring = record month", style = MaterialTheme.typography.labelSmall)
            }

            selected?.let { visible.getOrNull(it) }?.let { month ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(month.month.format(MONTH_LABEL_FORMAT), style = MaterialTheme.typography.titleSmall)
                        ACTIVITY_ORDER.forEach { type ->
                            Text("${type.displayName()}: %.1f %s".format(display(month.of(type)), unit.symbol), style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Total: %.1f %s".format(display(month.totalMeters), unit.symbol), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            }

            Text("Totals", style = MaterialTheme.typography.titleMedium)
            totals.asReversed().forEach {
                Text(
                    "%s: run %.1f, walk %.1f, bike %.1f, stationary %.1f, total %.1f %s".format(
                        it.month, display(it.of(ActivityType.RUN)), display(it.of(ActivityType.WALK)),
                        display(it.of(ActivityType.BIKE)), display(it.of(ActivityType.STATIONARY_BIKE)),
                        display(it.totalMeters), unit.symbol
                    )
                )
            }
            Text(
                "All time: run %.1f, walk %.1f, bike %.1f, stationary %.1f, total %.1f %s".format(
                    display(totals.sumOf { it.of(ActivityType.RUN) }),
                    display(totals.sumOf { it.of(ActivityType.WALK) }),
                    display(totals.sumOf { it.of(ActivityType.BIKE) }),
                    display(totals.sumOf { it.of(ActivityType.STATIONARY_BIKE) }),
                    display(totals.sumOf { it.totalMeters }),
                    unit.symbol
                ),
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { exportLauncher.launch("pulseguard_monthly_totals.csv") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export monthly totals (CSV)") }
        }
    }
}
