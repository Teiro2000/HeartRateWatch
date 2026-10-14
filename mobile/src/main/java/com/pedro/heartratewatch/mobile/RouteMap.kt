package com.pedro.heartratewatch.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pedro.heartratewatch.shared.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The saved route of a session, drawn as a line (green dot where it started, orange where it ended).
 * It is the route's shape on a plain background -- no street map behind it, which would need a map
 * service and a key. Shows nothing when the session has no route (indoor sessions, or ones from
 * before routes were saved).
 */
@Composable
fun RouteMap(startedAtMillis: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val route by produceState(initialValue = emptyList<RoutePoint>(), startedAtMillis) {
        value = withContext(Dispatchers.IO) { RouteStore(context.applicationContext).load(startedAtMillis) }
    }
    if (route.size < 2) return

    val line = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.fillMaxWidth().height(160.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            val pad = 14.dp.toPx()
            // Longitude degrees are shorter than latitude degrees away from the equator; scale to keep the shape true.
            val midLat = route.sumOf { it.latitude } / route.size
            val xs = route.map { it.longitude * cos(Math.toRadians(midLat)) }
            val ys = route.map { it.latitude }
            val minX = xs.min(); val maxX = xs.max()
            val minY = ys.min(); val maxY = ys.max()
            val spanX = max(maxX - minX, 1e-9)
            val spanY = max(maxY - minY, 1e-9)
            val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY).toFloat()
            // Centred, with north up.
            val offsetX = pad + ((size.width - 2 * pad) - (spanX * scale).toFloat()) / 2f
            val offsetY = pad + ((size.height - 2 * pad) - (spanY * scale).toFloat()) / 2f
            fun point(i: Int) = Offset(
                offsetX + ((xs[i] - minX) * scale).toFloat(),
                offsetY + ((maxY - ys[i]) * scale).toFloat()
            )

            val path = Path()
            for (i in route.indices) {
                val p = point(i)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(Color(0xFF43A047), radius = 5.dp.toPx(), center = point(0))
            drawCircle(Color(0xFFFF7043), radius = 5.dp.toPx(), center = point(route.lastIndex))
        }
    }
}
