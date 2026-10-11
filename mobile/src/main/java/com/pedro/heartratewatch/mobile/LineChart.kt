package com.pedro.heartratewatch.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** One line on a [LineChart]: a value per x position, and which of them get a ring around the dot. */
internal class LineSeries(val color: Color, val values: List<Double>, val ringed: List<Boolean>)

private const val LEFT_PAD_DP = 44f
private const val RIGHT_PAD_DP = 12f
private const val TOP_PAD_DP = 12f
private const val BOTTOM_PAD_DP = 24f

/**
 * The line chart used by both the workout Progress screen and the run Stats screen: dots joined
 * by lines, x spread out by time, y scaled to fit, a vertical marker on the tapped point. Tapping
 * within ~40dp of a point's column calls [onSelect] with its index (tapping elsewhere clears it).
 * [xMillis] must be ascending and every series needs one value per entry.
 */
@Composable
internal fun LineChart(
    xMillis: List<Long>,
    series: List<LineSeries>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    xLabel: (Long) -> String,
    yLabel: (Double) -> String
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val highlight = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current.density

    val all = series.flatMap { it.values }
    var yMin = all.min()
    var yMax = all.max()
    if (yMax - yMin < 1e-6) { yMin -= 1.0; yMax += 1.0 }
    val yPad = (yMax - yMin) * 0.1
    yMin -= yPad
    yMax += yPad

    fun xs(): List<Float> {
        val left = LEFT_PAD_DP * density
        val width = size.width - left - RIGHT_PAD_DP * density
        val tMin = xMillis.first()
        val span = xMillis.last() - tMin
        return xMillis.map { if (span == 0L) left + width / 2 else left + width * ((it - tMin).toFloat() / span) }
    }

    fun yOf(value: Double): Float {
        val top = TOP_PAD_DP * density
        val height = size.height - top - BOTTOM_PAD_DP * density
        return top + height * (1f - ((value - yMin) / (yMax - yMin)).toFloat())
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .onSizeChanged { size = it }
            .pointerInput(xMillis, series.size, size) {
                detectTapGestures { tap ->
                    val positions = xs()
                    val nearest = positions.indices.minByOrNull { abs(positions[it] - tap.x) }
                    onSelect(nearest?.takeIf { abs(positions[it] - tap.x) <= 40 * density })
                }
            }
    ) {
        if (size == IntSize.Zero) return@Canvas
        val positions = xs()
        val left = LEFT_PAD_DP * density
        val bottom = size.height - BOTTOM_PAD_DP * density

        drawLine(axisColor.copy(alpha = 0.5f), Offset(left, TOP_PAD_DP * density), Offset(left, bottom), 1f)
        drawLine(axisColor.copy(alpha = 0.5f), Offset(left, bottom), Offset(size.width - RIGHT_PAD_DP * density, bottom), 1f)

        fun label(text: String, x: Float, y: Float) = drawAxisLabel(measurer, text, x, y, axisColor)
        label(yLabel(yMax), 2f, TOP_PAD_DP * density - 6f)
        label(yLabel(yMin), 2f, bottom - 14f)
        label(xLabel(xMillis.first()), left, bottom + 4f)
        if (xMillis.size > 1) {
            label(xLabel(xMillis.last()), size.width - RIGHT_PAD_DP * density - 40f, bottom + 4f)
        }

        for (line in series) {
            if (xMillis.size > 1) {
                val path = Path()
                positions.indices.forEach { i ->
                    val point = Offset(positions[i], yOf(line.values[i]))
                    if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                drawPath(path, line.color, style = Stroke(width = 2.5f * density))
            }
            positions.indices.forEach { i ->
                val center = Offset(positions[i], yOf(line.values[i]))
                drawCircle(line.color, radius = 4f * density, center = center)
                if (line.ringed[i]) {
                    drawCircle(line.color, radius = 8f * density, center = center, style = Stroke(width = 2f * density))
                }
            }
        }

        selected?.let { i ->
            drawLine(highlight.copy(alpha = 0.4f), Offset(positions[i], TOP_PAD_DP * density), Offset(positions[i], bottom), 1f * density)
        }
    }
}

private fun DrawScope.drawAxisLabel(measurer: TextMeasurer, text: String, x: Float, y: Float, color: Color) {
    drawText(measurer, text, topLeft = Offset(x, y), style = TextStyle(fontSize = 10.sp, color = color))
}
