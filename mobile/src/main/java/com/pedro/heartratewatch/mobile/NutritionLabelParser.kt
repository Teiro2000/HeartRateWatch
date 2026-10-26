package com.pedro.heartratewatch.mobile

import kotlin.math.max
import kotlin.math.min

/** One line of text the camera found, with where it sits in the picture (pixels). */
data class OcrLine(val text: String, val left: Int, val top: Int, val bottom: Int, val right: Int = left)

/** A row of the label (pieces of text side by side joined up) and the box it covers in the picture. */
data class LabelRow(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** The numbers a label scan fills in. */
enum class LabelField(val label: String) {
    CALORIES("Calories"),
    PROTEIN("Protein"),
    CARBS("Carbs"),
    FAT("Fat"),
    SERVING("Serving size")
}

/** What could be read off a Nutrition Facts label: all numbers are for one serving; null where the label wasn't read. */
data class ScannedLabel(
    val calories: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    /** How much one serving is, from the bracket in "Serving size 2/3 cup (55g)" or "Per 1 cup (250 mL)", in [servingUnit]. */
    val servingAmount: Double?,
    val servingUnit: ServingUnit = ServingUnit.GRAMS
) {
    val foundAnything: Boolean get() = listOf(calories, proteinG, carbsG, fatG, servingAmount).any { it != null }
}

/** A scanned label together with which row (index into the rows given to the parser) each number was read from. */
data class ParsedLabel(val label: ScannedLabel, val rowOf: Map<LabelField, Int>)

/**
 * Turns the text read from a photo of a US or Canadian Nutrition Facts label into numbers (the Canadian one is
 * bilingual: "Fat / Lipides 15 g", "Per 1 3/4 cups (100 g)"). Text recognition returns pieces
 * of text, not rows -- a label's big "Calories" and its "230" often come back as separate pieces -- so [rows] first puts
 * pieces that sit side by side back into one row, then [parse] looks for each nutrient in the rows. Pure, so it can be tested.
 */
object NutritionLabelParser {

    /** Joins pieces of text that sit on the same line of the picture into one row each, top to bottom, left to right. */
    fun rowBoxes(lines: List<OcrLine>): List<LabelRow> {
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
        return rows.map { row ->
            val ordered = row.sortedBy { it.left }
            LabelRow(
                text = ordered.joinToString(" ") { it.text.trim() },
                left = row.minOf { it.left },
                top = row.minOf { it.top },
                right = row.maxOf { max(it.right, it.left) },
                bottom = row.maxOf { it.bottom }
            )
        }
    }

    fun rows(lines: List<OcrLine>): List<String> = rowBoxes(lines).map { it.text }

    private const val NUMBER = """(\d+(?:[.,]\d+)?)"""
    private val calories = Regex("""calories\s*[:\-]?\s*$NUMBER(?!\s*(?:g\b|mg\b|%))""")
    // Labels from other countries give the energy as "1046 kJ / 250 kcal" instead.
    private val kcal = Regex("""$NUMBER\s*kcal\b""")
    // The nutrient's name (in English or French) then, within a few characters, its amount in grams.
    private val protein = Regex("""\bprotein\D{0,16}?<?\s*$NUMBER\s*g\b""")
    private val carbs = Regex("""\b(?:carbohydrate|carbs?\b|glucides)\D{0,16}?<?\s*$NUMBER\s*g\b""")
    private val fat = Regex("""\b(?:fat|lipides)\b\D{0,16}?<?\s*$NUMBER\s*g\b""")
    // The sub-rows under fat and carbohydrate must not be taken for the total.
    private val subRow = Regex("""saturated|satur[ée]s?|trans|poly|mono|sugar|sucres|fib(?:er|re)|starch|amidon""")
    // Grams or millilitres ("m1" is how a lowercase l is sometimes read).
    private const val UNIT = """(g|ml|m1)"""
    private val servingBracket = Regex("""\(\s*$NUMBER\s*$UNIT\s*\)""")
    private val servingPlain = Regex("""$NUMBER\s*$UNIT\b""")
    // "Per 100 g" / "Per 100 mL" with no bracket.
    private val perAmount = Regex("""^(?:per|pour)\s+$NUMBER\s*$UNIT\b""")
    private val perRow = Regex("""^(?:per|pour)\s""")

    /** "Og" is how a zero followed by g is often read. */
    private fun clean(row: String): String =
        row.lowercase().replace(Regex("""\b[o]\s*g\b"""), "0g").replace(" ", " ")

    private fun number(match: MatchResult): Double = match.groupValues[1].replace(',', '.').toDouble()

    fun parse(rows: List<String>): ScannedLabel = parseDetailed(rows).label

    /** Like [parse], also saying which row each number came from. */
    fun parseDetailed(rows: List<String>): ParsedLabel {
        val cleaned = rows.map(::clean)
        val rowOf = mutableMapOf<LabelField, Int>()

        fun find(field: LabelField, regex: Regex, skipSubRows: Boolean = false): Double? {
            for ((index, row) in cleaned.withIndex()) {
                if (skipSubRows && subRow.containsMatchIn(row)) continue
                val match = regex.find(row) ?: continue
                rowOf[field] = index
                return number(match)
            }
            return null
        }

        // "Serving size 2/3 cup (55g)" (US) or "Per 1 3/4 cups (100 g)" / "Per 1 cup (250 mL)" (Canada): the amount in
        // the bracket; failing that a plain "30g" on a "serving size" row, or "Per 100 g".
        val servingIndex = cleaned.indexOfFirst { it.contains("serving size") || it.contains("serv. size") }
            .takeIf { it >= 0 }
            ?: cleaned.indexOfFirst { row -> perRow.containsMatchIn(row) && (servingBracket.containsMatchIn(row) || perAmount.containsMatchIn(row)) }
                .takeIf { it >= 0 }
        var servingUnit = ServingUnit.GRAMS
        val servingAmount = servingIndex?.let { index ->
            val row = cleaned[index]
            (servingBracket.find(row) ?: servingPlain.find(row))?.let { match ->
                rowOf[LabelField.SERVING] = index
                servingUnit = if (match.groupValues[2] == "g") ServingUnit.GRAMS else ServingUnit.MILLILITRES
                number(match)
            }
        }

        val label = ScannedLabel(
            calories = find(LabelField.CALORIES, calories) ?: find(LabelField.CALORIES, kcal),
            proteinG = find(LabelField.PROTEIN, protein),
            carbsG = find(LabelField.CARBS, carbs, skipSubRows = true),
            fatG = find(LabelField.FAT, fat, skipSubRows = true),
            servingAmount = servingAmount?.takeIf { it > 0 },
            servingUnit = servingUnit
        )
        if (label.servingAmount == null) rowOf.remove(LabelField.SERVING)
        return ParsedLabel(label, rowOf)
    }
}
