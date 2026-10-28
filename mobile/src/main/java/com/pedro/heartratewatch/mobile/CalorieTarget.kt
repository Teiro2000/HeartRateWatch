package com.pedro.heartratewatch.mobile

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Body weight and height are stored in kg and cm; they are shown in the app's weight unit (the one workouts use), with
// height in inches for pounds and centimetres for kg.
private const val LB_PER_KG = 2.2046226218

fun WeightUnit.fromKg(kg: Double): Double = fromLb(kg * LB_PER_KG)
fun WeightUnit.toKg(value: Double): Double = toLb(value) / LB_PER_KG

/** Height is typed in feet and inches or in centimetres, and kept in centimetres. */
object Height {
    fun cm(feet: Double, inches: Double): Double = (feet * 12 + inches) * 2.54

    /** Whole feet and the inches left over (to a tenth), e.g. 178 cm is 5 ft 10.1 in. */
    fun feetInches(cm: Double): Pair<Int, Double> {
        val totalInches = cm / 2.54
        var feet = (totalInches / 12).toInt()
        var inches = Math.round((totalInches - feet * 12) * 10) / 10.0
        if (inches >= 12) { feet += 1; inches = 0.0 }
        return feet to inches
    }
}

/** A morning weight, in kg, on one day. */
data class WeighIn(val epochDay: Long, val kg: Double)

/** How fast to move towards the target weight. */
enum class Pace(val label: String, val kgPerWeek: Double) {
    SLOW("Slow", 0.125),
    STEADY("Steady", 0.25),
    FAST("Fast", 0.5)
}

enum class Sex(val label: String) { MALE("Male"), FEMALE("Female") }

/**
 * The weight goal. The resting metabolism (BMR) is either [scaleBmr], typed in from a smart scale, or worked out from
 * [heightCm], [age] and [sex] with the current weight. [estimateStartDay] is where the learning from weigh-ins and
 * food logs starts (moved forward by "Reset estimate").
 */
data class WeightPlan(
    val targetKg: Double,
    val pace: Pace,
    val estimateStartDay: Long,
    val scaleBmr: Double? = null,
    val heightCm: Double? = null,
    val age: Int? = null,
    val sex: Sex? = null
) {
    /** Mifflin-St Jeor, the usual formula, when the scale's own number wasn't given. Null when something is missing. */
    fun bmr(currentKg: Double?): Double? {
        scaleBmr?.let { return it }
        if (currentKg == null || heightCm == null || age == null || sex == null) return null
        return 10 * currentKg + 6.25 * heightCm - 5 * age + if (sex == Sex.MALE) 5 else -161
    }
}

enum class EstimateSource { STARTING_GUESS, LEARNING, MEASURED }

/** A day's protein, carbs and fat (grams) that suits the plan: [mode] is "cutting", "bulking" or "maintaining". */
data class MacroSplit(val mode: String, val proteinG: Double, val carbsG: Double, val fatG: Double, val explanation: String)

/** The daily calorie goal and how it was worked out. */
data class CalorieTarget(
    val goal: Int,
    /** Calories burnt per day at the current weight and exercise level. */
    val maintenance: Double,
    val bmr: Double,
    val bmrFromProfile: Boolean,
    val source: EstimateSource,
    /** 0 (only the starting guess) to 1 (fully from the weigh-ins and food logs). */
    val confidence: Double,
    /** The recent weight (average of the last week's weigh-ins), in kg. */
    val currentKg: Double?,
    /** Calories added (+) or taken away (-) each day to move towards the target weight; 0 when at the target. */
    val dailyChange: Double,
    /** The goal was held up by the minimum, so the pace is slower than chosen. */
    val flooredAtMinimum: Boolean,
    /** XP earned in the last 7 days. */
    val recentWeeklyXp: Double,
    /** XP per week over the period the estimate learned from, null when it isn't learning yet. */
    val usualWeeklyXp: Double?,
    /** The day the target weight should be reached at this pace. */
    val projectedDay: Long?
)

/**
 * Works out a daily calorie goal from the weight goal, the weigh-ins and the food diary. Pure, so it can be tested.
 *
 * Before there is enough data, maintenance is a guess: BMR x 1.2 plus a little for the XP earned lately (XP stands in
 * for exercise, so there is no need to record the calories of each workout). Once there are weigh-ins and logged food
 * across a couple of weeks, maintenance is measured: what you ate on average, plus what your weight trend says you
 * burnt beyond that. The measurement is moved up or down when the last week's XP is above or below what it was over
 * the period it learned from, and it takes over from the guess gradually.
 */
object CalorieTargets {

    const val KCAL_PER_KG = 7700.0
    /** About what a point of XP is worth in calories (a 300 XP run is roughly 300 kcal). */
    const val KCAL_PER_XP = 1.1
    const val BASE_FACTOR = 1.2
    const val WINDOW_DAYS = 28
    /** A day with less food than this logged is treated as not logged, not as fasting. */
    const val MIN_LOGGED_KCAL = 800.0
    const val MIN_GOAL = 1200.0
    private const val MIN_POINTS = 4
    private const val MIN_SPAN_DAYS = 10
    private const val MIN_LOGGED_DAYS = 7
    private const val AT_TARGET_KG = 0.25

    /** The average of the last week's weigh-ins, or the latest one if there are none that recent. */
    fun trendKg(weighIns: List<WeighIn>, today: Long): Double? {
        val recent = weighIns.filter { it.epochDay in (today - 6)..today }
        if (recent.isNotEmpty()) return recent.map { it.kg }.average()
        return weighIns.filter { it.epochDay <= today }.maxByOrNull { it.epochDay }?.kg
    }

    /** Least-squares slope of weight against day, in kg per day. */
    fun slopeKgPerDay(points: List<WeighIn>): Double {
        if (points.size < 2) return 0.0
        val meanX = points.map { it.epochDay.toDouble() }.average()
        val meanY = points.map { it.kg }.average()
        val denominator = points.sumOf { (it.epochDay - meanX) * (it.epochDay - meanX) }
        if (denominator == 0.0) return 0.0
        return points.sumOf { (it.epochDay - meanX) * (it.kg - meanY) } / denominator
    }

    /**
     * A protein, carbs and fat split for the goal. Protein is set by body weight (2.2 g per kg when cutting, to hold on to
     * muscle; 1.8 g per kg when bulking or maintaining) but never more than 35% of the calories; fat is a quarter of
     * the calories; carbs fill the rest. Which of cutting, bulking or maintaining it is follows the weight plan.
     */
    fun macros(target: CalorieTarget): MacroSplit {
        val kcal = target.goal.toDouble()
        val (mode, perKg, why) = when {
            target.dailyChange < 0 -> Triple("cutting", 2.2, "high protein to hold on to muscle while you lose weight, a quarter of calories from fat, and the rest carbs for training.")
            target.dailyChange > 0 -> Triple("bulking", 1.8, "enough protein to build muscle, a quarter of calories from fat, and plenty of carbs to fuel training and growth.")
            else -> Triple("maintaining", 1.8, "steady protein, a quarter of calories from fat, and the rest carbs.")
        }
        val protein = min(target.currentKg?.let { it * perKg } ?: (kcal * 0.30 / 4), kcal * 0.35 / 4)
        val fat = kcal * 0.25 / 9
        val carbs = max(0.0, (kcal - protein * 4 - fat * 9) / 4)
        return MacroSplit(mode, protein, carbs, fat, why)
    }

    /** Null when the BMR can't be known yet (it needs the scale's number, or height, age, sex and a weigh-in). */
    fun compute(
        plan: WeightPlan,
        weighIns: List<WeighIn>,
        entries: List<FoodEntry>,
        dailyXp: Map<Long, Int>,
        today: Long
    ): CalorieTarget? {
        val currentKg = trendKg(weighIns, today)
        val bmr = plan.bmr(currentKg) ?: return null

        val recentXp = (today - 7 until today).sumOf { dailyXp[it] ?: 0 }.toDouble()
        val baseline = bmr * BASE_FACTOR + recentXp * KCAL_PER_XP / 7

        val windowStart = max(plan.estimateStartDay, today - WINDOW_DAYS)
        val windowDays = (today - windowStart).toInt()
        val caloriesByDay = entries.groupBy { it.epochDay }.mapValues { (_, day) -> day.sumOf { it.calories } }
        val loggedDays = (windowStart until today).filter { (caloriesByDay[it] ?: 0.0) >= MIN_LOGGED_KCAL }
        val points = weighIns.filter { it.epochDay in windowStart..today }.sortedBy { it.epochDay }
        val span = if (points.size >= 2) (points.last().epochDay - points.first().epochDay).toInt() else 0
        val enough = points.size >= MIN_POINTS && span >= MIN_SPAN_DAYS && loggedDays.size >= MIN_LOGGED_DAYS
        val confidence = if (enough) ((min(loggedDays.size, span) - 5) / 23.0).coerceIn(0.0, 1.0) else 0.0

        var maintenance = baseline
        var usualXp: Double? = null
        if (confidence > 0) {
            val averageIntake = loggedDays.map { caloriesByDay.getValue(it) }.average()
            val measured = averageIntake - slopeKgPerDay(points) * KCAL_PER_KG
            val windowXpPerWeek = (windowStart until today).sumOf { dailyXp[it] ?: 0 } * 7.0 / windowDays
            usualXp = windowXpPerWeek
            val adjusted = (measured + (recentXp - windowXpPerWeek) * KCAL_PER_XP / 7).coerceIn(bmr, bmr * 2.5)
            maintenance = baseline * (1 - confidence) + adjusted * confidence
        }

        val toGo = plan.targetKg - (currentKg ?: plan.targetKg)
        val direction = if (abs(toGo) < AT_TARGET_KG) 0 else if (toGo < 0) -1 else 1
        val change = direction * plan.pace.kgPerWeek * KCAL_PER_KG / 7
        val floor = max(MIN_GOAL, bmr)
        val wanted = maintenance + change
        val goal = max(wanted, floor)

        val effectiveKgPerWeek = (goal - maintenance) / KCAL_PER_KG * 7
        val projected = if (direction != 0 && effectiveKgPerWeek * direction > 0.01) {
            today + ceil(abs(toGo) / abs(effectiveKgPerWeek) * 7).toLong()
        } else null

        return CalorieTarget(
            goal = goal.roundToInt(),
            maintenance = maintenance,
            bmr = bmr,
            bmrFromProfile = plan.scaleBmr == null,
            source = when {
                confidence <= 0 -> EstimateSource.STARTING_GUESS
                confidence < 1 -> EstimateSource.LEARNING
                else -> EstimateSource.MEASURED
            },
            confidence = confidence,
            currentKg = currentKg,
            dailyChange = change,
            flooredAtMinimum = wanted < floor && direction != 0,
            recentWeeklyXp = recentXp,
            usualWeeklyXp = usualXp,
            projectedDay = projected
        )
    }
}
