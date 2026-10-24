package com.pedro.heartratewatch.mobile

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A simple food diary: what was eaten each day, with calories and macros added up. No XP, just for keeping track. */
class CalorieActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PipBoyTheme { CalorieScreen() } }
    }
}

private fun decimalOnly(text: String): String {
    val filtered = text.filter { it.isDigit() || it == '.' }
    val firstDot = filtered.indexOf('.')
    return if (firstDot < 0) filtered else filtered.substring(0, firstDot + 1) + filtered.substring(firstDot + 1).replace(".", "")
}

@Composable
private fun CalorieScreen() {
    val context = LocalContext.current
    val app = context.applicationContext
    val repository = remember { CalorieRepository(app) }
    val scope = rememberCoroutineScope()
    val entries by repository.entriesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val foods by repository.foodsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val goal by repository.goalFlow.collectAsStateWithLifecycle(initialValue = null)
    val back = rememberSystemBack()

    val today = remember { LocalDate.now() }
    var day by remember { mutableStateOf(today) }
    var adding by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf(false) }

    val totals = remember(entries, day) { dayTotals(entries, day.toEpochDay()) }
    val dayEntries = remember(entries, day) { entries.filter { it.epochDay == day.toEpochDay() }.sortedBy { it.loggedAtMillis } }
    val dayFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    Scaffold { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    NavButtons(onBack = back, onMainMenu = { goToMainMenu(context) })
                    Text("Calories", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { day = day.minusDays(1) }) { Text("<") }
                    Text(
                        if (day == today) "Today" else day.format(dayFormat),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { day = day.plusDays(1) }, enabled = day.isBefore(today)) { Text(">") }
                }
            }
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (goal != null) "${formatAmount(totals.calories)} / $goal kcal" else "${formatAmount(totals.calories)} kcal",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        goal?.let { g ->
                            LinearProgressIndicator(
                                progress = { (totals.calories / g).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            val left = g - totals.calories
                            Text(
                                if (left >= 0) "${formatAmount(left)} kcal left" else "${formatAmount(-left)} kcal over",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Protein ${formatAmount(totals.proteinG)} g", style = MaterialTheme.typography.bodyMedium)
                            Text("Carbs ${formatAmount(totals.carbsG)} g", style = MaterialTheme.typography.bodyMedium)
                            Text("Fat ${formatAmount(totals.fatG)} g", style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { editingGoal = true }) { Text(if (goal != null) "Change daily goal" else "Set a daily goal") }
                    }
                }
            }
            item {
                Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Add food") }
            }
            if (dayEntries.isEmpty()) {
                item { Text("Nothing logged for this day.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(dayEntries, key = { it.id }) { entry ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${formatAmount(entry.grams)} g  ·  " +
                                    "P ${formatAmount(entry.proteinG)}  C ${formatAmount(entry.carbsG)}  F ${formatAmount(entry.fatG)} g",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text("${formatAmount(entry.calories)} kcal", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { scope.launch { repository.deleteEntry(entry.id) } }) { Text("X") }
                    }
                }
            }
            item { Column(modifier = Modifier.padding(bottom = 24.dp)) {} }
        }
    }

    if (adding) {
        AddFoodDialog(
            foods = foods,
            onDelete = { scope.launch { repository.deleteFood(it.id) } },
            onAdd = { food, grams ->
                scope.launch { repository.log(food, grams, day.toEpochDay()) }
                adding = false
            },
            onDismiss = { adding = false }
        )
    }
    if (editingGoal) {
        GoalDialog(
            current = goal,
            onSave = { scope.launch { repository.setGoal(it) }; editingGoal = false },
            onDismiss = { editingGoal = false }
        )
    }
}

/**
 * Adds something eaten. Type a food's numbers for one serving (calories are required, the rest optional) and what a
 * serving weighs, then the weight actually eaten, or tap a saved food to fill it in. The food is saved for next time.
 */
@Composable
private fun AddFoodDialog(
    foods: List<Food>,
    onDelete: (Food) -> Unit,
    onAdd: (Food, Double) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var servingSize by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var savedId by remember { mutableStateOf<String?>(null) }

    // Scanning a photo of a nutrition label fills in the numbers (reading happens on the phone).
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanStatus by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    // The scanned photo with boxes over where each number was read, to check them against.
    var scan by remember { mutableStateOf<LabelScan?>(null) }
    var showBigPreview by remember { mutableStateOf(false) }
    fun readPhoto(photo: Uri) {
        scanning = true
        scanStatus = "Reading the label..."
        scope.launch {
            val result = LabelScanner.scan(context, photo)
            val scanned = result?.label
            scanning = false
            scan = result?.takeIf { it.label.foundAnything }
            if (scanned == null || !scanned.foundAnything) {
                scanStatus = "Couldn't read a nutrition label in that photo. Try again with the label flat, in good light, filling the picture."
            } else {
                scanned.calories?.let { calories = formatAmount(it) }
                scanned.proteinG?.let { protein = formatAmount(it) }
                scanned.carbsG?.let { carbs = formatAmount(it) }
                scanned.fatG?.let { fat = formatAmount(it) }
                scanned.servingGrams?.let { servingSize = formatAmount(it) }
                savedId = null
                val missing = buildList {
                    if (scanned.calories == null) add("calories")
                    if (scanned.proteinG == null) add("protein")
                    if (scanned.carbsG == null) add("carbs")
                    if (scanned.fatG == null) add("fat")
                    if (scanned.servingGrams == null) add("serving weight")
                }
                scanStatus = "Filled in from the label. Check the numbers" +
                    (if (missing.isEmpty()) "." else ", and fill in: ${missing.joinToString(", ")}.")
            }
        }
    }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val photo = photoUri
        if (saved && photo != null) readPhoto(photo)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        if (picked != null) readPhoto(picked)
    }

    val kcal = calories.toDoubleOrNull()
    val amount = weight.toDoubleOrNull()
    val serving = servingSize.toDoubleOrNull()
    val valid = name.isNotBlank() && kcal != null && serving != null && serving > 0 && amount != null && amount > 0
    val numberOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    scan?.let { current -> if (showBigPreview) LabelPreviewScreen(current) { showBigPreview = false } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add food") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scan a nutrition label", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !scanning,
                        onClick = {
                            val file = File(context.cacheDir, "label_photos").also { it.mkdirs() }.let { File(it, "label.jpg") }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            photoUri = uri
                            takePhoto.launch(uri)
                        }
                    ) { Text("Take photo") }
                    OutlinedButton(enabled = !scanning, onClick = { pickPhoto.launch("image/*") }) { Text("Choose photo") }
                }
                scanStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                scan?.let { current ->
                    // Where on the photo each number was read, so a wrong one is easy to spot.
                    LabelPreview(
                        current,
                        modifier = Modifier.fillMaxWidth().height(200.dp).clickable { showBigPreview = true }
                    )
                    LabelLegend(current)
                    TextButton(onClick = { showBigPreview = true }) { Text("Enlarge photo") }
                }
                if (foods.isNotEmpty()) {
                    Text("My foods (tap to fill in)", style = MaterialTheme.typography.labelLarge)
                    foods.take(8).forEach { food ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "${food.name}  ·  ${formatAmount(food.calories)} kcal per ${formatAmount(food.servingGrams)} g",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).clickable {
                                    name = food.name
                                    calories = formatAmount(food.calories)
                                    protein = if (food.proteinG > 0) formatAmount(food.proteinG) else ""
                                    carbs = if (food.carbsG > 0) formatAmount(food.carbsG) else ""
                                    fat = if (food.fatG > 0) formatAmount(food.fatG) else ""
                                    servingSize = formatAmount(food.servingGrams)
                                    savedId = food.id
                                }.padding(vertical = 6.dp)
                            )
                            TextButton(onClick = { onDelete(food) }) { Text("X") }
                        }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it; savedId = null }, label = { Text("Food") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("One serving", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = calories, onValueChange = { calories = decimalOnly(it) }, label = { Text("Calories (kcal)") }, singleLine = true, keyboardOptions = numberOptions, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = protein, onValueChange = { protein = decimalOnly(it) }, label = { Text("Protein g") }, singleLine = true, keyboardOptions = numberOptions, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = carbs, onValueChange = { carbs = decimalOnly(it) }, label = { Text("Carbs g") }, singleLine = true, keyboardOptions = numberOptions, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = fat, onValueChange = { fat = decimalOnly(it) }, label = { Text("Fat g") }, singleLine = true, keyboardOptions = numberOptions, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = servingSize, onValueChange = { servingSize = decimalOnly(it) }, label = { Text("A serving weighs (g)") }, singleLine = true, keyboardOptions = numberOptions, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = weight, onValueChange = { weight = decimalOnly(it) }, label = { Text("Weight eaten (g)") }, singleLine = true, keyboardOptions = numberOptions, modifier = Modifier.fillMaxWidth())
                if (valid) Text("Total: ${formatAmount(kcal!! * amount!! / serving!!)} kcal", style = MaterialTheme.typography.titleSmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val food = Food(
                        id = savedId ?: CalorieRepository.newId(),
                        name = name.trim().capitalizedWords(),
                        calories = kcal!!,
                        proteinG = protein.toDoubleOrNull() ?: 0.0,
                        carbsG = carbs.toDoubleOrNull() ?: 0.0,
                        fatG = fat.toDoubleOrNull() ?: 0.0,
                        servingGrams = serving!!
                    )
                    onAdd(food, amount!!)
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun GoalDialog(current: Int?, onSave: (Int?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily calorie goal") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit) },
                label = { Text("Calories per day (blank for none)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.toIntOrNull()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
