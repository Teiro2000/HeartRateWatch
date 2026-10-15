package com.pedro.heartratewatch.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pedro.heartratewatch.shared.DistanceUnit
import com.pedro.heartratewatch.shared.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val FastColor = Color(0xFF43A047)
private val MidColor = Color(0xFFFBC02D)
private val SlowColor = Color(0xFFE53935)

/** Green for the fastest stretches of a route, through yellow, to red for the slowest. */
private fun paceColor(slowness: Float): Color =
    if (slowness < 0.5f) lerp(FastColor, MidColor, slowness * 2f) else lerp(MidColor, SlowColor, (slowness - 0.5f) * 2f)

/**
 * The saved route of a session. The line is coloured by pace (green where you were fastest, red where you were
 * slowest, compared with the rest of the same session) when the route has times, and there is a small marker at
 * every [unit] along it (1 km, 1 mile and so on) with the green dot at the start and the orange dot at the end. It
 * is the route's shape on a plain background -- no street map behind it. Shows nothing when the session has no
 * route (indoor sessions, or ones from before routes were saved); routes from before times were kept are drawn as a
 * plain line.
 */
@Composable
fun RouteMap(startedAtMillis: Long, unit: DistanceUnit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val route by produceState(initialValue = emptyList<RoutePoint>(), startedAtMillis) {
        value = withContext(Dispatchers.IO) { RouteStore(context.applicationContext).load(startedAtMillis) }
    }
    if (route.size < 2) return

    val slowness = remember(route) { RouteGeometry.slowness(route) }
    val markers = remember(route, unit) { RouteGeometry.markers(route, unit.metersPerUnit) }
    val plainLine = MaterialTheme.colorScheme.primary
    val markerFill = MaterialTheme.colorScheme.surface
    val markerText = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                val pad = 18.dp.toPx()
                // Longitude degrees are shorter than latitude degrees away from the equator; scale to keep the shape true.
                val midLat = route.sumOf { it.latitude } / route.size
                val lonScale = cos(Math.toRadians(midLat))
                val xs = route.map { it.longitude * lonScale }
                val ys = route.map { it.latitude }
                val minX = xs.min(); val maxX = xs.max()
                val minY = ys.min(); val maxY = ys.max()
                val spanX = max(maxX - minX, 1e-9)
                val spanY = max(maxY - minY, 1e-9)
                val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY).toFloat()
                // Centred, with north up.
                val offsetX = pad + ((size.width - 2 * pad) - (spanX * scale).toFloat()) / 2f
                val offsetY = pad + ((size.height - 2 * pad) - (spanY * scale).toFloat()) / 2f
                fun at(latitude: Double, longitude: Double) = Offset(
                    offsetX + ((longitude * lonScale - minX) * scale).toFloat(),
                    offsetY + ((maxY - latitude) * scale).toFloat()
                )
                fun point(i: Int) = at(route[i].latitude, route[i].longitude)

                val stroke = 4.dp.toPx()
                for (i in 0 until route.size - 1) {
                    val color = slowness?.let { paceColor(it[i]) } ?: plainLine
                    drawLine(color, point(i), point(i + 1), strokeWidth = stroke, cap = StrokeCap.Round)
                }

                // A numbered marker at each distance.
                val labelStyle = TextStyle(fontSize = 10.sp, color = markerText)
                markers.forEach { marker ->
                    val p = at(marker.latitude, marker.longitude)
                    drawCircle(markerFill, radius = 6.dp.toPx(), center = p)
                    drawCircle(markerText, radius = 6.dp.toPx(), center = p, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()))
                    val label = (marker.meters / unit.metersPerUnit).roundToInt().toString()
                    val layout = measurer.measure(label, labelStyle)
                    drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
                }

                drawCircle(Color(0xFF43A047), radius = 5.dp.toPx(), center = point(0))
                drawCircle(Color(0xFFFF7043), radius = 5.dp.toPx(), center = point(route.lastIndex))
            }
        }
        if (slowness != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Faster", style = MaterialTheme.typography.labelSmall)
                Canvas(modifier = Modifier.weight(1f).height(8.dp)) {
                    val steps = 24
                    for (i in 0 until steps) {
                        drawRect(
                            paceColor(i / (steps - 1f)),
                            topLeft = Offset(size.width * i / steps, 0f),
                            size = androidx.compose.ui.geometry.Size(size.width / steps + 1f, size.height)
                        )
                    }
                }
                Text("Slower", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
