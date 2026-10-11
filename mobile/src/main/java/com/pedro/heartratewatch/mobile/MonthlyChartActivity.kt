package com.pedro.heartratewatch.mobile

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.mobile.theme.PipBoyTheme
import com.pedro.heartratewatch.shared.DistanceUnit
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private val MONTH_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM yy")

private enum class MonthRange(val label: String, val months: Int?) {
    LAST_YEAR("12 months", 12),
    ALL_TIME("All time", null)
}

/**
 * Line chart of total distance per month -- running, stationary biking, and the two combined --
 * in the same style as the workout Progress chart (see [LineChart]): toggle which lines to show,
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
    val unit by unitPreferences.paceUnitFlow.collectAsStateWithLifecycle(initialValue = DistanceUnit.KILOMETERS)
    val totals = remember(runs) { monthlyTotals(runs) }
    val snackbarHostState = remember { SnackbarHostState() }

    var showRun by remember { mutableStateOf(true) }
    var showBike by remember { mutableStateOf(true) }
    var showTotal by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(MonthRange.ALL_TIME) }
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

    // Fixed, unrelated hues for the two activity lines (the Pip-Boy scheme is all shades of green,
    // too close together to tell Running from Stationary bike at a glance); the combined line uses
    // the theme's own accent.
    val runColor = Color(0xFF4FA3F7)
    val bikeColor = Color(0xFFF5A623)
    val totalColor = MaterialTheme.colorScheme.primary

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Monthly distance", style = MaterialTheme.typography.headlineSmall)

            if (totals.isEmpty()) {
                Text("No workouts recorded yet.")
                return@Column
            }

            fun display(meters: Double) = meters / unit.metersPerUnit

            val visible = if (range.months == null) totals else totals.takeLast(range.months!!)

            // At least one line always stays on: a chart with none would just be empty.
            fun toggle(current: Boolean, others: Boolean, set: (Boolean) -> Unit) {
                if (!current || others) set(!current)
                selected = null
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = showRun, onClick = { toggle(showRun, showBike || showTotal) { showRun = it } }, label = { Text("Running") })
                FilterChip(selected = showBike, onClick = { toggle(showBike, showRun || showTotal) { showBike = it } }, label = { Text("Bike") })
                FilterChip(selected = showTotal, onClick = { toggle(showTotal, showRun || showBike) { showTotal = it } }, label = { Text("Total") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonthRange.entries.forEach { option ->
                    FilterChip(
                        selected = range == option,
                        onClick = { range = option; selected = null },
                        label = { Text(option.label) }
                    )
                }
            }

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
            val offset = totals.size - visible.size
            fun series(color: Color, pick: (MonthTotal) -> Double): LineSeries {
                val all = totals.map { display(pick(it)) }
                return LineSeries(color, all.drop(offset), records(all).drop(offset))
            }
            val zone = remember { ZoneId.systemDefault() }
            val lines = buildList {
                if (showRun) add(series(runColor) { it.runMeters })
                if (showBike) add(series(bikeColor) { it.bikeMeters })
                if (showTotal) add(series(totalColor) { it.totalMeters })
            }
            LineChart(
                xMillis = visible.map { it.month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() },
                series = lines,
                selected = selected?.takeIf { it in visible.indices },
                onSelect = { selected = it },
                xLabel = { MONTH_LABEL_FORMAT.format(java.time.Instant.ofEpochMilli(it).atZone(zone)) },
                yLabel = { String.format(Locale.US, "%.1f", it) }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (showRun) LegendDot(runColor, "Running")
                if (showBike) LegendDot(bikeColor, "Bike")
                if (showTotal) LegendDot(totalColor, "Total")
                Text("Ring = record month", style = MaterialTheme.typography.labelSmall)
            }

            selected?.let { visible.getOrNull(it) }?.let { month ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(month.month.format(MONTH_LABEL_FORMAT), style = MaterialTheme.typography.titleSmall)
                        Text("Running: %.1f %s".format(display(month.runMeters), unit.symbol), style = MaterialTheme.typography.bodySmall)
                        Text("Bike: %.1f %s".format(display(month.bikeMeters), unit.symbol), style = MaterialTheme.typography.bodySmall)
                        Text("Total: %.1f %s".format(display(month.totalMeters), unit.symbol), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text("Totals", style = MaterialTheme.typography.titleMedium)
            totals.asReversed().forEach {
                Text(
                    "%s: run %.1f, bike %.1f, total %.1f %s".format(
                        it.month, display(it.runMeters), display(it.bikeMeters), display(it.totalMeters), unit.symbol
                    )
                )
            }
            Text(
                "All time: run %.1f, bike %.1f, total %.1f %s".format(
                    display(totals.sumOf { it.runMeters }),
                    display(totals.sumOf { it.bikeMeters }),
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
