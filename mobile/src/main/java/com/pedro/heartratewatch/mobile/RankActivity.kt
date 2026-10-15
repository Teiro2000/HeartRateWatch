package com.pedro.heartratewatch.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.mobile.theme.PipBoyTheme
import java.text.NumberFormat

/** Everything XP: the level, the rank, how the XP adds up, and the rank ladder. */
class RankActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PipBoyTheme { RankScreen() } }
    }
}

/**
 * XP, level and rank worked out from both histories (see [Progression]); null until everything has loaded,
 * so nothing is decided from a half-loaded state (a level-up check would otherwise see level 1 for a moment).
 */
@Composable
internal fun rememberBreakdownOrNull(context: Context): Progression.Breakdown? {
    val app = context.applicationContext
    val runs by remember { RunHistoryRepository(app).historyFlow }.collectAsStateWithLifecycle(initialValue = null)
    val workouts by remember { WorkoutRepository(app).historyFlow }.collectAsStateWithLifecycle(initialValue = null)
    val settings = remember { ProgressionSettingsRepository(app) }
    val resetAt by settings.resetAtFlow.collectAsStateWithLifecycle(initialValue = null)
    val penaltiesFrom by settings.penaltiesFromFlow.collectAsStateWithLifecycle(initialValue = null)
    // From the first time levels are shown, a full day without a session costs a level.
    LaunchedEffect(Unit) { settings.ensurePenaltiesStarted() }
    val loadedRuns = runs ?: return null
    val loadedWorkouts = workouts ?: return null
    val loadedResetAt = resetAt ?: return null
    val loadedPenalties = penaltiesFrom ?: return null
    return remember(loadedRuns, loadedWorkouts, loadedResetAt, loadedPenalties) {
        Progression.breakdown(
            loadedRuns, loadedWorkouts, loadedResetAt,
            penaltiesFrom = loadedPenalties.takeIf { it != java.time.LocalDate.MAX }
        )
    }
}

/** Like [rememberBreakdownOrNull], showing zero XP while loading. */
@Composable
internal fun rememberBreakdown(context: Context): Progression.Breakdown =
    rememberBreakdownOrNull(context) ?: Progression.Breakdown(0, 0, 0)

/**
 * The "Level up!" / "Rank up!" moment. Place it on a screen and it shows a celebration the first time that
 * screen sees a higher level than the last one celebrated. Going down a level (a missed day) is quietly
 * remembered with no pop-up, and the very first look at levels just records where you are.
 */
@Composable
internal fun LevelUpHost() {
    val context = LocalContext.current
    val breakdown = rememberBreakdownOrNull(context) ?: return
    val level = Progression.levelForXp(breakdown.total)
    val settings = remember { ProgressionSettingsRepository(context.applicationContext) }
    val celebrated by settings.celebratedLevelFlow.collectAsStateWithLifecycle(initialValue = null)
    val haptics = LocalHapticFeedback.current
    var showing by remember { mutableStateOf<Pair<Progression.Standing, Boolean>?>(null) }

    LaunchedEffect(level, celebrated) {
        val last = celebrated ?: return@LaunchedEffect
        when {
            last < 0 || level < last -> settings.setCelebratedLevel(level)
            level > last -> {
                val rankUp = Progression.rankForLevel(level) != Progression.rankForLevel(last)
                settings.setCelebratedLevel(level)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                showing = Progression.standing(breakdown.total) to rankUp
            }
        }
    }

    showing?.let { (standing, rankUp) -> LevelUpDialog(standing, rankUp) { showing = null } }
}

private fun Int.grouped(): String = NumberFormat.getIntegerInstance().format(this)

/**
 * Streak protection: today's XP (cardio and workouts together) out of the 600 that banks a rest day, with a mark at
 * 300, where the day counts for the streak, and how many rest days are banked.
 */
@Composable
internal fun RestProgress(breakdown: Progression.Breakdown, onClick: (() -> Unit)? = null) {
    val xp = breakdown.todayXp
    val markColor = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Streak protection", style = MaterialTheme.typography.titleSmall)
            Text(
                "${breakdown.restCredits} of ${Progression.MAX_REST_CREDITS} rest days banked",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(10.dp)) {
            LinearProgressIndicator(
                progress = { (xp.toFloat() / Progression.REST_XP).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize()
            )
            // The streak line, halfway along the bar.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val x = size.width * Progression.STREAK_XP / Progression.REST_XP
                drawLine(markColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "$xp / ${Progression.REST_XP} XP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (breakdown.todayCounts) "Streak kept today" else "Streak at ${Progression.STREAK_XP} XP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The compact "Level 12 - Silver" bar on the main menu; tapping it opens [RankActivity]. */
@Composable
internal fun RankSummary(standing: Progression.Standing, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Level ${standing.level}", style = MaterialTheme.typography.titleMedium)
            Text(standing.rank.name, style = MaterialTheme.typography.titleMedium, color = standing.rank.color)
        }
        LinearProgressIndicator(
            progress = { standing.fraction },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = standing.rank.color
        )
        Text(
            "${standing.xpIntoLevel.grouped()} / ${standing.xpForNext.grouped()} XP to level ${standing.level + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RankScreen() {
    val context = LocalContext.current
    val breakdown = rememberBreakdown(context)
    val standing = remember(breakdown) { Progression.standing(breakdown.total) }
    val back = rememberSystemBack()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                NavButtons(onBack = back, onMainMenu = { goToMainMenu(context) })
                Text(
                    "Rank",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )
            }
            Text(
                standing.rank.name,
                style = MaterialTheme.typography.headlineMedium,
                color = standing.rank.color,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Level ${standing.level}",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            RankSummary(standing, onClick = {})
            RestProgress(breakdown)
            HorizontalDivider()

            Text("Where your XP comes from", style = MaterialTheme.typography.titleMedium)
            XpLine("Cardio", breakdown.cardio)
            XpLine("Workouts", breakdown.workouts)
            XpLine("Streak bonus", breakdown.streak)
            if (breakdown.missedDays > 0) XpLine("Missed days (${breakdown.missedDays}, a level each)", -breakdown.lost)
            XpLine("Total", breakdown.total, bold = true)
            Text(
                "Cardio: distance and moving time, with a bonus for each target star. Workouts: the session, each set and the weight lifted. " +
                    "A level is shared across Cardio and Workout.",
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider()

            Text("Ranks", style = MaterialTheme.typography.titleMedium)
            Progression.RANKS.forEach { rank ->
                val current = rank == standing.rank
                OutlinedCard(
                    border = BorderStroke(
                        if (current) 2.dp else 1.dp,
                        if (current) rank.color else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(rank.name, style = MaterialTheme.typography.titleMedium, color = rank.color)
                        Text(
                            "Level ${rank.minLevel}+" + if (current) "   you are here" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            Text("", modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun XpLine(label: String, xp: Int, bold: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
        Text(label, style = style)
        Text("${xp.grouped()} XP", style = style)
    }
}
