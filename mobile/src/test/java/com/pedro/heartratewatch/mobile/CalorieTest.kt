package com.pedro.heartratewatch.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val granola = Food("g", "Granola", calories = 150.0, proteinG = 5.0, servingAmount = 30.0)
        val entry = FoodEntry.of("e", granola, 45.0, 100, 0)
        assertEquals(225.0, entry.calories, 0.001)
        assertEquals(7.5, entry.proteinG, 0.001)
        assertEquals(45.0, entry.amount, 0.001)
        assertEquals(ServingUnit.GRAMS, entry.unit)
    }

    @Test
    fun aDrinkMeasuredInMillilitresIsLoggedInMillilitres() {
        // milk: 130 kcal and 9 g protein per 250 mL; drinking 500 mL is two servings
        val milk = Food("m", "Milk", calories = 130.0, proteinG = 9.0, servingAmount = 250.0, unit = ServingUnit.MILLILITRES)
        val entry = FoodEntry.of("e", milk, 500.0, 100, 0)
        assertEquals(260.0, entry.calories, 0.001)
        assertEquals(18.0, entry.proteinG, 0.001)
        assertEquals(ServingUnit.MILLILITRES, entry.unit)
        assertEquals("500 mL", formatQuantity(entry.amount, entry.unit))
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

    private val today = 1000L
    private val plan = WeightPlan(targetKg = 70.0, pace = Pace.STEADY, estimateStartDay = 0L, scaleBmr = 1700.0)

    /** A day's worth of food. */
    private fun ate(day: Long, kcal: Double) = FoodEntry("e$day", day, "Food", 100.0, ServingUnit.GRAMS, kcal, 0.0, 0.0, 0.0, 0L)

    @Test
    fun withNoDataTheGoalIsAGuessFromTheBmrAndRecentXp() {
        // 2100 XP in the last 7 days: 1700 x 1.2 + 2100 x 1.1 / 7 = 2370 kcal to maintain; 0.25 kg a week off is 275 kcal a day
        val xp = (today - 7 until today).associateWith { 300 }
        val result = CalorieTargets.compute(plan, listOf(WeighIn(today, 80.0)), emptyList(), xp, today)!!
        assertEquals(EstimateSource.STARTING_GUESS, result.source)
        assertEquals(2370.0, result.maintenance, 0.5)
        assertEquals(2095, result.goal)
        assertEquals(-275.0, result.dailyChange, 0.5)
    }

    @Test
    fun noExerciseLowersTheStartingGuess() {
        val result = CalorieTargets.compute(plan, listOf(WeighIn(today, 80.0)), emptyList(), emptyMap(), today)!!
        assertEquals(2040.0, result.maintenance, 0.5)
    }

    @Test
    fun theGoalIsNeverBelowTheBmrOrTheMinimum() {
        val small = plan.copy(scaleBmr = 1500.0, pace = Pace.FAST)
        val result = CalorieTargets.compute(small, listOf(WeighIn(today, 80.0)), emptyList(), emptyMap(), today)!!
        // maintenance 1800, minus 550 would be 1250, but the goal stays at the BMR
        assertEquals(1500, result.goal)
        assertTrue(result.flooredAtMinimum)
    }

    @Test
    fun atTheTargetWeightTheGoalIsMaintenance() {
        val result = CalorieTargets.compute(plan, listOf(WeighIn(today, 70.1)), emptyList(), emptyMap(), today)!!
        assertEquals(0.0, result.dailyChange, 0.001)
        assertEquals(result.maintenance.toInt(), result.goal)
    }

    @Test
    fun aTargetAboveTheCurrentWeightAddsCalories() {
        val result = CalorieTargets.compute(plan, listOf(WeighIn(today, 65.0)), emptyList(), emptyMap(), today)!!
        assertTrue(result.dailyChange > 0)
    }

    @Test
    fun measuredMaintenanceIsWhatWasEatenPlusWhatTheWeightLoss() {
        // 28 days at 2000 kcal while weight fell 0.1 kg a day: burn = 2000 + 0.1 x 7700 = 2770 kcal
        val weighIns = (today - 28..today).map { WeighIn(it, 80.0 - 0.1 * (it - (today - 28))) }
        val entries = (today - 28 until today).map { ate(it, 2000.0) }
        val xp = (today - 28 until today).associateWith { 300 }
        val result = CalorieTargets.compute(plan, weighIns, entries, xp, today)!!
        assertEquals(EstimateSource.MEASURED, result.source)
        assertEquals(2770.0, result.maintenance, 5.0)
    }

    @Test
    fun exercisingLessThanUsualLowersAMeasuredGoal() {
        val weighIns = (today - 27..today).map { WeighIn(it, 80.0) }
        val entries = (today - 28 until today).map { ate(it, 2400.0) }
        val usual = (today - 28 until today).associateWith { 300 }
        val restWeek = usual + (today - 7 until today).associateWith { 0 }
        val steady = CalorieTargets.compute(plan, weighIns, entries, usual, today)!!
        val rested = CalorieTargets.compute(plan, weighIns, entries, restWeek, today)!!
        assertTrue(rested.maintenance < steady.maintenance - 200)
    }

    @Test
    fun fewDaysOfDataStillGiveTheStartingGuess() {
        val weighIns = (today - 5..today).map { WeighIn(it, 80.0) }
        val entries = (today - 5 until today).map { ate(it, 2000.0) }
        assertEquals(EstimateSource.STARTING_GUESS, CalorieTargets.compute(plan, weighIns, entries, emptyMap(), today)!!.source)
    }

    @Test
    fun resettingTheEstimateIgnoresEarlierData() {
        val weighIns = (today - 27..today).map { WeighIn(it, 80.0) }
        val entries = (today - 28 until today).map { ate(it, 2400.0) }
        val reset = plan.copy(estimateStartDay = today - 2)
        assertEquals(EstimateSource.STARTING_GUESS, CalorieTargets.compute(reset, weighIns, entries, emptyMap(), today)!!.source)
    }

    @Test
    fun theBmrCanBeWorkedOutFromHeightAgeAndSex() {
        // Mifflin-St Jeor: 10 x 80 + 6.25 x 180 - 5 x 30 + 5 = 1780
        val profile = WeightPlan(70.0, Pace.STEADY, 0L, heightCm = 180.0, age = 30, sex = Sex.MALE)
        assertEquals(1780.0, profile.bmr(80.0)!!, 0.001)
        assertEquals(1614.0, profile.copy(sex = Sex.FEMALE).bmr(80.0)!!, 0.001)
        assertNull(profile.bmr(null))
        assertNull(CalorieTargets.compute(profile, emptyList(), emptyList(), emptyMap(), today))
        assertEquals(1780.0, CalorieTargets.compute(profile, listOf(WeighIn(today, 80.0)), emptyList(), emptyMap(), today)!!.bmr, 0.001)
    }

    @Test
    fun theScaleBmrWinsOverTheFormula() {
        val both = WeightPlan(70.0, Pace.STEADY, 0L, scaleBmr = 1900.0, heightCm = 180.0, age = 30, sex = Sex.MALE)
        assertEquals(1900.0, both.bmr(80.0)!!, 0.001)
    }

    @Test
    fun weightUnitsConvertBothWays() {
        assertEquals(176.37, WeightUnit.LB.fromKg(80.0), 0.01)
        assertEquals(80.0, WeightUnit.LB.toKg(WeightUnit.LB.fromKg(80.0)), 0.0001)
    }

    @Test
    fun editingAFoodRewritesTheEntriesLoggedFromIt() {
        val old = Food(id = "f1", name = "Cheese", calories = 12.0, proteinG = 7.0, carbsG = 1.0, fatG = 10.0, servingAmount = 30.0)
        val fixed = old.copy(calories = 120.0)
        val other = Food(id = "f2", name = "Eggs", calories = 160.0, servingAmount = 105.0)
        val linked = FoodEntry.of("a", old, 63.0, 100, 1L)
        val legacy = linked.copy(id = "b", foodId = null) // logged before entries kept the food's id
        val unrelated = FoodEntry.of("c", other, 105.0, 100, 1L)
        val result = withEditedFood(listOf(linked, legacy, unrelated), fixed, previousName = "cheese")
        assertEquals(252.0, result[0].calories, 0.001)
        assertEquals(252.0, result[1].calories, 0.001)
        assertEquals("f1", result[1].foodId)
        assertEquals(63.0, result[0].amount, 0.0)
        assertEquals(unrelated, result[2])
    }

    @Test
    fun renamingAFoodRenamesItsEntries() {
        val old = Food(id = "f1", name = "Chiken", calories = 170.0, servingAmount = 69.0)
        val entry = FoodEntry.of("a", old, 69.0, 100, 1L)
        val result = withEditedFood(listOf(entry), old.copy(name = "Chicken"), previousName = "Chiken")
        assertEquals("Chicken", result[0].name)
    }

    @Test
    fun heightIsTypedInFeetAndInchesOrCentimetres() {
        assertEquals(177.8, Height.cm(5.0, 10.0), 0.001)
        assertEquals(5 to 10.0, Height.feetInches(177.8))
        assertEquals(6 to 0.0, Height.feetInches(182.8)) // 71.97 in rounds up to a whole 6 ft, not 5 ft 12 in
    }

    private fun targetFor(goalKcal: Int, change: Double, kg: Double?) = CalorieTarget(
        goal = goalKcal, maintenance = goalKcal - change, bmr = 1700.0, bmrFromProfile = false,
        source = EstimateSource.STARTING_GUESS, confidence = 0.0, currentKg = kg, dailyChange = change,
        flooredAtMinimum = false, recentWeeklyXp = 0.0, usualWeeklyXp = null, projectedDay = null
    )

    @Test
    fun cuttingGetsMoreProteinPerKgThanBulking() {
        val cut = CalorieTargets.macros(targetFor(2200, -275.0, 80.0))
        val bulk = CalorieTargets.macros(targetFor(2800, 275.0, 80.0))
        assertEquals("cutting", cut.mode)
        assertEquals(176.0, cut.proteinG, 0.001)
        assertEquals("bulking", bulk.mode)
        assertEquals(144.0, bulk.proteinG, 0.001)
        assertTrue(bulk.carbsG > cut.carbsG)
    }

    @Test
    fun theRecommendedMacrosAddUpToTheGoal() {
        val split = CalorieTargets.macros(targetFor(2500, 0.0, 75.0))
        assertEquals("maintaining", split.mode)
        assertEquals(2500.0, split.proteinG * 4 + split.carbsG * 4 + split.fatG * 9, 0.5)
        assertEquals(2500 * 0.25 / 9, split.fatG, 0.001)
    }

    @Test
    fun proteinIsCappedAtAShareOfTheCaloriesForAHeavyPerson() {
        // 140 kg x 2.2 g would be 308 g (1232 kcal) of a 1800 kcal goal; capped at 35% = 157.5 g
        val split = CalorieTargets.macros(targetFor(1800, -275.0, 140.0))
        assertEquals(157.5, split.proteinG, 0.001)
        assertTrue(split.carbsG >= 0)
    }

    @Test
    fun theWeightTrendAveragesTheLastWeek() {
        val ins = listOf(WeighIn(today - 20, 90.0), WeighIn(today - 2, 80.0), WeighIn(today, 82.0))
        assertEquals(81.0, CalorieTargets.trendKg(ins, today)!!, 0.001)
        assertEquals(90.0, CalorieTargets.trendKg(listOf(WeighIn(today - 20, 90.0)), today)!!, 0.001)
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
