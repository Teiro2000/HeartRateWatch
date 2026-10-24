package com.pedro.heartratewatch.mobile

import android.content.Context
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
 * What the calorie tracker keeps: everything eaten (by day), the saved foods, and the daily calorie goal. Phone-only,
 * a small JSON string per list in a Preferences DataStore like the workout data (so a backup includes it).
 */
class CalorieRepository(private val context: Context) {

    private object Keys {
        val ENTRIES = stringPreferencesKey("entries")
        val FOODS = stringPreferencesKey("foods")
        val GOAL = intPreferencesKey("goal")
    }

    val entriesFlow: Flow<List<FoodEntry>> = context.calorieDataStore.data.map { prefs ->
        prefs[Keys.ENTRIES]?.let(::parseEntries).orEmpty()
    }

    /** Saved foods, most recently used first. */
    val foodsFlow: Flow<List<Food>> = context.calorieDataStore.data.map { prefs ->
        prefs[Keys.FOODS]?.let(::parseFoods).orEmpty()
    }

    /** The daily calorie goal, or null when none is set. */
    val goalFlow: Flow<Int?> = context.calorieDataStore.data.map { prefs -> prefs[Keys.GOAL]?.takeIf { it > 0 } }

    suspend fun setGoal(calories: Int?) {
        context.calorieDataStore.edit { if (calories == null || calories <= 0) it.remove(Keys.GOAL) else it[Keys.GOAL] = calories }
    }

    /** Logs [grams] of [food] on [epochDay], and keeps the food in "My foods" (updating it if the name is already there). */
    suspend fun log(food: Food, grams: Double, epochDay: Long) {
        context.calorieDataStore.edit { prefs ->
            val entries = prefs[Keys.ENTRIES]?.let(::parseEntries).orEmpty()
            prefs[Keys.ENTRIES] = entriesToJson(
                entries + FoodEntry.of(newId(), food, grams, epochDay, System.currentTimeMillis())
            )
            val foods = prefs[Keys.FOODS]?.let(::parseFoods).orEmpty()
            val others = foods.filterNot { it.name.equals(food.name, ignoreCase = true) }
            val saved = food.copy(id = foods.firstOrNull { it.name.equals(food.name, ignoreCase = true) }?.id ?: food.id)
            prefs[Keys.FOODS] = foodsToJson(listOf(saved) + others)
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
    }

    private fun entriesToJson(list: List<FoodEntry>): String = JSONArray().also { arr ->
        list.forEach { e ->
            arr.put(
                JSONObject().put("id", e.id).put("day", e.epochDay).put("name", e.name).put("grams", e.grams)
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
                grams = o.optDouble("grams", 0.0),
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
                    .put("serving", f.servingGrams)
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
                servingGrams = o.optDouble("serving", 100.0).takeIf { it > 0 } ?: 100.0
            )
        }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
