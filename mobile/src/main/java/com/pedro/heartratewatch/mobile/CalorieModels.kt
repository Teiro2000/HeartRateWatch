package com.pedro.heartratewatch.mobile

/**
 * A food as saved in "My foods", with its numbers per 100 g (the way nutrition labels list them, so scanning one
 * later fills in exactly this). Protein, carbs and fat are in grams; they can be 0 when only the calories are known.
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
 * Something eaten on one day. The numbers are the totals for what was eaten ([grams] of the food, worked out from its
 * per-100 g numbers when it was logged), so changing or deleting the saved food never rewrites the past.
 */
data class FoodEntry(
    val id: String,
    /** The day it was eaten, as [java.time.LocalDate.toEpochDay]. */
    val epochDay: Long,
    val name: String,
    /** The weight eaten, in grams. */
    val grams: Double,
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val loggedAtMillis: Long
) {
    companion object {
        fun of(id: String, food: Food, grams: Double, epochDay: Long, loggedAtMillis: Long): FoodEntry {
            val per = grams / 100.0
            return FoodEntry(
                id = id,
                epochDay = epochDay,
                name = food.name,
                grams = grams,
                calories = food.calories * per,
                proteinG = food.proteinG * per,
                carbsG = food.carbsG * per,
                fatG = food.fatG * per,
                loggedAtMillis = loggedAtMillis
            )
        }
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
