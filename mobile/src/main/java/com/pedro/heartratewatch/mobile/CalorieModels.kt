package com.pedro.heartratewatch.mobile

/**
 * A food as saved in "My foods", with its numbers for ONE serving (so scanning a nutrition label later fills in
 * exactly this). Protein, carbs and fat are in grams; they can be 0 when only the calories are known.
 */
data class Food(
    val id: String,
    val name: String,
    val calories: Double,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0
)

/**
 * Something eaten on one day. The numbers are the totals for what was eaten ([servings] times the food's per-serving
 * numbers, worked out when it was logged), so changing or deleting the saved food never rewrites the past.
 */
data class FoodEntry(
    val id: String,
    /** The day it was eaten, as [java.time.LocalDate.toEpochDay]. */
    val epochDay: Long,
    val name: String,
    val servings: Double,
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val loggedAtMillis: Long
) {
    companion object {
        fun of(id: String, food: Food, servings: Double, epochDay: Long, loggedAtMillis: Long) = FoodEntry(
            id = id,
            epochDay = epochDay,
            name = food.name,
            servings = servings,
            calories = food.calories * servings,
            proteinG = food.proteinG * servings,
            carbsG = food.carbsG * servings,
            fatG = food.fatG * servings,
            loggedAtMillis = loggedAtMillis
        )
    }
}

/** What a day's food adds up to. */
data class DayTotals(val calories: Double, val proteinG: Double, val carbsG: Double, val fatG: Double)

fun dayTotals(entries: List<FoodEntry>, epochDay: Long): DayTotals {
    val day = entries.filter { it.epochDay == epochDay }
    return DayTotals(day.sumOf { it.calories }, day.sumOf { it.proteinG }, day.sumOf { it.carbsG }, day.sumOf { it.fatG })
}

/** "120" for a whole number, "12.5" otherwise. */
fun formatAmount(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded == Math.rint(rounded)) "%d".format(rounded.toLong()) else "%.1f".format(rounded)
}
