package com.pedro.heartratewatch.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pedro.heartratewatch.mobile.theme.PipBoyTheme
import com.pedro.heartratewatch.shared.DecodedRoute
import com.pedro.heartratewatch.shared.DistanceUnit
import com.pedro.heartratewatch.shared.formatDistance
import com.pedro.heartratewatch.shared.formatPace
import com.pedro.heartratewatch.shared.formatSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A session's route as a 3D line you can turn and zoom: drag to rotate, pinch to zoom. Each point of the line sits
 * at its altitude above a flat grid, so hills show up as hills. Opened by tapping a route in Cardio History.
 */
class RouteViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startedAt = intent.getLongExtra(EXTRA_STARTED_AT, 0L)
        val unit = runCatching { DistanceUnit.valueOf(intent.getStringExtra(EXTRA_UNIT) ?: "") }.getOrDefault(DistanceUnit.KILOMETERS)
        val showsSpeed = intent.getBooleanExtra(EXTRA_SHOWS_SPEED, false)
        setContent { PipBoyTheme { RouteViewScreen(startedAt, unit, showsSpeed) } }
    }

    companion object {
        private const val EXTRA_STARTED_AT = "startedAt"
        private const val EXTRA_UNIT = "unit"
        private const val EXTRA_SHOWS_SPEED = "showsSpeed"

        fun open(context: Context, startedAtMillis: Long, unit: DistanceUnit, showsSpeed: Boolean) {
            context.startActivity(
                Intent(context, RouteViewActivity::class.java)
                    .putExtra(EXTRA_STARTED_AT, startedAtMillis)
                    .putExtra(EXTRA_UNIT, unit.name)
                    .putExtra(EXTRA_SHOWS_SPEED, showsSpeed)
            )
        }
    }
}

private val LowColor = Color(0xFF1E88E5)
private val HighColor = Color(0xFFE53935)
private val MidHeightColor = Color(0xFF43A047)

/** Blue for the lowest part of the route, through green, to red for the highest. */
private fun heightColor(t: Float): Color =
    if (t < 0.5f) lerp(LowColor, MidHeightColor, t * 2f) else lerp(MidHeightColor, HighColor, (t - 0.5f) * 2f)

private enum class Coloring { HEIGHT, PACE }

/** Heights are shown in feet when distances are in miles, feet or yards, otherwise in meters. */
private fun usesFeet(unit: DistanceUnit) = unit == DistanceUnit.MILES || unit == DistanceUnit.FEET || unit == DistanceUnit.YARDS

private fun heightText(meters: Double, feet: Boolean, signed: Boolean = false): String {
    val value = (if (feet) meters / 0.3048 else meters).roundToInt()
    return (if (signed && value > 0) "+" else "") + value + (if (feet) " ft" else " m")
}

@Composable
private fun RouteViewScreen(startedAtMillis: Long, unit: DistanceUnit, showsSpeed: Boolean) {
    val context = LocalContext.current
    val back = rememberSystemBack()
    val loaded by produceState<DecodedRoute?>(initialValue = null, startedAtMillis) {
        value = withContext(Dispatchers.IO) { RouteStore(context.applicationContext).loadRoute(startedAtMillis) }
    }
    val route = loaded
    var showInfo by remember { mutableStateOf(false) }

    Scaffold { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp)) {
            NavButtons(onBack = back, onMainMenu = { goToMainMenu(context) })
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Route in 3D", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { showInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "About the heights", tint = MaterialTheme.colorScheme.primary)
                }
            }
            when {
                route == null -> Text("Loading...")
                route.points.size < 2 -> Text("This session has no saved route.")
                else -> RouteView3D(route, unit, showsSpeed)
            }
        }
    }

    if (showInfo) {
        val text = when {
            route != null && route.points.none { it.hasAltitude } ->
                "This session was recorded before heights were saved, so its route is drawn flat. " +
                    "New outdoor sessions record heights."
            route != null && route.barometric ->
                "The heights in this session came from the barometer (air pressure sensor), which follows climbs " +
                    "to within a few meters. Weather changes the pressure too, so read the numbers as how much you " +
                    "went up and down, not as exact height above sea level."
            else ->
                "The heights in this session came from GPS, which can be off by 10 to 20 meters, so small hills may " +
                    "look flat or bumpy. A barometer would be more accurate: the app uses the barometer " +
                    "automatically in new sessions when your watch or phone has one."
        }
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("About the heights") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text)
                    Text(
                        "A barometer would be more accurate than GPS for heights.",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } }
        )
    }
}

@Composable
private fun RouteView3D(route: DecodedRoute, unit: DistanceUnit, showsSpeed: Boolean) {
    val points = route.points
    val elevation = remember(route) { RouteGeometry.elevation(points, window = if (route.barometric) 3 else 7, threshold = if (route.barometric) 2.0 else 4.0) }
    val slowness = remember(route) { RouteGeometry.slowness(points) }
    val cumulative = remember(route) { RouteGeometry.cumulativeMeters(points) }
    val feet = usesFeet(unit)

    // Local x (east) and y (north) in meters from the route's middle; length of the drawn area.
    val local = remember(route) {
        val midLat = points.sumOf { it.latitude } / points.size
        val midLon = points.sumOf { it.longitude } / points.size
        val lonMeters = 111_320.0 * cos(Math.toRadians(midLat))
        points.map { Pair((it.longitude - midLon) * lonMeters, (it.latitude - midLat) * 110_574.0) }
    }
    val halfX = max(local.maxOf { kotlin.math.abs(it.first) }, 5.0)
    val halfY = max(local.maxOf { kotlin.math.abs(it.second) }, 5.0)
    val heightRange = elevation?.let { it.max - it.min } ?: 0.0

    // A starting exaggeration that makes hills visible without looking like mountains.
    val autoExaggeration = remember(route) {
        if (heightRange < 1.0) 1.0 else (0.25 * 2 * max(halfX, halfY) / heightRange).coerceIn(1.0, 30.0)
    }
    var exaggerationT by remember(route) { mutableFloatStateOf((ln(autoExaggeration) / ln(30.0)).toFloat()) }
    val exaggeration = exp(exaggerationT * ln(30.0))
    var yaw by remember { mutableFloatStateOf(-0.6f) }
    var pitch by remember { mutableFloatStateOf(0.9f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var position by remember(route) { mutableFloatStateOf(0f) }
    var coloring by remember(route) { mutableStateOf(if (elevation != null) Coloring.HEIGHT else Coloring.PACE) }
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val plainLine = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface

    val index = (position * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1)

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoomChange, _ ->
                            yaw += pan.x * 0.008f
                            pitch = (pitch + pan.y * 0.008f).coerceIn(0.15f, (PI / 2).toFloat())
                            zoom = (zoom * zoomChange).coerceIn(0.4f, 8f)
                        }
                    }
            ) {
                val zRange = heightRange * exaggeration
                val zCenter = zRange / 2.0
                val radius = sqrt(halfX * halfX + halfY * halfY + zRange * zRange / 4.0)
                val gridHalf = max(halfX, halfY) * 1.15
                val scale = (min(size.width, size.height) / 2f * 0.8f / radius * zoom).toFloat()
                val cosY = cos(yaw.toDouble()); val sinY = sin(yaw.toDouble())
                val cosP = cos(pitch.toDouble()); val sinP = sin(pitch.toDouble())
                val cx = size.width / 2f
                val cy = size.height / 2f

                class P(val x: Float, val y: Float, val depth: Double)
                fun project(x: Double, y: Double, z: Double): P {
                    val xr = x * cosY - y * sinY
                    val yr = x * sinY + y * cosY
                    return P(cx + (xr * scale).toFloat(), cy - ((yr * sinP + z * cosP) * scale).toFloat(), yr * cosP - z * sinP)
                }
                fun heightZ(i: Int): Double =
                    (elevation?.let { (it.meters[i] - it.min) * exaggeration } ?: 0.0) - zCenter
                val groundZ = -zCenter

                // The ground grid, with north marked.
                val divisions = 8
                for (k in 0..divisions) {
                    val t = -gridHalf + 2 * gridHalf * k / divisions
                    val a = project(t, -gridHalf, groundZ); val b = project(t, gridHalf, groundZ)
                    drawLine(gridColor, Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = 1.dp.toPx())
                    val c = project(-gridHalf, t, groundZ); val d = project(gridHalf, t, groundZ)
                    drawLine(gridColor, Offset(c.x, c.y), Offset(d.x, d.y), strokeWidth = 1.dp.toPx())
                }
                val north = project(0.0, gridHalf * 1.12, groundZ)
                val northLayout = measurer.measure("N", TextStyle(fontSize = 12.sp, color = labelColor))
                drawText(northLayout, topLeft = Offset(north.x - northLayout.size.width / 2f, north.y - northLayout.size.height / 2f))

                val ground = local.indices.map { project(local[it].first, local[it].second, groundZ) }
                val raised = local.indices.map { project(local[it].first, local[it].second, heightZ(it)) }

                // The route's shadow on the ground, and a few lines down to it.
                for (i in 0 until points.size - 1) {
                    drawLine(gridColor, Offset(ground[i].x, ground[i].y), Offset(ground[i + 1].x, ground[i + 1].y), strokeWidth = 2.dp.toPx())
                }
                if (elevation != null) {
                    val step = max(1, points.size / 14)
                    for (i in points.indices step step) {
                        drawLine(gridColor, Offset(raised[i].x, raised[i].y), Offset(ground[i].x, ground[i].y), strokeWidth = 1.dp.toPx())
                    }
                }

                // The route itself, far parts first so near parts draw over them.
                val order = (0 until points.size - 1).sortedByDescending { raised[it].depth + raised[it + 1].depth }
                val stroke = 4.dp.toPx()
                for (i in order) {
                    val color = when {
                        coloring == Coloring.PACE && slowness != null -> paceColor(slowness[i])
                        elevation != null && heightRange >= 0.5 ->
                            heightColor((((elevation.meters[i] + elevation.meters[i + 1]) / 2 - elevation.min) / heightRange).toFloat().coerceIn(0f, 1f))
                        else -> plainLine
                    }
                    drawLine(color, Offset(raised[i].x, raised[i].y), Offset(raised[i + 1].x, raised[i + 1].y), strokeWidth = stroke, cap = StrokeCap.Round)
                }

                drawCircle(Color(0xFF43A047), radius = 6.dp.toPx(), center = Offset(raised[0].x, raised[0].y))
                drawCircle(Color(0xFFFF7043), radius = 6.dp.toPx(), center = Offset(raised.last().x, raised.last().y))

                // The spot picked with the slider, with a line down to the ground.
                val here = raised[index]
                val under = ground[index]
                drawLine(labelColor, Offset(here.x, here.y), Offset(under.x, under.y), strokeWidth = 1.5.dp.toPx())
                drawCircle(surface, radius = 7.dp.toPx(), center = Offset(here.x, here.y))
                drawCircle(labelColor, radius = 7.dp.toPx(), center = Offset(here.x, here.y), style = Stroke(2.dp.toPx()))
            }
            Text(
                "Drag to turn, pinch to zoom",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomStart)
            )
            TextButton(onClick = { yaw = -0.6f; pitch = 0.9f; zoom = 1f }, modifier = Modifier.align(Alignment.BottomEnd)) { Text("Reset view") }
        }

        // What it is like at the picked spot.
        val parts = buildList {
            if (elevation != null) {
                add("Height ${heightText(elevation.meters[index], feet)} (${heightText(elevation.meters[index] - elevation.meters[0], feet, signed = true)} from the start)")
            }
            add(formatDistance(cumulative[index].toFloat(), unit) + " along")
            RouteGeometry.paceSecPerKm(points, index)?.let { add(if (showsSpeed) formatSpeed(it, unit) else formatPace(it, unit)) }
        }
        Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
        Slider(value = position, onValueChange = { position = it })

        if (elevation != null) {
            Text(
                "Climb ${heightText(elevation.climb, feet)}  ·  Descent ${heightText(elevation.descent, feet)}  ·  " +
                    "Low ${heightText(elevation.min, feet)}  ·  High ${heightText(elevation.max, feet)}",
                style = MaterialTheme.typography.labelMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Height x${"%.1f".format(exaggeration)}", style = MaterialTheme.typography.labelMedium)
                Slider(value = exaggerationT, onValueChange = { exaggerationT = it }, modifier = Modifier.weight(1f))
            }
        } else {
            Text(
                "No height data for this session, so the route is flat. Tap the (i) at the top for why.",
                style = MaterialTheme.typography.labelMedium
            )
        }
        if (elevation != null && slowness != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = coloring == Coloring.HEIGHT, onClick = { coloring = Coloring.HEIGHT }, label = { Text("Colour by height") })
                FilterChip(selected = coloring == Coloring.PACE, onClick = { coloring = Coloring.PACE }, label = { Text("Colour by pace") })
            }
        }
    }
}
