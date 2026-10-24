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
        assertEquals(55.0, scanned.servingGrams!!, 0.001)
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
        assertEquals(100.0, scanned.servingGrams!!, 0.001)
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
    fun aPerServingRowInCanadaWithoutAGramWeightLeavesItBlank() {
        assertNull(NutritionLabelParser.parse(listOf("Per 1 cup (250 mL)", "Calories 120")).servingGrams)
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
        assertEquals(40.0, scanned.servingGrams!!, 0.001)
    }

    @Test
    fun aLetterOReadInPlaceOfAZeroIsFixed() {
        assertEquals(0.0, NutritionLabelParser.parse(listOf("Protein Og")).proteinG!!, 0.001)
    }

    @Test
    fun aServingWithoutAGramWeightLeavesItBlank() {
        val scanned = NutritionLabelParser.parse(listOf("Serving size 1 cup (240mL)", "Calories 90"))
        assertNull(scanned.servingGrams)
        assertEquals(90.0, scanned.calories!!, 0.001)
    }

    @Test
    fun anOldStyleLabelWithCaloriesFromFatStillGivesTheCalories() {
        val scanned = NutritionLabelParser.parse(listOf("Serving Size 28g", "Calories 250 Calories from Fat 110"))
        assertEquals(250.0, scanned.calories!!, 0.001)
        assertEquals(28.0, scanned.servingGrams!!, 0.001)
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
