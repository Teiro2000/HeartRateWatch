package com.pedro.heartratewatch.mobile

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.min
import kotlin.math.roundToInt

private fun LabelField.color(): Color = when (this) {
    LabelField.CALORIES -> Color(0xFFFF9800)
    LabelField.PROTEIN -> Color(0xFF2196F3)
    LabelField.CARBS -> Color(0xFF4CAF50)
    LabelField.FAT -> Color(0xFFE53935)
    LabelField.SERVING -> Color(0xFF9C27B0)
}

/**
 * The photo that was scanned, with a coloured box over the row each number was read from. With [zoomable] you can pinch
 * and drag to look closely at small print.
 */
@Composable
fun LabelPreview(scan: LabelScan, modifier: Modifier = Modifier, zoomable: Boolean = false) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val image = remember(scan) { scan.photo.asImageBitmap() }
    Canvas(
        modifier = modifier
            .let { base ->
                if (zoomable) base.pointerInput(Unit) {
                    detectTransformGestures { _, drag, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(1f, 8f)
                        pan += drag
                    }
                } else base
            }
    ) {
        val bw = scan.photo.width.toFloat()
        val bh = scan.photo.height.toFloat()
        val fit = min(size.width / bw, size.height / bh) * zoom
        val topLeft = Offset(size.width / 2f - bw * fit / 2f, size.height / 2f - bh * fit / 2f) + pan
        drawImage(
            image,
            dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
            dstSize = IntSize((bw * fit).roundToInt(), (bh * fit).roundToInt())
        )
        scan.highlights.forEach { box ->
            val color = box.field.color()
            val origin = Offset(topLeft.x + box.left * fit, topLeft.y + box.top * fit)
            val boxSize = Size((box.right - box.left) * fit, (box.bottom - box.top) * fit)
            drawRect(color.copy(alpha = 0.25f), origin, boxSize)
            drawRect(color, origin, boxSize, style = Stroke(2.dp.toPx()))
        }
    }
}

/** What each colour on the photo is, with the number that was read there. */
@Composable
fun LabelLegend(scan: LabelScan) {
    val label = scan.label
    fun text(field: LabelField): String? = when (field) {
        LabelField.CALORIES -> label.calories?.let { "${formatAmount(it)} kcal" }
        LabelField.PROTEIN -> label.proteinG?.let { "${formatAmount(it)} g" }
        LabelField.CARBS -> label.carbsG?.let { "${formatAmount(it)} g" }
        LabelField.FAT -> label.fatG?.let { "${formatAmount(it)} g" }
        LabelField.SERVING -> label.servingAmount?.let { formatQuantity(it, label.servingUnit) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LabelField.entries.forEach { field ->
            val value = text(field)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(12.dp).background(if (value != null) field.color() else Color.Transparent))
                Text(
                    if (value != null) "${field.label}: $value" else "${field.label}: not found",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** A saved photo full screen, to pinch and drag over. */
@Composable
fun PhotoViewer(photo: Bitmap, onClose: () -> Unit) {
    val scan = remember(photo) { LabelScan(ScannedLabel(null, null, null, null, null), photo, emptyList()) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved photo", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Text("Pinch to zoom, drag to move.", style = MaterialTheme.typography.bodySmall)
                LabelPreview(scan, modifier = Modifier.fillMaxWidth().weight(1f), zoomable = true)
            }
        }
    }
}

/** The photo full screen, to pinch and drag over, with the legend underneath. */
@Composable
fun LabelPreviewScreen(scan: LabelScan, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Where the numbers came from", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Text("Pinch to zoom, drag to move.", style = MaterialTheme.typography.bodySmall)
                LabelPreview(scan, modifier = Modifier.fillMaxWidth().weight(1f), zoomable = true)
                LabelLegend(scan)
            }
        }
    }
}
