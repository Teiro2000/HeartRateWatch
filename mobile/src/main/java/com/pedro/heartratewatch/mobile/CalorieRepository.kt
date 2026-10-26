package com.pedro.heartratewatch.mobile

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.calorieDataStore by preferencesDataStore(name = "calories")

/**
 * What the calorie tracker keeps: everything eaten (by day), the saved foods (the food database, each with the photo it
 * was entered from), and the daily calorie goal. Phone-only, a small JSON string per list in a Preferences DataStore
 * like the workout data (so a backup includes it); the photos are files, see [FoodPhotos].
 */
class CalorieRepository(private val context: Context) {

    private object Keys {
        val ENTRIES = stringPreferencesKey("entries")
        val FOODS = stringPreferencesKey("foods")
        val GOAL = intPreferencesKey("goal")
        val WEIGH_INS = stringPreferencesKey("weigh_ins")
        val PLAN = stringPreferencesKey("weight_plan")
    }

    val entriesFlow: Flow<List<FoodEntry>> = context.calorieDataStore.data.map { prefs ->
        prefs[Keys.ENTRIES]?.let(::parseEntries).orEmpty()
    }

    /** Saved foods, most recently added or used first. */
    val foodsFlow: Flow<List<Food>> = context.calorieDataStore.data.map { prefs ->
        prefs[Keys.FOODS]?.let(::parseFoods).orEmpty()
    }

    /** The daily calorie goal, or null when none is set. */
    val goalFlow: Flow<Int?> = context.calorieDataStore.data.map { prefs -> prefs[Keys.GOAL]?.takeIf { it > 0 } }

    /** Weigh-ins, oldest first. */
    val weighInsFlow: Flow<List<WeighIn>> = context.calorieDataStore.data.map { prefs ->
        prefs[Keys.WEIGH_INS]?.let(::parseWeighIns).orEmpty()
    }

    val planFlow: Flow<WeightPlan?> = context.calorieDataStore.data.map { prefs -> prefs[Keys.PLAN]?.let(::parsePlan) }

    /** Records a weight (kg) for [epochDay], replacing one already there. */
    suspend fun addWeighIn(epochDay: Long, kg: Double) {
        context.calorieDataStore.edit { prefs ->
            val all = prefs[Keys.WEIGH_INS]?.let(::parseWeighIns).orEmpty().filterNot { it.epochDay == epochDay }
            prefs[Keys.WEIGH_INS] = weighInsToJson((all + WeighIn(epochDay, kg)).sortedBy { it.epochDay })
        }
    }

    suspend fun deleteWeighIn(epochDay: Long) {
        context.calorieDataStore.edit { prefs ->
            val all = prefs[Keys.WEIGH_INS]?.let(::parseWeighIns).orEmpty()
            prefs[Keys.WEIGH_INS] = weighInsToJson(all.filterNot { it.epochDay == epochDay })
        }
    }

    /** Saves the weight goal. A new plan starts learning today; editing keeps what it has learned so far. */
    suspend fun savePlan(plan: WeightPlan) {
        context.calorieDataStore.edit { prefs ->
            val existing = prefs[Keys.PLAN]?.let(::parsePlan)
            prefs[Keys.PLAN] = planToJson(if (existing != null) plan.copy(estimateStartDay = existing.estimateStartDay) else plan)
        }
    }

    /** Throws away what the estimate learned (after a long break, or a big change in routine) and starts it over from today. */
    suspend fun resetEstimate(today: Long) {
        context.calorieDataStore.edit { prefs ->
            prefs[Keys.PLAN]?.let(::parsePlan)?.let { prefs[Keys.PLAN] = planToJson(it.copy(estimateStartDay = today)) }
        }
    }

    suspend fun clearPlan() {
        context.calorieDataStore.edit { it.remove(Keys.PLAN) }
    }

    private fun weighInsToJson(list: List<WeighIn>): String = JSONArray().also { arr ->
        list.forEach { arr.put(JSONObject().put("day", it.epochDay).put("kg", it.kg)) }
    }.toString()

    private fun parseWeighIns(json: String): List<WeighIn> = JSONArray(json).let { arr ->
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { WeighIn(it.getLong("day"), it.getDouble("kg")) } }
    }

    private fun planToJson(p: WeightPlan): String = JSONObject()
        .put("target", p.targetKg).put("pace", p.pace.name).put("start", p.estimateStartDay)
        .also { o ->
            p.scaleBmr?.let { o.put("bmr", it) }
            p.heightCm?.let { o.put("height", it) }
            p.age?.let { o.put("age", it) }
            p.sex?.let { o.put("sex", it.name) }
        }.toString()

    private fun parsePlan(json: String): WeightPlan = JSONObject(json).let { o ->
        WeightPlan(
            targetKg = o.getDouble("target"),
            pace = Pace.entries.firstOrNull { it.name == o.optString("pace") } ?: Pace.STEADY,
            estimateStartDay = o.optLong("start", 0L),
            scaleBmr = o.optDouble("bmr").takeIf { !it.isNaN() },
            heightCm = o.optDouble("height").takeIf { !it.isNaN() },
            age = if (o.has("age")) o.getInt("age") else null,
            sex = Sex.entries.firstOrNull { it.name == o.optString("sex") }
        )
    }

    suspend fun setGoal(calories: Int?) {
        context.calorieDataStore.edit { if (calories == null || calories <= 0) it.remove(Keys.GOAL) else it[Keys.GOAL] = calories }
    }

    /**
     * Saves [food] to "My foods" without logging anything: replaces the saved food with the same id (or the same name),
     * or adds it at the top. A [photo] is kept with it. Returns the food as saved.
     */
    suspend fun saveFood(food: Food, photo: Bitmap? = null): Food {
        var saved = food
        context.calorieDataStore.edit { prefs ->
            val foods = prefs[Keys.FOODS]?.let(::parseFoods).orEmpty()
            val existing = foods.firstOrNull { it.id == food.id } ?: foods.firstOrNull { it.name.equals(food.name, ignoreCase = true) }
            val id = existing?.id ?: food.id
            if (photo != null) FoodPhotos.save(context, id, photo)
            saved = food.copy(id = id, photoAt = if (photo != null) System.currentTimeMillis() else existing?.photoAt ?: 0L)
            val replaced = if (existing != null) foods.map { if (it.id == id) saved else it } else listOf(saved) + foods
            // Renaming a food to a name that is already saved replaces that one.
            val (kept, dropped) = replaced.partition { it.id == id || !it.name.equals(saved.name, ignoreCase = true) }
            dropped.forEach { FoodPhotos.delete(context, it.id) }
            prefs[Keys.FOODS] = foodsToJson(kept)
        }
        return saved
    }

    /** Logs [amount] (in the food's own unit) of [food] on [epochDay], and moves the food to the top of "My foods". */
    suspend fun log(food: Food, amount: Double, epochDay: Long) {
        context.calorieDataStore.edit { prefs ->
            val entries = prefs[Keys.ENTRIES]?.let(::parseEntries).orEmpty()
            prefs[Keys.ENTRIES] = entriesToJson(
                entries + FoodEntry.of(newId(), food, amount, epochDay, System.currentTimeMillis())
            )
            val foods = prefs[Keys.FOODS]?.let(::parseFoods).orEmpty()
            val used = foods.firstOrNull { it.id == food.id }
            if (used != null) prefs[Keys.FOODS] = foodsToJson(listOf(used) + foods.filterNot { it.id == food.id })
        }
    }

    suspend fun deleteEntry(id: String) {
        context.calorieDataStore.edit { prefs ->
            val entries = prefs[Keys.ENTRIES]?.let(::parseEntries).orEmpty()
            prefs[Keys.ENTRIES] = entriesToJson(entries.filterNot { it.id == id })
        }
    }

    suspend fun deleteFood(id: String) {
        context.calorieDataStore.edit { prefs ->
            val foods = prefs[Keys.FOODS]?.let(::parseFoods).orEmpty()
            prefs[Keys.FOODS] = foodsToJson(foods.filterNot { it.id == id })
        }
        FoodPhotos.delete(context, id)
    }

    private fun entriesToJson(list: List<FoodEntry>): String = JSONArray().also { arr ->
        list.forEach { e ->
            arr.put(
                JSONObject().put("id", e.id).put("day", e.epochDay).put("name", e.name)
                    .put("amount", e.amount).put("unit", e.unit.label)
                    .put("kcal", e.calories).put("protein", e.proteinG).put("carbs", e.carbsG).put("fat", e.fatG)
                    .put("at", e.loggedAtMillis)
            )
        }
    }.toString()

    private fun parseEntries(json: String): List<FoodEntry> = JSONArray(json).let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            FoodEntry(
                id = o.getString("id"),
                epochDay = o.getLong("day"),
                name = o.getString("name"),
                // entries logged before drinks could be measured in mL only have "grams"
                amount = o.optDouble("amount", o.optDouble("grams", 0.0)),
                unit = ServingUnit.fromLabel(o.optString("unit", "g")),
                calories = o.optDouble("kcal", 0.0),
                proteinG = o.optDouble("protein", 0.0),
                carbsG = o.optDouble("carbs", 0.0),
                fatG = o.optDouble("fat", 0.0),
                loggedAtMillis = o.optLong("at", 0L)
            )
        }
    }

    private fun foodsToJson(list: List<Food>): String = JSONArray().also { arr ->
        list.forEach { f ->
            arr.put(
                JSONObject().put("id", f.id).put("name", f.name).put("kcal", f.calories)
                    .put("protein", f.proteinG).put("carbs", f.carbsG).put("fat", f.fatG)
                    .put("serving", f.servingAmount).put("unit", f.unit.label)
                    .also { if (f.hasPhoto) it.put("photo", f.photoAt) }
            )
        }
    }.toString()

    private fun parseFoods(json: String): List<Food> = JSONArray(json).let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Food(
                id = o.getString("id"),
                name = o.getString("name"),
                calories = o.optDouble("kcal", 0.0),
                proteinG = o.optDouble("protein", 0.0),
                carbsG = o.optDouble("carbs", 0.0),
                fatG = o.optDouble("fat", 0.0),
                servingAmount = o.optDouble("serving", 100.0).takeIf { it > 0 } ?: 100.0,
                unit = ServingUnit.fromLabel(o.optString("unit", "g")),
                photoAt = o.optLong("photo", 0L)
            )
        }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
