package com.pedro.heartratewatch.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pedro.heartratewatch.mobile.theme.PipBoyTheme

/** Back to the Run/Workout picker from anywhere, clearing the screens stacked above it. */
fun goToMainMenu(context: Context) {
    context.startActivity(
        Intent(context, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    )
}

/**
 * What the app opens to: pick Cardio (the existing run-tracking screen, MainActivity) or Workout
 * (the gym log, WorkoutActivity).
 */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PipBoyTheme { HomeScreen() }
        }
    }
}

/** What the system back button does for the current activity -- for screens that show their own Back button. */
@Composable
fun rememberSystemBack(): () -> Unit {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    return { dispatcher?.onBackPressed() }
}

/**
 * The "< Back" and "Main menu" buttons shown top-left on every screen below the Cardio / Workout
 * menus. [onBack] null hides Back (for a screen that is itself a menu, where Back would be the
 * same as Main menu).
 */
@Composable
fun NavButtons(onBack: (() -> Unit)?, onMainMenu: () -> Unit) {
    // Two separate bordered buttons with a clear gap, so Back can't be mistaken for Main menu.
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        if (onBack != null) OutlinedButton(onClick = onBack) { Text("< Back") }
        OutlinedButton(onClick = onMainMenu) { Text("Main menu") }
    }
}

/** One row in a menu list (Cardio and Workout menus share it): label on the left, chevron on the right. */
@Composable
fun MenuListRow(label: String, onClick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(">", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Pulse Guard", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HomeTile("Cardio", Modifier.weight(1f)) {
                    context.startActivity(Intent(context, MainActivity::class.java))
                }
                HomeTile("Workout", Modifier.weight(1f)) {
                    context.startActivity(Intent(context, WorkoutActivity::class.java))
                }
            }
            OutlinedButton(
                onClick = { context.startActivity(Intent(context, AppSettingsActivity::class.java)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Settings") }
        }
    }
}

@Composable
private fun HomeTile(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(modifier = modifier.aspectRatio(1f).clickable(onClick = onClick)) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
}
