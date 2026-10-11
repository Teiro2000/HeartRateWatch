package com.pedro.heartratewatch.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PipBoyTheme { AppSettingsScreen(themePreferenceRepository) }
        }
    }
}

@Composable
private fun AppSettingsScreen(themePreferenceRepository: ThemePreferenceRepository) {
    val scope = rememberCoroutineScope()
    val useLightTheme by themePreferenceRepository.useLightThemeFlow.collectAsStateWithLifecycle(initialValue = false)
    val back = rememberSystemBack()
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
        }
    }
}
