package com.pedro.heartratewatch.mobile

import android.content.Context
import kotlinx.coroutines.flow.first

/** Gathers what [Progression.breakdown] needs from the app's stores, for code that isn't a screen (the daily reminder). */
object ProgressionInputs {

    suspend fun breakdown(context: Context): Progression.Breakdown {
        val app = context.applicationContext
        val progression = ProgressionSettingsRepository(app)
        val penalties = progression.penaltiesFromFlow.first()
        return Progression.breakdown(
            runs = RunHistoryRepository(app).historyFlow.first(),
            workouts = WorkoutRepository(app).historyFlow.first(),
            sinceMillis = progression.resetAtFlow.first(),
            penaltiesFrom = penalties.takeIf { it != java.time.LocalDate.MAX }
        )
    }
}
