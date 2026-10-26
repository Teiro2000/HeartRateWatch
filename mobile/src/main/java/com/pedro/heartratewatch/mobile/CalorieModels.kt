package com.pedro.heartratewatch.mobile

/** What a serving (and so the amount eaten) is measured in: foods are weighed in grams, drinks are usually measured in millilitres. */
enum class ServingUnit(val label: String) {
    GRAMS("g"),
    MILLILITRES("mL");

    companion object {
        fun fromLabel(text: String?): ServingUnit = entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: GRAMS
    }
}

/**
 * A food as saved in "My foods", with its numbers for ONE serving and how big that serving is (the way a nutrition
 * label gives them, so scanning one later fills in exactly this). Protein, carbs and fat are in grams; they can be 0
 * when only the calories are known.
 */
data class Food(
    val id: String,
    val name: String,
    val calories: Double,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    /** How much one serving is, in [unit]. */
    val servingAmount: Double = 100.0,
    val unit: ServingUnit = ServingUnit.GRAMS,
    /** When the photo the food was entered from was saved (see [FoodPhotos]), or 0 if there is none. */
    val photoAt: Long = 0L
) {
    val hasPhoto: Boolean get() = photoAt > 0
}

/**
 * Something eaten on one day. The numbers are the totals for what was eaten ([amount] of the food, worked out from its
 * per-serving numbers and serving size when it was logged), so changing or deleting the saved food never rewrites the past.
 */
data class FoodEntry(
    val id: String,
    /** The day it was eaten, as [java.time.LocalDate.toEpochDay]. */
    val epochDay: Long,
    val name: String,
    /** How much was eaten, in [unit]. */
    val amount: Double,
    val unit: ServingUnit = ServingUnit.GRAMS,
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val loggedAtMillis: Long
) {
    companion object {
        /** [amount] is in the food's own unit. */
        fun of(id: String, food: Food, amount: Double, epochDay: Long, loggedAtMillis: Long): FoodEntry {
            val per = amount / food.servingAmount
            return FoodEntry(
                id = id,
                epochDay = epochDay,
                name = food.name,
                amount = amount,
                unit = food.unit,
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

/** "250 mL", "30 g". */
fun formatQuantity(amount: Double, unit: ServingUnit): String = "${formatAmount(amount)} ${unit.label}"
