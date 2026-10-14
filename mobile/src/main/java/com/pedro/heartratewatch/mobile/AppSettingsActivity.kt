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
import androidx.compose.foundation.layout.padding
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
    private val cardioSettingsRepository by lazy { SettingsRepository(applicationContext) }
    private val calibrationRepository by lazy { CalibrationRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PipBoyTheme {
                AppSettingsScreen(
                    themePreferenceRepository, reminderRepository, alertPlayer,
                    workoutRepository, cardioSettingsRepository, calibrationRepository
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
    workoutRepository: WorkoutRepository,
    cardioSettingsRepository: SettingsRepository,
    calibrationRepository: CalibrationRepository
) {
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
                    Text(
                        "A notification at the time below if you haven't worked out or done cardio yet that day. Not sent once today's streak is done.",
                        style = MaterialTheme.typography.bodySmall
                    )
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
            var cardioOpen by remember { mutableStateOf(false) }
            AccordionSection(
                "Cardio settings",
                expanded = cardioOpen,
                onToggle = { cardioOpen = !cardioOpen }
            ) {
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
                CardioSettingsContent(cardioSettingsRepository, calibrationRepository)
            }
        }
    }
}
