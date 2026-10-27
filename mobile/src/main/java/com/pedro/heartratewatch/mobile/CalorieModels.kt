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
 * per-serving numbers and serving size), kept so that deleting the saved food doesn't change the past. Editing the saved
 * food does rewrite them (see [withEditedFood]). [foodId] is the saved food it came from; entries logged before that was
 * kept have none and are matched by name.
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
    val loggedAtMillis: Long,
    val foodId: String? = null
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
                loggedAtMillis = loggedAtMillis,
                foodId = food.id
            )
        }
    }
}

/**
 * [entries] with the ones eaten from [food] worked out again from its edited numbers, name and unit (keeping how much was
 * eaten). Matches by food id, by the ids of foods it replaced ([replacedIds]), or, for old entries with no id, by the
 * name the food had before ([previousName]).
 */
fun withEditedFood(entries: List<FoodEntry>, food: Food, previousName: String, replacedIds: Set<String> = emptySet()): List<FoodEntry> =
    entries.map { e ->
        val ofThisFood = e.foodId == food.id || (e.foodId != null && e.foodId in replacedIds) ||
            (e.foodId == null && e.name.equals(previousName, ignoreCase = true))
        if (ofThisFood) FoodEntry.of(e.id, food, e.amount, e.epochDay, e.loggedAtMillis) else e
    }

/** What a day's food adds up to. */
data class DayTotals(val calories: Double, val proteinG: Double, val carbsG: Double, val fatG: Double)

fun dayTotals(entries: List<FoodEntry>, epochDay: Long): DayTotals {
    val day = entries.filter { it.epochDay == epochDay }
    return DayTotals(day.sumOf { it.calories }, day.sumOf { it.proteinG }, day.sumOf { it.carbsG }, day.sumOf { it.fatG })
}

object CalorieChecks {
    /**
     * True when the calories are far from what the macros add up to (4 kcal per g of protein or carbs, 9 per g of fat;
     * fibre and label rounding allow some slack). A zero dropped from "240" shows up this way. False when no macros are given.
     */
    fun caloriesLookOff(calories: Double, proteinG: Double, carbsG: Double, fatG: Double): Boolean {
        val fromMacros = proteinG * 4 + carbsG * 4 + fatG * 9
        if (fromMacros < 20) return false
        return calories < fromMacros * 0.7 || calories > fromMacros * 1.3
    }
}

/** "120" for a whole number, "12.5" otherwise. */
fun formatAmount(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded == Math.rint(rounded)) "%d".format(rounded.toLong()) else "%.1f".format(rounded)
}

/** "250 mL", "30 g". */
fun formatQuantity(amount: Double, unit: ServingUnit): String = "${formatAmount(amount)} ${unit.label}"
