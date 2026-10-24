package com.pedro.heartratewatch.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalorieTest {

    private val oats = Food("f1", "Oats", calories = 150.0, proteinG = 5.0, carbsG = 27.0, fatG = 3.0)

    @Test
    fun anEntryScalesTheFoodsPer100gNumbersByTheWeightEaten() {
        val entry = FoodEntry.of("e1", oats, 150.0, epochDay = 100, loggedAtMillis = 0)
        assertEquals(225.0, entry.calories, 0.001)
        assertEquals(7.5, entry.proteinG, 0.001)
        assertEquals(40.5, entry.carbsG, 0.001)
        assertEquals(4.5, entry.fatG, 0.001)
    }

    @Test
    fun theWeightEatenIsComparedWithTheServingWeight() {
        // a 30 g serving has 150 kcal and 5 g protein; eating 45 g is one and a half servings
        val granola = Food("g", "Granola", calories = 150.0, proteinG = 5.0, servingGrams = 30.0)
        val entry = FoodEntry.of("e", granola, 45.0, 100, 0)
        assertEquals(225.0, entry.calories, 0.001)
        assertEquals(7.5, entry.proteinG, 0.001)
        assertEquals(45.0, entry.grams, 0.001)
    }

    @Test
    fun aDayAddsUpOnlyItsOwnEntries() {
        val entries = listOf(
            FoodEntry.of("a", oats, 100.0, 100, 1),
            FoodEntry.of("b", oats, 200.0, 100, 2),
            FoodEntry.of("c", oats, 100.0, 101, 3)
        )
        assertEquals(450.0, dayTotals(entries, 100).calories, 0.001)
        assertEquals(15.0, dayTotals(entries, 100).proteinG, 0.001)
        assertEquals(150.0, dayTotals(entries, 101).calories, 0.001)
        assertEquals(0.0, dayTotals(entries, 99).calories, 0.001)
    }

    @Test
    fun amountsShowWithoutNeedlessDecimals() {
        assertEquals("120", formatAmount(120.0))
        assertEquals("12.5", formatAmount(12.5))
        assertEquals("225", formatAmount(224.96))
    }

    @Test
    fun exercisesNeedAtLeastOneSetWithAWeightOrReps() {
        assertFalse(ExerciseLog("e", "x", "X").hasData)
        assertFalse(ExerciseLog("e", "x", "X", listOf(LoggedSet("s"))).hasData)
        assertTrue(ExerciseLog("e", "x", "X", listOf(LoggedSet("s"), LoggedSet("t", reps = 8))).hasData)
        assertTrue(ExerciseLog("e", "x", "X", listOf(LoggedSet("s", weight = 20.0))).hasData)
    }

    @Test
    fun theStreakReminderSaysHowMuchXpIsStillNeeded() {
        assertEquals("You need 300 XP to complete today's streak.", StreakReminder.reminderText(0))
        assertEquals("You need 176 more XP to complete today's streak (124 of 300 so far).", StreakReminder.reminderText(124))
        assertEquals("You need 1 more XP to complete today's streak (299 of 300 so far).", StreakReminder.reminderText(299))
    }
}
