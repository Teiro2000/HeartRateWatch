package com.pedro.heartratewatch.mobile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.DistanceUnit
import com.pedro.heartratewatch.shared.RunSummary
import com.pedro.heartratewatch.shared.formatDistance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** Something that just beat your previous best ("Fastest 5 km: 24:10"), and when. */
data class RecordEvent(val timeMillis: Long, val text: String)

/** Works out which personal bests a finished cardio session set. Pure, so it can be tested. */
object Records {

    /**
     * [history] is everything saved before [session]. The first session of an activity is only a
     * baseline and sets no records. After that: a faster 1, 5 or 10 km split than any earlier run
     * (or the first time a run got that far), and the longest distance for that activity.
     */
    fun cardio(history: List<RunSummary>, session: RunSummary, unit: DistanceUnit): List<String> {
        val earlier = history.filter { it.activityType == session.activityType && it.startedAtMillis != session.startedAtMillis }
        if (earlier.isEmpty()) return emptyList()
        val out = mutableListOf<String>()

        if (session.activityType == ActivityType.RUN) {
            fun split(label: String, pick: (RunSummary) -> Int?) {
                val now = pick(session) ?: return
                val before = earlier.mapNotNull(pick)
                when {
                    before.isEmpty() -> out += "First $label run"
                    now < before.min() -> out += "Fastest $label: ${minutesSeconds(now)}"
                }
            }
            split("1 km") { it.best1kmSeconds }
            split("5 km") { it.best5kmSeconds }
            split("10 km") { it.best10kmSeconds }
        }

        if (session.distanceMeters > 0f && session.distanceMeters > earlier.maxOf { it.distanceMeters }) {
            out += "Longest ${noun(session.activityType)}: ${formatDistance(session.distanceMeters, unit)}"
        }
        return out
    }

    fun minutesSeconds(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun noun(type: ActivityType) = when (type) {
        ActivityType.RUN -> "run"
        ActivityType.WALK -> "walk"
        ActivityType.BIKE -> "ride"
        ActivityType.STATIONARY_BIKE -> "stationary ride"
    }
}

private val Context.recordsDataStore by preferencesDataStore(name = "records")

/**
 * Every personal best set so far, newest last, plus how far the person has seen: anything after
 * [seenUpToFlow] is new and shows as a banner on the main menu until dismissed. The weekly recap
 * reads the same list to show the week's bests.
 */
class RecordsRepository(private val context: Context) {

    private val eventsKey = stringPreferencesKey("events")
    private val seenKey = longPreferencesKey("seen_up_to")

    val eventsFlow: Flow<List<RecordEvent>> = context.recordsDataStore.data.map { parse(it[eventsKey]) }

    /** Records set since the banner was last dismissed. */
    val pendingFlow: Flow<List<RecordEvent>> = context.recordsDataStore.data.map { prefs ->
        val seen = prefs[seenKey] ?: 0L
        parse(prefs[eventsKey]).filter { it.timeMillis > seen }
    }

    suspend fun add(events: List<RecordEvent>) {
        if (events.isEmpty()) return
        context.recordsDataStore.edit { prefs ->
            val all = (parse(prefs[eventsKey]) + events).takeLast(MAX_EVENTS)
            prefs[eventsKey] = JSONArray().also { arr ->
                all.forEach { arr.put(JSONObject().put("t", it.timeMillis).put("text", it.text)) }
            }.toString()
        }
    }

    /** Hides the banner for everything recorded so far. */
    suspend fun markSeen() {
        val newest = eventsFlow.first().maxOfOrNull { it.timeMillis } ?: return
        context.recordsDataStore.edit { it[seenKey] = newest }
    }

    private fun parse(json: String?): List<RecordEvent> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            JSONArray(json).let { arr -> List(arr.length()) { i -> arr.getJSONObject(i).let { RecordEvent(it.getLong("t"), it.getString("text")) } } }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val MAX_EVENTS = 300
    }
}
