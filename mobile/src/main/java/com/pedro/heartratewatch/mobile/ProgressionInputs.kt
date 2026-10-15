package com.pedro.heartratewatch.mobile

import android.content.Context
import com.pedro.heartratewatch.shared.ActivityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Gathers what [Progression.breakdown] needs from the app's stores: histories, level settings, and your effort settings. */
object ProgressionInputs {

    /** Each activity's lower heart rate (from its settings and your max heart rate) and your workout templates. */
    fun effortFlow(context: Context): Flow<EffortContext> {
        val app = context.applicationContext
        val settings = SettingsRepository(app)
        val perActivity = combine(ActivityType.entries.map { type -> settings.settingsFlow(type).map { type to it } }) { it.toList() }
        return combine(WorkoutRepository(app).templatesFlow, perActivity, CalibrationRepository(app).latestFlow) { templates, list, calibration ->
            EffortContext(
                lowerBpm = list.associate { (type, s) -> type to s.resolvedLowerBpm(s.manualMaxHrBpm ?: calibration?.bpm) },
                templates = templates.associateBy { it.id }
            )
        }
    }

    /** The current breakdown, for code that isn't a screen (the daily reminder). */
    suspend fun breakdown(context: Context): Progression.Breakdown {
        val app = context.applicationContext
        val progression = ProgressionSettingsRepository(app)
        val penalties = progression.penaltiesFromFlow.first()
        return Progression.breakdown(
            runs = RunHistoryRepository(app).historyFlow.first(),
            workouts = WorkoutRepository(app).historyFlow.first(),
            sinceMillis = progression.resetAtFlow.first(),
            penaltiesFrom = penalties.takeIf { it != java.time.LocalDate.MAX },
            bonusXp = progression.bonusXpFlow.first(),
            effort = effortFlow(app).first()
        )
    }
}
