package com.pedro.heartratewatch.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.mobile.theme.PipBoyTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Weight and calorie goal: log weigh-ins, set a target weight, and get a daily calorie goal that learns from the
 * weigh-ins, the food diary and the XP earned (see [CalorieTargets]).
 */
class WeightPlanActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PipBoyTheme { WeightPlanScreen() } }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, WeightPlanActivity::class.java))
        }
    }
}

/** The daily calorie goal from the weight plan, or null when there is no plan (or its BMR can't be worked out yet). */
@Composable
internal fun rememberCalorieTarget(repository: CalorieRepository): CalorieTarget? {
    val app = LocalContext.current.applicationContext
    val plan by repository.planFlow.collectAsStateWithLifecycle(initialValue = null)
    val weighIns by repository.weighInsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val entries by repository.entriesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val runsFlow = remember { RunHistoryRepository(app).historyFlow }
    val workoutsFlow = remember { WorkoutRepository(app).historyFlow }
    val runs by runsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val workouts by workoutsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    return remember(plan, weighIns, entries, runs, workouts) {
        plan?.let { p ->
            val xp = Progression.dailyXp(runs, workouts).mapKeys { it.key.toEpochDay() }
            CalorieTargets.compute(p, weighIns, entries, xp, LocalDate.now().toEpochDay())
        }
    }
}

private fun decimalOnly(text: String): String {
    val filtered = text.filter { it.isDigit() || it == '.' }
    val firstDot = filtered.indexOf('.')
    return if (firstDot < 0) filtered else filtered.substring(0, firstDot + 1) + filtered.substring(firstDot + 1).replace(".", "")
}

private fun formatWeight(kg: Double, unit: WeightUnit): String = "${formatAmount(unit.fromKg(kg))} ${unit.symbol}"

@Composable
private fun WeightPlanScreen() {
    val context = LocalContext.current
    val repository = remember { CalorieRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val back = rememberSystemBack()
    // The same unit setting as workout weights (also in Settings).
    val workoutRepository = remember { WorkoutRepository(context.applicationContext) }
    val unit by workoutRepository.weightUnitFlow.collectAsStateWithLifecycle(initialValue = WeightUnit.LB)
    val weighIns by repository.weighInsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val plan by repository.planFlow.collectAsStateWithLifecycle(initialValue = null)
    val target = rememberCalorieTarget(repository)

    val today = remember { LocalDate.now() }
    val todayEpoch = today.toEpochDay()
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    var weighing by remember { mutableStateOf(false) }
    var editingPlan by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val trend = remember(weighIns) { CalorieTargets.trendKg(weighIns, todayEpoch) }

    Scaffold { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    NavButtons(onBack = back, onMainMenu = { goToMainMenu(context) })
                    Text("Weight plan", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Units", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    WeightUnit.entries.forEach { option ->
                        if (option == unit) Button(onClick = {}) { Text(option.symbol) }
                        else OutlinedButton(onClick = { scope.launch { workoutRepository.setWeightUnit(option) } }) { Text(option.symbol) }
                    }
                }
            }
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            trend?.let { formatWeight(it, unit) } ?: "No weigh-ins yet",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        if (trend != null) Text("Average of the last 7 days' weigh-ins", style = MaterialTheme.typography.bodySmall)
                        plan?.let { p ->
                            val toGo = trend?.let { p.targetKg - it }
                            Text(
                                "Target ${formatWeight(p.targetKg, unit)}" + when {
                                    toGo == null -> ""
                                    abs(toGo) < 0.25 -> "  ·  you're there"
                                    toGo < 0 -> "  ·  ${formatWeight(-toGo, unit)} to lose"
                                    else -> "  ·  ${formatWeight(toGo, unit)} to gain"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { weighing = true }) { Text("Weigh in") }
                            OutlinedButton(onClick = { editingPlan = true }) { Text(if (plan == null) "Set up a plan" else "Edit plan") }
                        }
                    }
                }
            }
            if (plan == null) {
                item {
                    Text(
                        "Set a target weight and the app works out a daily calorie goal. It starts from your BMR and your recent " +
                            "exercise (the XP you earn), then learns from your weigh-ins and what you eat, so log both for a couple of weeks.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else if (target == null) {
                item {
                    Text(
                        "Add a weigh-in so your BMR can be worked out from your height, age and sex.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                item { GoalCard(target, unit, dateFormat) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmReset = true }) { Text("Reset estimate") }
                        TextButton(onClick = { confirmClear = true }) { Text("Remove plan") }
                    }
                }
            }
            if (weighIns.isNotEmpty()) item { Text("Weigh-ins", style = MaterialTheme.typography.titleMedium) }
            items(weighIns.asReversed().take(30), key = { it.epochDay }) { entry ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (entry.epochDay == todayEpoch) "Today" else LocalDate.ofEpochDay(entry.epochDay).format(dateFormat),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(formatWeight(entry.kg, unit), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { scope.launch { repository.deleteWeighIn(entry.epochDay) } }) { Text("X") }
                }
            }
            item { Column(modifier = Modifier.padding(bottom = 24.dp)) {} }
        }
    }

    if (weighing) {
        WeighInDialog(
            unit = unit,
            onSave = { daysAgo, kg ->
                scope.launch { repository.addWeighIn(todayEpoch - daysAgo, kg) }
                weighing = false
            },
            onDismiss = { weighing = false }
        )
    }
    if (editingPlan) {
        PlanDialog(
            unit = unit,
            initial = plan,
            suggestedTargetKg = trend,
            onSave = { saved ->
                scope.launch { repository.savePlan(saved.copy(estimateStartDay = plan?.estimateStartDay ?: todayEpoch)) }
                editingPlan = false
            },
            onDismiss = { editingPlan = false }
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            text = { Text("Forget what the estimate learned and start over from today? The goal goes back to the starting guess until there are a couple of weeks of new weigh-ins and food logs.") },
            confirmButton = { TextButton(onClick = { scope.launch { repository.resetEstimate(todayEpoch) }; confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text("Remove the weight plan? Your weigh-ins are kept.") },
            confirmButton = { TextButton(onClick = { scope.launch { repository.clearPlan() }; confirmClear = false }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

/** The daily goal and how it was reached. */
@Composable
private fun GoalCard(target: CalorieTarget, unit: WeightUnit, dateFormat: DateTimeFormatter) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${target.goal} kcal a day", style = MaterialTheme.typography.headlineSmall)
            val change = target.dailyChange.roundToInt()
            Text(
                "You burn about ${target.maintenance.roundToInt()} kcal a day" + when {
                    change < 0 -> ", so the goal is ${-change} below that to lose weight."
                    change > 0 -> ", so the goal is $change above that to gain weight."
                    else -> "; at your target weight the goal keeps you there."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (target.flooredAtMinimum) {
                Text(
                    "The goal is held at ${target.goal} kcal, the lowest it goes (your BMR, and never under ${CalorieTargets.MIN_GOAL.roundToInt()}), so progress is slower than the pace you chose.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            target.projectedDay?.let {
                Text("At this pace you reach your target around ${LocalDate.ofEpochDay(it).format(dateFormat)}.", style = MaterialTheme.typography.bodyMedium)
            }
            when (target.source) {
                EstimateSource.STARTING_GUESS -> Text(
                    "Starting guess from your BMR (${target.bmr.roundToInt()} kcal${if (target.bmrFromProfile) ", worked out from your height, age and sex" else ""}) and recent exercise. " +
                        "It starts learning from your own data after about 10 days of weigh-ins and 7 days of logged food.",
                    style = MaterialTheme.typography.bodySmall
                )
                EstimateSource.LEARNING -> {
                    LinearProgressIndicator(progress = { target.confidence.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text("Learning: ${(target.confidence * 100).roundToInt()}% worked out from your weigh-ins and food, the rest still a guess.", style = MaterialTheme.typography.bodySmall)
                }
                EstimateSource.MEASURED -> Text("Measured from your last 4 weeks of weigh-ins and food.", style = MaterialTheme.typography.bodySmall)
            }
            val usual = target.usualWeeklyXp
            val recent = target.recentWeeklyXp.roundToInt()
            Text(
                "Exercise: $recent XP in the last 7 days" + when {
                    usual == null -> "."
                    target.recentWeeklyXp > usual * 1.15 + 50 -> " (more than your usual ${usual.roundToInt()}), so the goal is higher."
                    target.recentWeeklyXp < usual * 0.85 - 50 -> " (less than your usual ${usual.roundToInt()}), so the goal is lower."
                    else -> " (about your usual ${usual.roundToInt()})."
                },
                style = MaterialTheme.typography.bodySmall
            )
            target.currentKg?.let {
                Text("Based on ${formatWeight(it, unit)}.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WeighInDialog(unit: WeightUnit, onSave: (daysAgo: Int, kg: Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var daysAgo by remember { mutableStateOf(0) }
    val value = text.toDoubleOrNull()
    val valid = value != null && value > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weigh in") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = decimalOnly(it) },
                    label = { Text("Weight (${unit.symbol})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Today" to 0, "Yesterday" to 1).forEach { (label, days) ->
                        if (daysAgo == days) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = { daysAgo = days }) { Text(label) }
                    }
                }
                Text("Weigh at the same time each day, such as first thing in the morning. One weigh-in a day replaces an earlier one.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(daysAgo, unit.toKg(value!!)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The target weight and pace, and how the BMR is known: type the number from the scale, or give height, age and sex
 * and it is worked out (it's recalculated from your weight as that changes).
 */
@Composable
private fun PlanDialog(
    unit: WeightUnit,
    initial: WeightPlan?,
    suggestedTargetKg: Double?,
    onSave: (WeightPlan) -> Unit,
    onDismiss: () -> Unit
) {
    var target by remember { mutableStateOf((initial?.targetKg ?: suggestedTargetKg)?.let { formatAmount(unit.fromKg(it)) }.orEmpty()) }
    var bmr by remember { mutableStateOf(initial?.scaleBmr?.let { formatAmount(it) }.orEmpty()) }
    // Height can be typed in feet and inches or in cm; it is kept in cm.
    var useFeet by remember { mutableStateOf(unit == WeightUnit.LB) }
    val initialFeet = remember { initial?.heightCm?.let { Height.feetInches(it) } }
    var feet by remember { mutableStateOf(initialFeet?.first?.toString().orEmpty()) }
    var inches by remember { mutableStateOf(initialFeet?.second?.let { formatAmount(it) }.orEmpty()) }
    var centimetres by remember { mutableStateOf(initial?.heightCm?.let { formatAmount(it) }.orEmpty()) }
    var age by remember { mutableStateOf(initial?.age?.toString().orEmpty()) }
    var sex by remember { mutableStateOf(initial?.sex) }
    var pace by remember { mutableStateOf(initial?.pace ?: Pace.STEADY) }

    val targetValue = target.toDoubleOrNull()
    val bmrValue = bmr.toDoubleOrNull()
    val heightValue: Double? = if (useFeet) {
        if (feet.isBlank() && inches.isBlank()) null else Height.cm(feet.toDoubleOrNull() ?: 0.0, inches.toDoubleOrNull() ?: 0.0)
    } else centimetres.toDoubleOrNull()
    fun switchHeightTo(feetMode: Boolean) {
        heightValue?.let { cm ->
            if (feetMode) {
                val (f, i) = Height.feetInches(cm)
                feet = f.toString()
                inches = formatAmount(i)
            } else centimetres = formatAmount(cm)
        }
        useFeet = feetMode
    }
    val ageValue = age.toIntOrNull()
    val bmrOk = bmrValue != null && bmrValue in 800.0..4500.0
    val profileOk = heightValue != null && heightValue in 90.0..250.0 && ageValue != null && ageValue in 10..100 && sex != null
    val valid = targetValue != null && targetValue > 0 && (if (bmr.isBlank()) profileOk else bmrOk)
    val numberOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weight plan") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = target, onValueChange = { target = decimalOnly(it) }, label = { Text("Target weight (${unit.symbol})") }, singleLine = true, keyboardOptions = numberOptions, modifier = Modifier.fillMaxWidth())
                Text("Pace", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pace.entries.forEach { option ->
                        if (option == pace) Button(onClick = {}) { Text(option.label) } else OutlinedButton(onClick = { pace = option }) { Text(option.label) }
                    }
                }
                Text("About ${"%.2f".format(unit.fromKg(pace.kgPerWeek))} ${unit.symbol} a week.", style = MaterialTheme.typography.bodySmall)
                Text("Your BMR", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = bmr, onValueChange = { bmr = decimalOnly(it) }, label = { Text("BMR from your scale (kcal)") }, singleLine = true, keyboardOptions = numberOptions, isError = bmr.isNotBlank() && !bmrOk, modifier = Modifier.fillMaxWidth())
                Text("No scale number? Leave it blank and fill these in; the BMR is worked out from them and your weight.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Height", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    listOf("ft / in" to true, "cm" to false).forEach { (label, feetMode) ->
                        if (useFeet == feetMode) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = { switchHeightTo(feetMode) }) { Text(label) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (useFeet) {
                        OutlinedTextField(value = feet, onValueChange = { feet = it.filter(Char::isDigit).take(1) }, label = { Text("Feet") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(value = inches, onValueChange = { inches = decimalOnly(it) }, label = { Text("Inches") }, singleLine = true, keyboardOptions = numberOptions, modifier = Modifier.weight(1f))
                    } else {
                        OutlinedTextField(value = centimetres, onValueChange = { centimetres = decimalOnly(it) }, label = { Text("Height (cm)") }, singleLine = true, keyboardOptions = numberOptions, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = age, onValueChange = { age = it.filter(Char::isDigit).take(3) }, label = { Text("Age") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Sex.entries.forEach { option ->
                        if (option == sex) Button(onClick = {}) { Text(option.label) } else OutlinedButton(onClick = { sex = option }) { Text(option.label) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        WeightPlan(
                            targetKg = unit.toKg(targetValue!!),
                            pace = pace,
                            estimateStartDay = initial?.estimateStartDay ?: 0L,
                            scaleBmr = bmrValue.takeIf { bmr.isNotBlank() },
                            heightCm = heightValue,
                            age = ageValue,
                            sex = sex
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
