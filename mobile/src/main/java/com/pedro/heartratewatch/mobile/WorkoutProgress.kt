package com.pedro.heartratewatch.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private enum class ProgressRange(val label: String, val days: Int?) {
    THREE_MONTHS("3 months", 90),
    ALL_TIME("All time", null)
}

/** One workout's result for the chosen exercise. Weights are in lb, like everything stored. */
private data class ProgressPoint(
    val millis: Long,
    val templateName: String,
    val sets: List<LoggedSet>,
    val note: String,
    val topWeightLb: Double,
    val topWeightReps: Int,
    val e1rmLb: Double,
    /** Beat every earlier workout's top weight / estimated 1RM (the first workout is only a baseline). */
    val topWeightPr: Boolean,
    val e1rmPr: Boolean
)

/** Epley's estimate of the heaviest single rep you could do from a set of [reps] at [weight]. */
private fun epley(weight: Double, reps: Int): Double = if (reps <= 1) weight else weight * (1 + reps / 30.0)

private fun buildPoints(history: List<WorkoutLog>, exerciseId: String): List<ProgressPoint> {
    var bestTop = Double.NEGATIVE_INFINITY
    var bestE1rm = Double.NEGATIVE_INFINITY
    return history.sortedBy { it.startedAtMillis }.mapNotNull { workout ->
        val logs = workout.exercises.filter { it.exerciseId == exerciseId }
        val sets = logs.flatMap { it.sets }.filter { (it.weight ?: 0.0) > 0.0 && (it.reps ?: 0) > 0 }
        if (sets.isEmpty()) return@mapNotNull null

        val topSet = sets.maxWith(compareBy({ it.weight!! }, { it.reps!! }))
        val e1rm = sets.maxOf { epley(it.weight!!, it.reps!!) }
        val first = bestTop == Double.NEGATIVE_INFINITY
        val point = ProgressPoint(
            millis = workout.startedAtMillis,
            templateName = workout.templateName,
            sets = sets,
            note = logs.map { it.note }.firstOrNull { it.isNotBlank() }.orEmpty(),
            topWeightLb = topSet.weight!!,
            topWeightReps = topSet.reps!!,
            e1rmLb = e1rm,
            topWeightPr = !first && topSet.weight!! > bestTop,
            e1rmPr = !first && e1rm > bestE1rm
        )
        bestTop = maxOf(bestTop, topSet.weight!!)
        bestE1rm = maxOf(bestE1rm, e1rm)
        point
    }
}

@Composable
internal fun ProgressScreen(history: List<WorkoutLog>, unit: WeightUnit) {
    // Only exercises that actually have a usable set logged are worth offering.
    val exerciseOptions = remember(history) {
        history.flatMap { it.exercises }
            .filter { log -> log.sets.any { (it.weight ?: 0.0) > 0.0 && (it.reps ?: 0) > 0 } }
            .associate { it.exerciseId to it.exerciseName }
            .entries.sortedBy { it.value.lowercase() }
    }

    var selectedId by remember { mutableStateOf<String?>(null) }
    var show1rm by remember { mutableStateOf(true) }
    var showTop by remember { mutableStateOf(true) }
    var range by remember { mutableStateOf(ProgressRange.ALL_TIME) }
    var menuOpen by remember { mutableStateOf(false) }
    var selectedPoint by remember { mutableStateOf<Int?>(null) }

    val effectiveId = selectedId?.takeIf { id -> exerciseOptions.any { it.key == id } } ?: exerciseOptions.firstOrNull()?.key

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { ScreenTitle("Progress") }

        if (effectiveId == null) {
            item { Text("Finish a workout with some weighted sets and your progress will show up here.") }
            return@LazyColumn
        }

        val allPoints = buildPoints(history, effectiveId)
        val cutoff = range.days?.let { System.currentTimeMillis() - it * 86_400_000L }
        val points = if (cutoff == null) allPoints else allPoints.filter { it.millis >= cutoff }

        item {
            Box {
                OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(exerciseOptions.first { it.key == effectiveId }.value + "  v")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    exerciseOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.value) },
                            onClick = {
                                selectedId = option.key
                                selectedPoint = null
                                menuOpen = false
                            }
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // At least one line always stays on: a chart with neither would just be empty.
                FilterChip(
                    selected = show1rm,
                    onClick = { if (!show1rm || showTop) show1rm = !show1rm },
                    label = { Text("Est. 1RM") }
                )
                FilterChip(
                    selected = showTop,
                    onClick = { if (!showTop || show1rm) showTop = !showTop },
                    label = { Text("Top weight") }
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressRange.entries.forEach { option ->
                    FilterChip(
                        selected = range == option,
                        onClick = { range = option; selectedPoint = null },
                        label = { Text(option.label) }
                    )
                }
            }
        }

        item {
            if (points.isEmpty()) {
                Text("No workouts for this exercise in that range.")
            } else {
                val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
                LineChart(
                    xMillis = points.map { it.millis },
                    series = buildList {
                        if (showTop) add(LineSeries(MaterialTheme.colorScheme.tertiary, points.map { unit.fromLb(it.topWeightLb) }, points.map { it.topWeightPr }))
                        if (show1rm) add(LineSeries(MaterialTheme.colorScheme.primary, points.map { unit.fromLb(it.e1rmLb) }, points.map { it.e1rmPr }))
                    },
                    selected = selectedPoint?.takeIf { it in points.indices },
                    onSelect = { selectedPoint = it },
                    xLabel = { dateFormat.format(Date(it)) },
                    yLabel = { formatWeight(it) }
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (show1rm) LegendDot(MaterialTheme.colorScheme.primary, "Est. 1RM")
                if (showTop) LegendDot(MaterialTheme.colorScheme.tertiary, "Top weight")
                Text("Ring = personal best", style = MaterialTheme.typography.labelSmall)
            }
        }

        // Personal bests are always all-time, whatever range the chart is showing.
        if (allPoints.isNotEmpty()) {
            item {
                val bestE1rm = allPoints.maxBy { it.e1rmLb }
                val bestTop = allPoints.maxWith(compareBy({ it.topWeightLb }, { it.topWeightReps }))
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Personal bests", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Est. 1RM: ${formatWeight(unit.fromLb(bestE1rm.e1rmLb))} ${unit.symbol} (${formatDate(bestE1rm.millis)})",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Heaviest: ${formatWeight(unit.fromLb(bestTop.topWeightLb))} ${unit.symbol} x ${bestTop.topWeightReps} (${formatDate(bestTop.millis)})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        val picked = selectedPoint?.let { points.getOrNull(it) }
        if (picked != null) {
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${formatDate(picked.millis)} - ${picked.templateName}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Est. 1RM ${formatWeight(unit.fromLb(picked.e1rmLb))} ${unit.symbol}" +
                                if (picked.e1rmPr) "  (personal best)" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Top weight ${formatWeight(unit.fromLb(picked.topWeightLb))} ${unit.symbol} x ${picked.topWeightReps}" +
                                if (picked.topWeightPr) "  (personal best)" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                        picked.sets.forEachIndexed { index, set ->
                            Text("${index + 1}.  ${formatSet(set, unit)}")
                        }
                        if (picked.note.isNotBlank()) {
                            Text("Note: ${picked.note}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item { Box(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
internal fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color, radius = 5.dp.toPx(), center = Offset(5.dp.toPx(), 5.dp.toPx()))
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
