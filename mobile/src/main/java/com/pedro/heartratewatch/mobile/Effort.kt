package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.RunSummary
import kotlin.math.ceil
import kotlin.math.max

/**
 * What a session has to be to count: the bar for "you really trained today", as opposed to just
 * opening the app. A session below the bar still earns its XP, but doesn't keep the streak alive.
 * [lowerBpm] is each activity's own lower heart rate setting, and [templates] are your workout
 * templates by id; both come from your settings (see [ProgressionInputs]).
 */
data class EffortContext(
    val lowerBpm: Map<ActivityType, Int> = ActivityType.entries.associateWith { 100 },
    val templates: Map<String, WorkoutTemplate> = emptyMap()
)

object Effort {

    /** Cardio with a heart rate needs at least this long moving at or above the activity's lower heart rate. */
    const val MIN_CARDIO_MINUTES = 20

    /** Without a heart rate to judge effort by (a phone-only session), time alone has to be this long. */
    const val MIN_CARDIO_MINUTES_WITHOUT_HEART_RATE = 30

    /** A workout from a template counts when this share of the template's exercises were done. */
    const val TEMPLATE_SHARE = 0.8

    /** A workout without a template (or whose template is gone) counts at this many logged sets. */
    const val MIN_FREE_SETS = 12

    /**
     * Cardio counts if it met the distance target (the star), or its average heart rate was at least the
     * activity's lower heart rate for [MIN_CARDIO_MINUTES], or (no heart rate at all) it lasted [MIN_CARDIO_MINUTES_WITHOUT_HEART_RATE].
     */
    fun cardioQualifies(run: RunSummary, context: EffortContext): Boolean {
        if (run.reachedTarget) return true
        val movingMinutes = max(0, run.durationSeconds - run.stoppedSeconds) / 60
        if (run.avgBpm <= 0) return movingMinutes >= MIN_CARDIO_MINUTES_WITHOUT_HEART_RATE
        val lower = context.lowerBpm[run.activityType] ?: 100
        return run.avgBpm >= lower && movingMinutes >= MIN_CARDIO_MINUTES
    }

    fun workoutQualifies(workout: WorkoutLog, context: EffortContext): Boolean {
        val done = workout.exercises.filter { log -> log.sets.any { (it.reps ?: 0) > 0 } }
        val template = workout.templateId?.let { context.templates[it] }
        if (template != null && template.exerciseIds.isNotEmpty()) {
            val wanted = template.exerciseIds.distinct()
            val finished = wanted.count { id -> done.any { it.exerciseId == id } }
            return finished >= ceil(TEMPLATE_SHARE * wanted.size).toInt()
        }
        return done.sumOf { log -> log.sets.count { (it.reps ?: 0) > 0 } } >= MIN_FREE_SETS
    }
}
