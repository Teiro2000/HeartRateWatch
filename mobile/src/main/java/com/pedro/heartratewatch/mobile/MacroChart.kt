package com.pedro.heartratewatch.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val ProteinColor = Color(0xFF2196F3)
private val CarbsColor = Color(0xFF4CAF50)
private val FatColor = Color(0xFFFF9800)

private const val KCAL_PER_G_PROTEIN = 4.0
private const val KCAL_PER_G_CARBS = 4.0
private const val KCAL_PER_G_FAT = 9.0

/** A ring split by where the calories come from (4 per g of protein or carbs, 9 per g of fat); an empty grey ring when nothing is logged. */
@Composable
fun MacroPie(proteinG: Double, carbsG: Double, fatG: Double, size: Dp = 110.dp) {
    val track = MaterialTheme.colorScheme.outline
    Canvas(modifier = Modifier.size(size)) {
        val stroke = 22.dp.toPx()
        val topLeft = Offset(stroke / 2, stroke / 2)
        val arc = Size(this.size.width - stroke, this.size.height - stroke)
        val parts = listOf(
            proteinG * KCAL_PER_G_PROTEIN to ProteinColor,
            carbsG * KCAL_PER_G_CARBS to CarbsColor,
            fatG * KCAL_PER_G_FAT to FatColor
        )
        val total = parts.sumOf { it.first }
        if (total <= 0) {
            drawArc(track.copy(alpha = 0.4f), 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
        } else {
            var start = -90f
            parts.forEach { (kcal, color) ->
                val sweep = (kcal / total * 360).toFloat()
                drawArc(color, start, sweep, false, topLeft, arc, style = Stroke(stroke))
                start += sweep
            }
        }
    }
}

/**
 * Where the day's calories came from, as a pie, with grams and shares per macro. When a weight plan gives a
 * [recommended] split (for cutting or bulking) it is shown beside it, and each macro reads "eaten of recommended".
 */
@Composable
fun MacroCard(totals: DayTotals, recommended: MacroSplit?) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Protein, carbs and fat", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MacroPie(totals.proteinG, totals.carbsG, totals.fatG)
                    Text("Eaten", style = MaterialTheme.typography.bodySmall)
                }
                recommended?.let {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MacroPie(it.proteinG, it.carbsG, it.fatG)
                        Text("Recommended", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            val eatenKcal = totals.proteinG * KCAL_PER_G_PROTEIN + totals.carbsG * KCAL_PER_G_CARBS + totals.fatG * KCAL_PER_G_FAT
            fun share(kcal: Double) = if (eatenKcal > 0) " (${(kcal / eatenKcal * 100).roundToInt()}%)" else ""
            MacroRow(ProteinColor, "Protein", totals.proteinG, recommended?.proteinG, share(totals.proteinG * KCAL_PER_G_PROTEIN))
            MacroRow(CarbsColor, "Carbs", totals.carbsG, recommended?.carbsG, share(totals.carbsG * KCAL_PER_G_CARBS))
            MacroRow(FatColor, "Fat", totals.fatG, recommended?.fatG, share(totals.fatG * KCAL_PER_G_FAT))
            if (recommended != null) {
                Text(
                    "Recommended for ${recommended.mode}: ${recommended.explanation}",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "Set a weight plan to get a recommended split for cutting or bulking.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun MacroRow(color: Color, name: String, eatenG: Double, recommendedG: Double?, share: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.size(12.dp).background(color))
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            "${formatAmount(eatenG)}${if (recommendedG != null) " / ${formatAmount(recommendedG)}" else ""} g$share",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End
        )
    }
}
