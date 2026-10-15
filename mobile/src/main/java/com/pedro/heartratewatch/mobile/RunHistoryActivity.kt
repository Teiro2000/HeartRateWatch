package com.pedro.heartratewatch.mobile

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.DistanceUnit
import com.pedro.heartratewatch.shared.PACE_UNITS
import com.pedro.heartratewatch.shared.RunSummary
import com.pedro.heartratewatch.shared.TrainingSettings
import com.pedro.heartratewatch.shared.formatDistance
import com.pedro.heartratewatch.shared.formatPace
import com.pedro.heartratewatch.shared.formatSpeed
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class RunHistoryActivity : ComponentActivity() {

    private val repository by lazy { RunHistoryRepository(applicationContext) }
    private val unitPreferences by lazy { UnitPreferencesRepository(applicationContext) }
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PipBoyTheme {
                RunHistoryScreen(repository, unitPreferences, settingsRepository)
            }
        }
    }
}

@Composable
private fun RunHistoryScreen(
    repository: RunHistoryRepository,
    unitPreferences: UnitPreferencesRepository,
    settingsRepository: SettingsRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runs by repository.historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // The km/miles preference doubles as the default unit when typing in a bike distance.
    val distanceUnit by unitPreferences.paceUnitFlow.collectAsStateWithLifecycle(initialValue = DistanceUnit.KILOMETERS)
    val settings by settingsRepository.settingsFlow.collectAsStateWithLifecycle(initialValue = TrainingSettings())
    // Whatever the distance-target goal is currently set to (Settings -> halfway/target alerts) --
    // a run that met or beat it gets a star in the list below, regardless of what the target was
    // when that run actually happened.
    // A star is saved with the run when it meets its activity's target (see RunHistoryRepository), so
    // it stays even after the target is changed. The first time this opens after stars started being
    // saved, the runs that already qualify are given theirs.
    LaunchedEffect(Unit) { repository.awardStarsOnce() }
    val snackbarHostState = remember { SnackbarHostState() }
    var editingDistanceFor by remember { mutableStateOf<RunSummary?>(null) }
    // The unit picked in the main Settings for showing distances beats the target's unit.
    val displayOverride by remember { DisplayDistanceUnitRepository(context.applicationContext).overrideFlow }
        .collectAsStateWithLifecycle(initialValue = null)
    var editingPackWeightFor by remember { mutableStateOf<RunSummary?>(null) }
    // Pack weight uses the same lb/kg choice as the Workout side.
    val weightUnit by remember { WorkoutRepository(context.applicationContext).weightUnitFlow }
        .collectAsStateWithLifecycle(initialValue = WeightUnit.LB)
    var confirmRemoveAll by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val csv = repository.exportCsv()
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                    ?: error("Could not open the chosen file for writing")
            }
            snackbarHostState.showSnackbar(
                if (result.isSuccess) "Exported ${runs.size} workout(s)" else "Export failed"
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not open the chosen file for reading")
                repository.importRuns(repository.parseCsv(text))
            }
            snackbarHostState.showSnackbar(
                result.fold(
                    onSuccess = { added -> "Imported $added new workout(s)" },
                    onFailure = { "Import failed -- not a run history CSV?" }
                )
            )
        }
    }

    editingPackWeightFor?.let { run ->
        PackWeightDialog(
            run = run,
            unit = weightUnit,
            onConfirm = { lb ->
                scope.launch { repository.updatePackWeight(run.startedAtMillis, lb) }
                editingPackWeightFor = null
            },
            onDismiss = { editingPackWeightFor = null }
        )
    }

    editingDistanceFor?.let { run ->
        DistanceDialog(
            run = run,
            defaultUnit = distanceUnit,
            onConfirm = { meters ->
                scope.launch { repository.updateDistance(run.startedAtMillis, meters) }
                editingDistanceFor = null
            },
            onDismiss = { editingDistanceFor = null }
        )
    }

    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            text = { Text("Are you sure you want to remove all ${runs.size} cardio sessions? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveAll = false
                    scope.launch { repository.removeAllRuns() }
                }) { Text("Remove All") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text("Cancel") } }
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp)) {
            NavButtons(onBack = rememberSystemBack(), onMainMenu = { goToMainMenu(context) })
            Text("History", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch("pulseguard_runs.csv") }) {
                    Text("Export")
                }
                Button(onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) }) {
                    Text("Import")
                }
            }
            if (runs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmRemoveAll = true },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Remove All Cardio Sessions", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(16.dp))

            if (runs.isEmpty()) {
                Text("No workouts recorded yet -- finish one on your watch to see it here.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(runs, key = { it.startedAtMillis }) { run ->
                        RunRow(
                            run,
                            reachedTarget = run.reachedTarget,
                            paceUnit = settings.paceUnit,
                            distanceUnit = displayOverride ?: settings.distanceUnit,
                            onEditDistance = { editingDistanceFor = run },
                            weightUnit = weightUnit,
                            onEditPackWeight = { editingPackWeightFor = run },
                            onDiscard = {
                                scope.launch {
                                    repository.removeRun(run.startedAtMillis)
                                    val result = snackbarHostState.showSnackbar(
                                        "Workout discarded",
                                        actionLabel = "Undo",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        repository.addRun(run)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunRow(
    run: RunSummary,
    reachedTarget: Boolean,
    paceUnit: DistanceUnit,
    distanceUnit: DistanceUnit,
    onEditDistance: () -> Unit,
    weightUnit: WeightUnit,
    onEditPackWeight: () -> Unit,
    onDiscard: () -> Unit
) {
    val isBike = run.activityType == ActivityType.STATIONARY_BIKE
    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(run.startedAtMillis)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    // Reached the distance-target goal from Settings -- see distanceTarget above.
                    if (reachedTarget) Text("★", color = Color(0xFFFFC107))
                }
                Button(onClick = onDiscard) { Text("Discard") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(run.activityType.displayName(), style = MaterialTheme.typography.labelLarge)
                Text("${Progression.cardioXp(run)} XP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            // The route, for sessions that recorded one (outdoor, from the watch or the phone).
            if (run.activityType.usesGps) RouteMap(run.startedAtMillis, distanceUnit)
            Text("Duration: %d:%02d".format(run.durationSeconds / 60, run.durationSeconds % 60))
            if (run.stoppedSeconds > 0) {
                Text("Time stopped: %d:%02d".format(run.stoppedSeconds / 60, run.stoppedSeconds % 60))
            }
            // Sessions tracked by the phone alone have no heart rate.
            if (run.avgBpm > 0) Text("Avg ${run.avgBpm} bpm, max ${run.maxBpm} bpm, min ${run.minBpm} bpm")
            if (isBike) {
                Text(
                    if (run.distanceMeters > 0f) "Distance: ${formatDistance(run.distanceMeters, distanceUnit)}"
                    else "Distance: not entered yet"
                )
                Button(onClick = onEditDistance) {
                    Text(if (run.distanceMeters > 0f) "Edit distance" else "Enter distance")
                }
            } else {
                Text("Distance: ${formatDistance(run.distanceMeters, distanceUnit)}")
                run.avgPaceSecPerKm?.let {
                    if (run.activityType.showsSpeed) Text("Avg speed (moving): ${formatSpeed(it, paceUnit)}")
                    else Text("Avg pace: ${formatPace(it, paceUnit)}")
                }
                if (run.activityType == ActivityType.WALK) {
                    if (run.packWeightLb > 0f) {
                        val shown = weightUnit.fromLb(run.packWeightLb.toDouble())
                        Text("Pack weight: ${formatWeight(shown)} ${weightUnit.symbol}")
                    }
                    Button(onClick = onEditPackWeight) {
                        Text(if (run.packWeightLb > 0f) "Edit pack weight" else "Add pack weight")
                    }
                }
            }
        }
    }
}

/** The weight carried on a walk (rucking), typed in the Workout side's lb/kg unit; stored in lb. */
@Composable
private fun PackWeightDialog(run: RunSummary, unit: WeightUnit, onConfirm: (lb: Float) -> Unit, onDismiss: () -> Unit) {
    var text by remember {
        mutableStateOf(if (run.packWeightLb > 0f) formatWeight(unit.fromLb(run.packWeightLb.toDouble())) else "")
    }
    val value = text.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pack weight") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Weight carried (${unit.symbol}), 0 to clear") },
                isError = text.isNotBlank() && value == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = value != null && value >= 0,
                onClick = { onConfirm(unit.toLb(value!!).toFloat()) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Typed-in distance for a stationary bike ride, entered as whatever the bike's display shows. */
@Composable
private fun DistanceDialog(
    run: RunSummary,
    defaultUnit: DistanceUnit,
    onConfirm: (meters: Float) -> Unit,
    onDismiss: () -> Unit
) {
    var unit by remember { mutableStateOf(defaultUnit) }
    var text by remember {
        mutableStateOf(
            if (run.distanceMeters > 0f) {
                String.format(Locale.US, "%.2f", run.distanceMeters / defaultUnit.metersPerUnit)
            } else ""
        )
    }
    // Accept a decimal comma as well as a point, since the keypad follows the phone's locale.
    val value = text.replace(',', '.').toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bike distance") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Distance") },
                    isError = text.isNotBlank() && value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                UnitDropdown(selected = unit, options = PACE_UNITS, onSelect = { unit = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = value != null && value > 0,
                onClick = { onConfirm((value!! * unit.metersPerUnit).toFloat()) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
