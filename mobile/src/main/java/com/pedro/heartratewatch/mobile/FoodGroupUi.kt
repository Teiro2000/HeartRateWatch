package com.pedro.heartratewatch.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private val decimalKeyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal)

private fun macroLine(totals: DayTotals) =
    "P ${formatAmount(totals.proteinG)}  C ${formatAmount(totals.carbsG)}  F ${formatAmount(totals.fatG)} g"

private fun ingredientCount(n: Int) = if (n == 1) "1 ingredient" else "$n ingredients"

@Composable
private fun Chevron(expanded: Boolean) {
    Icon(
        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
        contentDescription = if (expanded) "Collapse" else "Expand",
        tint = MaterialTheme.colorScheme.primary
    )
}

/**
 * Something logged from a group (a smoothie, pancakes): one card with the total that opens to its ingredients. Each
 * ingredient can be changed (a different food, another amount) or removed, and more can be added.
 */
@Composable
internal fun MealCard(
    meal: DayItem.Meal,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEditEntry: (FoodEntry) -> Unit,
    onDeleteEntry: (FoodEntry) -> Unit,
    onAddIngredient: () -> Unit,
    onDeleteMeal: () -> Unit
) {
    val totals = totalsOf(meal.entries)
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Chevron(expanded)
                Column(modifier = Modifier.weight(1f)) {
                    Text(meal.name, style = MaterialTheme.typography.titleSmall)
                    Text("${ingredientCount(meal.entries.size)}  ·  ${macroLine(totals)}", style = MaterialTheme.typography.bodySmall)
                }
                Text("${formatAmount(totals.calories)} kcal", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onDeleteMeal) { Text("X") }
            }
            if (expanded) {
                meal.entries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onEditEntry(entry) }.padding(start = 44.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${formatQuantity(entry.amount, entry.unit)}  ·  ${macroLine(totalsOf(listOf(entry)))}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text("${formatAmount(entry.calories)} kcal", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onDeleteEntry(entry) }) { Text("X") }
                    }
                }
                Row(modifier = Modifier.padding(start = 36.dp, bottom = 4.dp)) {
                    TextButton(onClick = onAddIngredient) { Text("+ Add ingredient") }
                }
                Text(
                    "Tap an ingredient to change the amount or swap it for another food.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 44.dp, end = 12.dp, bottom = 8.dp)
                )
            }
        }
    }
}

/** A search box and the saved foods that match it; tapping one picks it. */
@Composable
internal fun FoodPickerDialog(title: String, foods: List<Food>, onPick: (Food) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(foods, query) { foods.filter { it.name.contains(query.trim(), ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (foods.isEmpty()) {
                    Text("No saved foods to pick from. Add some in My foods.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search my foods") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (shown.isEmpty()) Text("No food matches that.", style = MaterialTheme.typography.bodyMedium)
                    shown.forEach { food -> FoodRow(food, modifier = Modifier.clickable { onPick(food) }.padding(vertical = 6.dp)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Change what an entry is after it was logged: the food (swap milk for water) and how much of it. */
@Composable
internal fun EntryEditDialog(entry: FoodEntry, foods: List<Food>, onSave: (Food, Double) -> Unit, onDismiss: () -> Unit) {
    val original = remember(entry.id) {
        foods.firstOrNull { it.id == entry.foodId } ?: foods.firstOrNull { it.name.equals(entry.name, ignoreCase = true) } ?: entry.asFood()
    }
    var food by remember { mutableStateOf(original) }
    var amountText by remember { mutableStateOf(formatAmount(entry.amount)) }
    var choosing by remember { mutableStateOf(false) }
    val amount = amountText.toDoubleOrNull()
    val valid = amount != null && amount > 0

    if (choosing) {
        FoodPickerDialog("Swap for", foods, onPick = { food = it; choosing = false }, onDismiss = { choosing = false })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(food.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${formatAmount(food.calories)} kcal per ${formatQuantity(food.servingAmount, food.unit)}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { choosing = true }) { Text("Swap for another food") }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = decimalOnly(it) },
                    label = { Text("Amount eaten (${food.unit.label})") },
                    singleLine = true,
                    keyboardOptions = decimalKeyboard,
                    modifier = Modifier.fillMaxWidth()
                )
                if (valid) Text("Total: ${formatAmount(food.calories * amount!! / food.servingAmount)} kcal", style = MaterialTheme.typography.titleSmall)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(food, amount!!) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** One line for a group in a list: its name, ingredient count and calories for one batch. */
@Composable
internal fun GroupRow(group: FoodGroup, foods: List<Food>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(group.name, style = MaterialTheme.typography.titleSmall)
        Text(
            "${ingredientCount(group.items.size)}  ·  ${formatAmount(group.totals(foods).calories)} kcal",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** A group in My foods: tap to see what's in it; log it, edit it or delete it. */
@Composable
internal fun GroupCard(
    group: FoodGroup,
    foods: List<Food>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLog: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Chevron(expanded)
                GroupRow(group, foods, modifier = Modifier.weight(1f))
            }
            if (expanded) {
                if (group.items.isEmpty()) Text("Nothing in it yet. Tap Edit to add ingredients.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 44.dp, bottom = 4.dp))
                group.items.forEach { item ->
                    val food = foods.firstOrNull { it.id == item.foodId } ?: return@forEach
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 44.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)) {
                        Text("${food.name}  ${formatQuantity(item.amount, food.unit)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("${formatAmount(food.calories * item.amount / food.servingAmount)} kcal", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onLog, enabled = group.items.isNotEmpty()) { Text("Log") }
                    TextButton(onClick = onEdit) { Text("Edit") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
            }
        }
    }
}

/** How many batches of a group to log; one batch is the amounts saved in the group. */
@Composable
internal fun GroupLogDialog(group: FoodGroup, foods: List<Food>, onLog: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("1") }
    val scale = text.toDoubleOrNull()
    val valid = scale != null && scale > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(group.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Adds each ingredient to the day as its own line, grouped together. You can change any of them afterwards.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = decimalOnly(it) },
                    label = { Text("Batches (1 = as saved, 0.5 = half)") },
                    singleLine = true,
                    keyboardOptions = decimalKeyboard,
                    modifier = Modifier.fillMaxWidth()
                )
                if (valid) {
                    val totals = group.totals(foods, scale!!)
                    Text("Total: ${formatAmount(totals.calories)} kcal", style = MaterialTheme.typography.titleSmall)
                    Text(macroLine(totals), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onLog(scale!!) }) { Text("Log") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Make or edit a group: a name and the saved foods in it, each with how much goes into one batch. */
@Composable
internal fun GroupEditorDialog(initial: FoodGroup?, foods: List<Food>, onSave: (FoodGroup) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    // The food ids and the amounts as typed.
    var rows by remember { mutableStateOf(initial?.items?.map { it.foodId to formatAmount(it.amount) }.orEmpty()) }
    var adding by remember { mutableStateOf(false) }

    val items = rows.mapNotNull { (id, text) -> text.toDoubleOrNull()?.takeIf { it > 0 }?.let { GroupItem(id, it) } }
    val valid = name.isNotBlank() && items.isNotEmpty() && items.size == rows.size
    val preview = FoodGroup("", name, items)

    if (adding) {
        FoodPickerDialog(
            "Add ingredient",
            foods.filter { food -> rows.none { it.first == food.id } },
            onPick = { food -> rows = rows + (food.id to formatAmount(food.servingAmount)); adding = false },
            onDismiss = { adding = false }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New group" else "Edit group") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (Smoothie, Pancakes...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Ingredients, for one batch", style = MaterialTheme.typography.labelLarge)
                rows.forEachIndexed { index, (id, text) ->
                    val food = foods.firstOrNull { it.id == id }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(food?.name ?: "(removed food)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { typed -> rows = rows.toMutableList().also { it[index] = id to decimalOnly(typed) } },
                            label = { Text(food?.unit?.label ?: "g") },
                            singleLine = true,
                            keyboardOptions = decimalKeyboard,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { rows = rows.filterIndexed { i, _ -> i != index } }) { Text("X") }
                    }
                }
                OutlinedButton(onClick = { adding = true }, enabled = foods.size > rows.size) { Text("+ Add ingredient") }
                if (foods.isEmpty()) Text("Save some foods in My foods first; groups are made from them.", style = MaterialTheme.typography.bodySmall)
                if (items.isNotEmpty()) {
                    val totals = preview.totals(foods)
                    Text("One batch: ${formatAmount(totals.calories)} kcal", style = MaterialTheme.typography.titleSmall)
                    Text(macroLine(totals), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(FoodGroup(initial?.id ?: CalorieRepository.newId(), name.trim().capitalizedWords(), items)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
