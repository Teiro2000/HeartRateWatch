package com.pedro.heartratewatch.mobile

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.mobile.theme.PipBoyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A simple food diary: what was eaten each day, with calories and macros added up, and a database of foods ("My
 * foods", each with the photo of its label) to log from. No XP, just for keeping track.
 */
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

/** The food editor is open for [food], or for a new food when it is null. */
private class EditorTarget(val food: Food?)

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
    var picking by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var eating by remember { mutableStateOf<Food?>(null) }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var deleting by remember { mutableStateOf<Food?>(null) }
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picking = true }, modifier = Modifier.weight(1f)) { Text("+ Log food") }
                    OutlinedButton(onClick = { browsing = true }, modifier = Modifier.weight(1f)) { Text("My foods") }
                }
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
                                "${formatQuantity(entry.amount, entry.unit)}  ·  " +
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

    if (picking) {
        LogFoodDialog(
            foods = foods,
            onPick = { picking = false; eating = it },
            onNewFood = { picking = false; editor = EditorTarget(null) },
            onDismiss = { picking = false }
        )
    }
    if (browsing) {
        MyFoodsScreen(
            foods = foods,
            onAdd = { editor = EditorTarget(null) },
            onEdit = { editor = EditorTarget(it) },
            onDelete = { deleting = it },
            onClose = { browsing = false }
        )
    }
    eating?.let { food ->
        AmountEatenDialog(
            food = food,
            onLog = { amount ->
                scope.launch { repository.log(food, amount, day.toEpochDay()) }
                eating = null
            },
            onDismiss = { eating = null }
        )
    }
    editor?.let { target ->
        FoodEditorDialog(
            initial = target.food,
            onSave = { food, photo, eaten ->
                scope.launch {
                    val saved = repository.saveFood(food, photo)
                    if (eaten != null) repository.log(saved, eaten, day.toEpochDay())
                }
                editor = null
            },
            onDismiss = { editor = null }
        )
    }
    deleting?.let { food ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text("Remove ${food.name} from My foods? What you already logged stays.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { repository.deleteFood(food.id) }; deleting = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
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

/** The small photo a food was entered from, or a plain plate icon when it has none. */
@Composable
private fun FoodThumbnail(food: Food, size: Dp = 48.dp) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, food.id, food.photoAt) {
        value = if (food.hasPhoto) withContext(Dispatchers.IO) { FoodPhotos.load(context, food.id, 240)?.asImageBitmap() } else null
    }
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = Modifier.size(size).clip(shape).border(1.dp, MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center
    ) {
        val shown = image
        if (shown != null) {
            Image(shown, contentDescription = "Photo of the label", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Outlined.Restaurant, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(size / 2))
        }
    }
}

@Composable
private fun FoodRow(food: Food, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier.fillMaxWidth()) {
        FoodThumbnail(food)
        Column(modifier = Modifier.weight(1f)) {
            Text(food.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${formatAmount(food.calories)} kcal per ${formatQuantity(food.servingAmount, food.unit)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        trailing()
    }
}

/** Pick a food from the database to log; what was eaten is asked next. */
@Composable
private fun LogFoodDialog(foods: List<Food>, onPick: (Food) -> Unit, onNewFood: () -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(foods, query) { foods.filter { it.name.contains(query.trim(), ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log food") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (foods.isEmpty()) {
                    Text("No saved foods yet. Add one with New food; it's kept here for next time.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search my foods") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (shown.isEmpty()) Text("No food matches that.", style = MaterialTheme.typography.bodyMedium)
                    shown.forEach { food -> FoodRow(food, modifier = Modifier.clickable { onPick(food) }.padding(vertical = 6.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNewFood) { Text("New food") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The whole food database: add a food without logging it, edit one, or remove one. */
@Composable
private fun MyFoodsScreen(foods: List<Food>, onAdd: () -> Unit, onEdit: (Food) -> Unit, onDelete: (Food) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("My foods", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("+ Add food") }
                Text("Foods you add are saved here with their label photo, so you only scan a label once. Tap one to edit it.", style = MaterialTheme.typography.bodySmall)
                if (foods.isEmpty()) Text("Nothing saved yet.", style = MaterialTheme.typography.bodyMedium)
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(foods, key = { it.id }) { food ->
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            FoodRow(
                                food,
                                modifier = Modifier.clickable { onEdit(food) }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
                            ) { TextButton(onClick = { onDelete(food) }) { Text("X") } }
                        }
                    }
                }
            }
        }
    }
}

/** How much of [food] was eaten, in the unit its serving is measured in. */
@Composable
private fun AmountEatenDialog(food: Food, onLog: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val amount = text.toDoubleOrNull()
    val valid = amount != null && amount > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(food.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${formatAmount(food.calories)} kcal per ${formatQuantity(food.servingAmount, food.unit)}",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = decimalOnly(it) },
                    label = { Text("Amount eaten (${food.unit.label})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                if (valid) Text("Total: ${formatAmount(food.calories * amount!! / food.servingAmount)} kcal", style = MaterialTheme.typography.titleSmall)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onLog(amount!!) }) { Text("Log") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Adds a food to the database (or edits one): type its numbers for one serving (calories are required, the rest
 * optional) and how big a serving is, in grams or millilitres, or scan the label to fill them in; the photo is kept with
 * the food. What was eaten is optional: leave it blank to only save the food, or fill it in to log it as well.
 */
@Composable
private fun FoodEditorDialog(
    initial: Food?,
    onSave: (food: Food, photo: Bitmap?, eaten: Double?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var calories by remember { mutableStateOf(initial?.let { formatAmount(it.calories) }.orEmpty()) }
    var protein by remember { mutableStateOf(initial?.takeIf { it.proteinG > 0 }?.let { formatAmount(it.proteinG) }.orEmpty()) }
    var carbs by remember { mutableStateOf(initial?.takeIf { it.carbsG > 0 }?.let { formatAmount(it.carbsG) }.orEmpty()) }
    var fat by remember { mutableStateOf(initial?.takeIf { it.fatG > 0 }?.let { formatAmount(it.fatG) }.orEmpty()) }
    var servingSize by remember { mutableStateOf(initial?.let { formatAmount(it.servingAmount) }.orEmpty()) }
    var unit by remember { mutableStateOf(initial?.unit ?: ServingUnit.GRAMS) }
    var eaten by remember { mutableStateOf("") }

    // Scanning a photo of a nutrition label fills in the numbers (reading happens on the phone).
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanStatus by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    // The scanned photo with boxes over where each number was read, to check them against. It is what gets saved.
    var scan by remember { mutableStateOf<LabelScan?>(null) }
    var showBigPreview by remember { mutableStateOf(false) }
    // The photo already saved with this food, shown until a new one is scanned.
    val savedPhoto by produceState<Bitmap?>(null, initial?.id, initial?.photoAt) {
        value = if (initial?.hasPhoto == true) withContext(Dispatchers.IO) { FoodPhotos.load(context, initial.id) } else null
    }
    var showSavedPhoto by remember { mutableStateOf(false) }

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
                scanned.servingAmount?.let { servingSize = formatAmount(it); unit = scanned.servingUnit }
                val missing = buildList {
                    if (scanned.calories == null) add("calories")
                    if (scanned.proteinG == null) add("protein")
                    if (scanned.carbsG == null) add("carbs")
                    if (scanned.fatG == null) add("fat")
                    if (scanned.servingAmount == null) add("serving size")
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
    val serving = servingSize.toDoubleOrNull()
    val amount = eaten.toDoubleOrNull()
    val eatenOk = eaten.isBlank() || (amount != null && amount > 0)
    val valid = name.isNotBlank() && kcal != null && serving != null && serving > 0 && eatenOk
    val numberOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    scan?.let { current -> if (showBigPreview) LabelPreviewScreen(current) { showBigPreview = false } }
    savedPhoto?.let { photo -> if (showSavedPhoto) PhotoViewer(photo) { showSavedPhoto = false } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add food" else "Edit food") },
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
                val current = scan
                if (current != null) {
                    // Where on the photo each number was read, so a wrong one is easy to spot.
                    LabelPreview(
                        current,
                        modifier = Modifier.fillMaxWidth().height(200.dp).clickable { showBigPreview = true }
                    )
                    LabelLegend(current)
                    TextButton(onClick = { showBigPreview = true }) { Text("Enlarge photo") }
                    Text("This photo is saved with the food.", style = MaterialTheme.typography.bodySmall)
                } else {
                    savedPhoto?.let { photo ->
                        Image(
                            photo.asImageBitmap(),
                            contentDescription = "Saved photo of the label",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(160.dp).clickable { showSavedPhoto = true }
                        )
                        Text("Saved photo (tap to enlarge). Scan again to replace it.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Food") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("One serving", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = calories, onValueChange = { calories = decimalOnly(it) }, label = { Text("Calories (kcal)") }, singleLine = true, keyboardOptions = numberOptions, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = protein, onValueChange = { protein = decimalOnly(it) }, label = { Text("Protein g") }, singleLine = true, keyboardOptions = numberOptions, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = carbs, onValueChange = { carbs = decimalOnly(it) }, label = { Text("Carbs g") }, singleLine = true, keyboardOptions = numberOptions, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = fat, onValueChange = { fat = decimalOnly(it) }, label = { Text("Fat g") }, singleLine = true, keyboardOptions = numberOptions, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = servingSize,
                        onValueChange = { servingSize = decimalOnly(it) },
                        label = { Text("A serving is (${unit.label})") },
                        singleLine = true,
                        keyboardOptions = numberOptions,
                        modifier = Modifier.weight(1f)
                    )
                    ServingUnit.entries.forEach { option ->
                        if (option == unit) Button(onClick = {}) { Text(option.label) }
                        else OutlinedButton(onClick = { unit = option }) { Text(option.label) }
                    }
                }
                Text("Eaten right now? (optional)", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = eaten,
                    onValueChange = { eaten = decimalOnly(it) },
                    label = { Text("Amount eaten (${unit.label})") },
                    singleLine = true,
                    keyboardOptions = numberOptions,
                    modifier = Modifier.fillMaxWidth()
                )
                if (valid && amount != null) Text("Total: ${formatAmount(kcal!! * amount / serving!!)} kcal", style = MaterialTheme.typography.titleSmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val food = Food(
                        id = initial?.id ?: CalorieRepository.newId(),
                        name = name.trim().capitalizedWords(),
                        calories = kcal!!,
                        proteinG = protein.toDoubleOrNull() ?: 0.0,
                        carbsG = carbs.toDoubleOrNull() ?: 0.0,
                        fatG = fat.toDoubleOrNull() ?: 0.0,
                        servingAmount = serving!!,
                        unit = unit,
                        photoAt = initial?.photoAt ?: 0L
                    )
                    onSave(food, scan?.photo, amount)
                }
            ) { Text(if (amount != null) "Save and log" else "Save") }
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
