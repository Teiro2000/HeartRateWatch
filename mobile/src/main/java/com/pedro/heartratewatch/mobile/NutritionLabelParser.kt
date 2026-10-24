package com.pedro.heartratewatch.mobile

import kotlin.math.max
import kotlin.math.min

/** One line of text the camera found, with where it sits in the picture (pixels). */
data class OcrLine(val text: String, val left: Int, val top: Int, val bottom: Int)

/** What could be read off a Nutrition Facts label: all numbers are for one serving; null where the label wasn't read. */
data class ScannedLabel(
    val calories: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    /** What one serving weighs in grams, from the bracket in "Serving size 2/3 cup (55g)". */
    val servingGrams: Double?
) {
    val foundAnything: Boolean get() = listOf(calories, proteinG, carbsG, fatG, servingGrams).any { it != null }
}

/**
 * Turns the text read from a photo of a (US-style) Nutrition Facts label into numbers. Text recognition returns pieces
 * of text, not rows -- a label's big "Calories" and its "230" often come back as separate pieces -- so [rows] first puts
 * pieces that sit side by side back into one row, then [parse] looks for each nutrient in the rows. Pure, so it can be tested.
 */
object NutritionLabelParser {

    /** Joins pieces of text that sit on the same line of the picture into one row each, top to bottom, left to right. */
    fun rows(lines: List<OcrLine>): List<String> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        val bands = mutableListOf<IntArray>() // top, bottom of each row so far
        for (line in lines.sortedBy { it.top + it.bottom }) {
            val height = max(1, line.bottom - line.top)
            val index = bands.indexOfFirst { band ->
                val overlap = min(band[1], line.bottom) - max(band[0], line.top)
                overlap >= 0.5 * min(height, max(1, band[1] - band[0]))
            }
            if (index < 0) {
                rows += mutableListOf(line)
                bands += intArrayOf(line.top, line.bottom)
            } else {
                rows[index] += line
                bands[index][0] = min(bands[index][0], line.top)
                bands[index][1] = max(bands[index][1], line.bottom)
            }
        }
        return rows.map { row -> row.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }

    private const val NUMBER = """(\d+(?:[.,]\d+)?)"""
    private val calories = Regex("""calories\s*[:\-]?\s*$NUMBER(?!\s*(?:g\b|mg\b|%))""")
    private val protein = Regex("""protein\s*[:\-]?\s*<?\s*$NUMBER\s*g\b""")
    private val carbs = Regex("""total\s*carb\w*\.?\s*[:\-]?\s*<?\s*$NUMBER\s*g\b""")
    private val fat = Regex("""total\s*fat\s*[:\-]?\s*<?\s*$NUMBER\s*g\b""")
    private val servingBracket = Regex("""\(\s*$NUMBER\s*g\s*\)""")
    private val servingPlain = Regex("""$NUMBER\s*g\b""")

    /** "Og" is how a zero followed by g is often read. */
    private fun clean(row: String): String =
        row.lowercase().replace(Regex("""\b[o]\s*g\b"""), "0g").replace(" ", " ")

    private fun number(match: MatchResult): Double = match.groupValues[1].replace(',', '.').toDouble()

    fun parse(rows: List<String>): ScannedLabel {
        val cleaned = rows.map(::clean)
        fun first(regex: Regex): Double? = cleaned.firstNotNullOfOrNull { regex.find(it)?.let(::number) }

        // "Serving size 2/3 cup (55g)": the weight in the bracket; failing that a plain "30g" on the serving size row.
        val servingRow = cleaned.firstOrNull { it.contains("serving size") || it.contains("serv. size") }
        val servingGrams = servingRow?.let { row ->
            servingBracket.find(row)?.let(::number) ?: servingPlain.find(row)?.let(::number)
        }

        return ScannedLabel(
            calories = first(calories),
            proteinG = first(protein),
            carbsG = first(carbs),
            fatG = first(fat),
            servingGrams = servingGrams?.takeIf { it > 0 }
        )
    }
}
