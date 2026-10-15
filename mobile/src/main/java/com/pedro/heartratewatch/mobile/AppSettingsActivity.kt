package com.pedro.heartratewatch.mobile

import android.Manifest
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pedro.heartratewatch.shared.AlertType
import com.pedro.heartratewatch.shared.DistanceUnit
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.mobile.theme.PipBoyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * App-wide settings, opened from the main menu. Right now just the light theme toggle, which
 * applies to the whole phone app (Cardio and Workout); the Cardio screen keeps its own
 * run-tracking settings.
 */
class AppSettingsActivity : ComponentActivity() {

    private val themePreferenceRepository by lazy { ThemePreferenceRepository(applicationContext) }
    private val reminderRepository by lazy { StreakReminderRepository(applicationContext) }
    private val alertPlayer by lazy { AlertPlayer(applicationContext) }
    private val workoutRepository by lazy { WorkoutRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PipBoyTheme {
                AppSettingsScreen(
                    themePreferenceRepository, reminderRepository, alertPlayer,
                    workoutRepository
                )
            }
        }
    }
}

@Composable
private fun AppSettingsScreen(
    themePreferenceRepository: ThemePreferenceRepository,
    reminderRepository: StreakReminderRepository,
    alertPlayer: AlertPlayer,
    workoutRepository: WorkoutRepository
) {
    LevelUpHost()
    val scope = rememberCoroutineScope()
    val useLightTheme by themePreferenceRepository.useLightThemeFlow.collectAsStateWithLifecycle(initialValue = false)
    val back = rememberSystemBack()
    val context = LocalContext.current
    val reminder by reminderRepository.configFlow.collectAsStateWithLifecycle(initialValue = StreakReminderConfig())
    // Turning the reminder on needs the notification permission (Android 13+); it stays off if denied.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { reminderRepository.save(reminder.copy(enabled = true)) }
    }
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = back) { Text("< Back") }
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Light mode", modifier = Modifier.weight(1f))
                Switch(
                    checked = useLightTheme,
                    onCheckedChange = { scope.launch { themePreferenceRepository.setUseLightTheme(it) } }
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Streak reminder")
                }
                Switch(
                    checked = reminder.enabled,
                    onCheckedChange = { on ->
                        if (on && !StreakReminder.canNotify(context)) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            scope.launch { reminderRepository.save(reminder.copy(enabled = on)) }
                        }
                    }
                )
            }
            HorizontalDivider()
            if (reminder.enabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Reminder time")
                    OutlinedButton(onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute -> scope.launch { reminderRepository.save(reminder.copy(hour = hour, minute = minute)) } },
                            reminder.hour,
                            reminder.minute,
                            DateFormat.is24HourFormat(context)
                        ).show()
                    }) {
                        Text(
                            java.time.LocalTime.of(reminder.hour, reminder.minute)
                                .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
                        )
                    }
                }
                HorizontalDivider()
            }
            // Workout: the unit weights are shown and entered in.
            val weightUnit by workoutRepository.weightUnitFlow.collectAsStateWithLifecycle(initialValue = WeightUnit.LB)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Weight unit")
                    Text("For Workout weights.", style = MaterialTheme.typography.bodySmall)
                }
                WeightUnit.entries.forEach { option ->
                    if (option == weightUnit) {
                        Button(onClick = {}) { Text(option.symbol) }
                    } else {
                        OutlinedButton(onClick = { scope.launch { workoutRepository.setWeightUnit(option) } }) { Text(option.symbol) }
                    }
                }
            }
            HorizontalDivider()
            // Cardio: the unit distances are shown in on the phone, ahead of the distance target's own unit.
            val displayRepo = remember { DisplayDistanceUnitRepository(context.applicationContext) }
            val displayOverride by displayRepo.overrideFlow.collectAsStateWithLifecycle(initialValue = null)
            var unitMenuOpen by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Distance unit")
                    Text(
                        "How distances are shown in History and Stats. Overrides the unit chosen for the distance target.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Box {
                    OutlinedButton(onClick = { unitMenuOpen = true }) {
                        Text((displayOverride?.symbol ?: "Same as target") + "  " + (if (unitMenuOpen) "▲" else "▼"))
                    }
                    DropdownMenu(expanded = unitMenuOpen, onDismissRequest = { unitMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Same as target") },
                            onClick = { scope.launch { displayRepo.set(null) }; unitMenuOpen = false }
                        )
                        DistanceUnit.entries.forEach { unit ->
                            DropdownMenuItem(
                                text = { Text(unit.symbol) },
                                onClick = { scope.launch { displayRepo.set(unit) }; unitMenuOpen = false }
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            var soundsOpen by remember { mutableStateOf(false) }
            val soundPickers = AlertType.entries.associateWith { rememberSoundPicker(it, alertPlayer) }
            AccordionSection(
                "Custom sounds",
                expanded = soundsOpen,
                onToggle = { soundsOpen = !soundsOpen }
            ) {
                Text(
                    "The sounds your phone plays for cardio alerts: heart rate, pace and distance. They apply to every cardio activity.",
                    style = MaterialTheme.typography.bodySmall
                )
                AlertType.entries.forEach { type ->
                    AlertSoundRow(
                        label = type.soundPickerLabel(),
                        initialDb = alertPlayer.volumeDb(type),
                        onChoose = { soundPickers.getValue(type).launch(SUPPORTED_AUDIO_MIME_TYPES) },
                        onTest = { alertPlayer.playNow(type) },
                        onVolumeChange = { alertPlayer.setVolumeDb(type, it) }
                    )
                }
            }
            // Backup: everything on this phone in one file, and back again.
            var backupMessage by remember { mutableStateOf<String?>(null) }
            var confirmRestore by remember { mutableStateOf(false) }
            val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri != null) scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openOutputStream(uri)!!.use { Backup.write(context.applicationContext, it) } }
                    }
                    backupMessage = result.fold({ "Backup saved." }, { "Couldn't write the backup: ${it.message ?: "unknown error"}" })
                }
            }
            val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    val restored = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openInputStream(uri)!!.use { Backup.restore(context.applicationContext, it) } }
                            .getOrDefault(false)
                    }
                    // On success the app restarts itself, so only a bad file gets here.
                    if (!restored) backupMessage = "That isn't a PulseGuard backup, so nothing was changed."
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Backup")
                    Text(
                        "All your history, settings and routes in one file. Custom alert sounds aren't included.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // As wide as the widest button, so the text beside it keeps the rest of the row.
                Column(modifier = Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        saveBackup.launch("PulseGuard backup ${java.time.LocalDate.now()}.zip")
                    }, modifier = Modifier.fillMaxWidth()) { Text("Back up") }
                    OutlinedButton(onClick = { confirmRestore = true }, modifier = Modifier.fillMaxWidth()) { Text("Restore") }
                }
            }
            HorizontalDivider()
            if (confirmRestore) {
                AlertDialog(
                    onDismissRequest = { confirmRestore = false },
                    title = { Text("Restore from a backup?") },
                    text = {
                        Text(
                            "Everything on this phone (history, workouts, settings, routes, level) is replaced by what's in the backup, " +
                                "and the app restarts. Back up first if you want to keep what's here."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmRestore = false
                            pickBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        }) { Text("Choose backup") }
                    },
                    dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } }
                )
            }
            backupMessage?.let { message ->
                AlertDialog(
                    onDismissRequest = { backupMessage = null },
                    text = { Text(message) },
                    confirmButton = { TextButton(onClick = { backupMessage = null }) { Text("OK") } }
                )
            }
            // Level: start over from level 1 (history, streak and personal bests are untouched).
            var confirmReset by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Level")
                    Text("Start over from level 1. Your history isn't touched.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { confirmReset = true },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider()
            if (confirmReset) {
                AlertDialog(
                    onDismissRequest = { confirmReset = false },
                    title = { Text("Reset your level?") },
                    text = {
                        Text(
                            "Your level and rank go back to the start and only sessions from now on earn XP. " +
                                "Your history, streak and personal bests stay exactly as they are. This can't be undone."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmReset = false
                            scope.launch { ProgressionSettingsRepository(context.applicationContext).reset() }
                        }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
                )
            }
        }
    }
}
