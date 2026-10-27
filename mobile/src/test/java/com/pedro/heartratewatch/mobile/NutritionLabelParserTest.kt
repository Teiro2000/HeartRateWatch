package com.pedro.heartratewatch.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionLabelParserTest {

    /** The label in the app's design notes, as text recognition would return it row by row. */
    private val label = listOf(
        "Nutrition Facts",
        "8 servings per container",
        "Serving size 2/3 cup (55g)",
        "Amount per serving",
        "Calories 230",
        "% Daily Value*",
        "Total Fat 8g 10%",
        "Saturated Fat 1g 5%",
        "Trans Fat 0g",
        "Cholesterol 0mg 0%",
        "Sodium 160mg 7%",
        "Total Carbohydrate 37g 13%",
        "Dietary Fiber 4g 14%",
        "Total Sugars 12g",
        "Includes 10g Added Sugars 20%",
        "Protein 3g",
        "Vitamin D 2mcg 10%",
        "Calcium 260mg 20%",
        "* The % Daily Value (DV) tells you how much a nutrient in a serving of food contributes to a daily diet. 2,000 calories a day is used for general nutrition advice."
    )

    @Test
    fun readsTheNutrientsAndServingWeightOffALabel() {
        val scanned = NutritionLabelParser.parse(label)
        assertEquals(230.0, scanned.calories!!, 0.001)
        assertEquals(8.0, scanned.fatG!!, 0.001)
        assertEquals(37.0, scanned.carbsG!!, 0.001)
        assertEquals(3.0, scanned.proteinG!!, 0.001)
        assertEquals(55.0, scanned.servingAmount!!, 0.001)
        assertEquals(ServingUnit.GRAMS, scanned.servingUnit)
    }

    /** A Canadian bilingual label, row by row. */
    private val canadianLabel = listOf(
        "Nutrition Facts",
        "Valeur nutritive",
        "Per 1 ¾ cups (100 g)",
        "pour 1 ¾ tasses (100 g)",
        "% Daily Value*",
        "% valeur quotidienne*",
        "Calories 180",
        "Fat / Lipides 15 g 20 %",
        "Saturated / saturés 1 g",
        "+ Trans / trans 0 g 6 %",
        "Carbohydrate / Glucides 59 g",
        "Fibre / Fibres 4 g 13 %",
        "Sugars / Sucres 3 g 3 %",
        "Protein / Protéines 4 g",
        "Cholesterol / Cholestérol 0 mg",
        "Sodium 650 mg 28 %",
        "Potassium 125 mg 2 %",
        "Calcium 40 mg 3 %",
        "Iron / Fer 2 mg 11 %"
    )

    @Test
    fun readsACanadianBilingualLabel() {
        val scanned = NutritionLabelParser.parse(canadianLabel)
        assertEquals(180.0, scanned.calories!!, 0.001)
        assertEquals(15.0, scanned.fatG!!, 0.001)
        assertEquals(59.0, scanned.carbsG!!, 0.001)
        assertEquals(4.0, scanned.proteinG!!, 0.001)
        assertEquals(100.0, scanned.servingAmount!!, 0.001)
    }

    @Test
    fun saysWhichRowEachNumberCameFrom() {
        val parsed = NutritionLabelParser.parseDetailed(label)
        assertEquals(label.indexOf("Calories 230"), parsed.rowOf[LabelField.CALORIES])
        assertEquals(label.indexOf("Total Fat 8g 10%"), parsed.rowOf[LabelField.FAT])
        assertEquals(label.indexOf("Total Carbohydrate 37g 13%"), parsed.rowOf[LabelField.CARBS])
        assertEquals(label.indexOf("Protein 3g"), parsed.rowOf[LabelField.PROTEIN])
        assertEquals(label.indexOf("Serving size 2/3 cup (55g)"), parsed.rowOf[LabelField.SERVING])
    }

    @Test
    fun rowBoxesCoverEveryPieceOfTheRow() {
        val rows = NutritionLabelParser.rowBoxes(
            listOf(
                OcrLine("Calories", left = 20, top = 275, bottom = 315, right = 200),
                OcrLine("230", left = 500, top = 270, bottom = 320, right = 620)
            )
        )
        assertEquals(1, rows.size)
        assertEquals(LabelRow("Calories 230", left = 20, top = 270, right = 620, bottom = 320), rows[0])
    }

    @Test
    fun theSaturatedAndFibreRowsAreNotTakenForTheTotals() {
        // even if text recognition returns the sub-rows before the totals
        val scanned = NutritionLabelParser.parse(
            listOf("Saturated Fat 1g 5%", "Dietary Fibre 4 g", "Total Fat 8 g", "Total Carbohydrate 37 g")
        )
        assertEquals(8.0, scanned.fatG!!, 0.001)
        assertEquals(37.0, scanned.carbsG!!, 0.001)
    }

    @Test
    fun aCanadianServingGivenInMillilitresIsReadInMillilitres() {
        val scanned = NutritionLabelParser.parse(
            listOf("Nutrition Facts", "Valeur nutritive", "Per 1 cup (250 mL)", "pour 1 tasse (250 mL)", "Calories 130", "Fat / Lipides 5 g")
        )
        assertEquals(250.0, scanned.servingAmount!!, 0.001)
        assertEquals(ServingUnit.MILLILITRES, scanned.servingUnit)
        assertEquals(130.0, scanned.calories!!, 0.001)
    }

    @Test
    fun aPlainPerHundredRowIsTheServing() {
        val grams = NutritionLabelParser.parse(listOf("Per 100 g", "Calories 80"))
        assertEquals(100.0, grams.servingAmount!!, 0.001)
        assertEquals(ServingUnit.GRAMS, grams.servingUnit)
        assertEquals(ServingUnit.MILLILITRES, NutritionLabelParser.parse(listOf("Per 100 mL", "Calories 40")).servingUnit)
    }

    @Test
    fun decimalCommasAndKcalEnergyAreRead() {
        val scanned = NutritionLabelParser.parse(listOf("Energy 1046 kJ / 250 kcal", "Fat 3,5 g", "Protein 12 g"))
        assertEquals(250.0, scanned.calories!!, 0.001)
        assertEquals(3.5, scanned.fatG!!, 0.001)
    }

    @Test
    fun theFootnoteAboutTwoThousandCaloriesIsNotTheCalories() {
        val scanned = NutritionLabelParser.parse(listOf("2,000 calories a day is used for general nutrition advice."))
        assertNull(scanned.calories)
        assertFalse(scanned.foundAnything)
    }

    @Test
    fun decimalsAndLessThanSignsAreRead() {
        val scanned = NutritionLabelParser.parse(
            listOf("Serving size 1 bar (40g)", "Calories 150", "Total Fat 3.5g", "Total Carbohydrate <1g", "Protein 12.5 g")
        )
        assertEquals(3.5, scanned.fatG!!, 0.001)
        assertEquals(1.0, scanned.carbsG!!, 0.001)
        assertEquals(12.5, scanned.proteinG!!, 0.001)
        assertEquals(40.0, scanned.servingAmount!!, 0.001)
    }

    @Test
    fun aLetterOReadInPlaceOfAZeroIsFixed() {
        assertEquals(0.0, NutritionLabelParser.parse(listOf("Protein Og")).proteinG!!, 0.001)
    }

    @Test
    fun aServingWithNoWeightOrVolumeLeavesItBlank() {
        val scanned = NutritionLabelParser.parse(listOf("Serving size 1 cup", "Calories 90"))
        assertNull(scanned.servingAmount)
        assertEquals(90.0, scanned.calories!!, 0.001)
    }

    @Test
    fun anOldStyleLabelWithCaloriesFromFatStillGivesTheCalories() {
        val scanned = NutritionLabelParser.parse(listOf("Serving Size 28g", "Calories 250 Calories from Fat 110"))
        assertEquals(250.0, scanned.calories!!, 0.001)
        assertEquals(28.0, scanned.servingAmount!!, 0.001)
    }

    @Test
    fun caloriesFollowedByADailyValueColumnKeepAllTheirDigits() {
        // "240 %" must not back off to "24" because a number followed by a percent isn't taken.
        assertEquals(240.0, NutritionLabelParser.parse(listOf("Calories 240 % Daily Value")).calories!!, 0.001)
        assertEquals(130.0, NutritionLabelParser.parse(listOf("Calories 130 %")).calories!!, 0.001)
        assertEquals(90.0, NutritionLabelParser.parse(listOf("Calories 90 % valeur quotidienne")).calories!!, 0.001)
    }

    @Test
    fun caloriesThatDontMatchTheMacrosAreFlagged() {
        assertTrue(CalorieChecks.caloriesLookOff(13.0, 27.0, 3.0, 1.5))   // 130 with the zero dropped
        assertTrue(CalorieChecks.caloriesLookOff(9.0, 3.0, 3.0, 8.0))
        assertFalse(CalorieChecks.caloriesLookOff(240.0, 10.0, 2.0, 21.0))
        assertFalse(CalorieChecks.caloriesLookOff(105.0, 1.0, 27.0, 0.0))
        assertFalse(CalorieChecks.caloriesLookOff(5.0, 0.0, 0.0, 0.0))    // no macros entered, nothing to compare
    }

    @Test
    fun piecesOnTheSameLineOfThePictureJoinIntoOneRow() {
        // The big "Calories" and "230" come back separately, side by side; the next row is lower down.
        val rows = NutritionLabelParser.rows(
            listOf(
                OcrLine("230", left = 500, top = 270, bottom = 320),
                OcrLine("Protein 3g", left = 20, top = 400, bottom = 430),
                OcrLine("Calories", left = 20, top = 275, bottom = 315),
                OcrLine("Total Fat", left = 20, top = 340, bottom = 370),
                OcrLine("8g", left = 150, top = 342, bottom = 372)
            )
        )
        assertEquals(listOf("Calories 230", "Total Fat 8g", "Protein 3g"), rows)
        val scanned = NutritionLabelParser.parse(rows)
        assertTrue(scanned.calories == 230.0 && scanned.fatG == 8.0 && scanned.proteinG == 3.0)
    }
}
