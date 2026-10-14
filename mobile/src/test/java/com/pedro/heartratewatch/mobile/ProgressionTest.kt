package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.RunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ProgressionTest {

    private fun run(type: ActivityType, km: Float, minutes: Int, pack: Float = 0f, star: Boolean = false, stoppedSeconds: Int = 0) =
        RunSummary(
            startedAtMillis = 0, durationSeconds = minutes * 60, avgBpm = 0, maxBpm = 0, minBpm = 0,
            distanceMeters = km * 1000f, activityType = type, packWeightLb = pack,
            stoppedSeconds = stoppedSeconds, reachedTarget = star
        )

    @Test
    fun cardioXpScalesWithDistanceTimeAndTheActivity() {
        assertEquals(330, Progression.cardioXp(run(ActivityType.RUN, 5f, 30)))        // 5 x 60 + 30
        assertEquals(380, Progression.cardioXp(run(ActivityType.RUN, 5f, 30, star = true)))
        assertEquals(30 * 3 + 60, Progression.cardioXp(run(ActivityType.WALK, 3f, 60)))
        // a ruck is worth more: 3 km x 30 x 1.5 + 60
        assertEquals(195, Progression.cardioXp(run(ActivityType.WALK, 3f, 60, pack = 50f)))
        // a bike ride's stopped time doesn't earn minutes
        assertEquals(20 * 10 + 50, Progression.cardioXp(run(ActivityType.BIKE, 10f, 60, stoppedSeconds = 600)))
    }

    @Test
    fun workoutXpCountsTheSessionSetsAndWeightLifted() {
        val sets = listOf(LoggedSet("a", 200.0, 5), LoggedSet("b", 200.0, 5), LoggedSet("c", null, 20), LoggedSet("d", 100.0, null))
        val workout = WorkoutLog("w", 0, templateName = "x", exercises = listOf(ExerciseLog("e", "ex", "Ex", sets)))
        // 100 + 5 x 3 sets with reps + (2000 lb / 200)
        assertEquals(125, Progression.workoutXp(workout))
        assertEquals(0, Progression.workoutXp(WorkoutLog("w2", 0, templateName = "x", exercises = emptyList())))
    }

    @Test
    fun streakXpBuildsUpOverConsecutiveDaysAndResetsAfterAGap() {
        val zone = ZoneId.of("UTC")
        fun at(day: String) = LocalDate.parse(day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val days = listOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-06").map(::at)
        // days 1,2,3 of a streak, then a fresh start: 5 + 10 + 15 + 5
        assertEquals(35, Progression.streakXp(days, zone))
    }

    @Test
    fun levelsAndRanksFollowTheCurve() {
        assertEquals(1, Progression.levelForXp(0))
        assertEquals(0, Progression.xpForLevel(1))
        for (level in listOf(2, 5, 10, 25, 50, 100)) {
            val needed = Progression.xpForLevel(level)
            assertEquals(level, Progression.levelForXp(needed))
            assertEquals(level - 1, Progression.levelForXp(needed - 1))
        }
        assertTrue(Progression.xpForLevel(10) in 4_000..6_000)
        assertEquals("Rookie", Progression.rankForLevel(4).name)
        assertEquals("Bronze", Progression.rankForLevel(5).name)
        assertEquals("Gold", Progression.rankForLevel(29).name)
        assertEquals("Legend", Progression.rankForLevel(250).name)
        val standing = Progression.standing(Progression.xpForLevel(10) + 100)
        assertEquals(10, standing.level)
        assertEquals(100, standing.xpIntoLevel)
        assertEquals("Gold", standing.nextRank?.name)
    }
}
