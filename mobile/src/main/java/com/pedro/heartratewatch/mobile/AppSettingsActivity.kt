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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PipBoyTheme { AppSettingsScreen(themePreferenceRepository, reminderRepository) }
        }
    }
}

@Composable
private fun AppSettingsScreen(themePreferenceRepository: ThemePreferenceRepository, reminderRepository: StreakReminderRepository) {
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
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
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
            }
        }
    }
}
