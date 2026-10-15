package com.pedro.heartratewatch.mobile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fireworks: bursts of sparks that fly out, fall under gravity and fade, one after another for as long as
 * this is on screen. Each burst is worked out from its own number (where, what colour, how many sparks),
 * so there is nothing to store and it can keep going indefinitely. [rankUp] makes it bigger and busier.
 */
@Composable
fun Fireworks(colors: List<Color>, rankUp: Boolean, modifier: Modifier = Modifier) {
    var timeMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillisCompat()
        while (true) timeMs = withFrameMillisCompat() - start
    }
    val intervalMs = if (rankUp) 260L else 420L
    val lifeMs = 1700L
    val sparks = if (rankUp) 56 else 40

    Canvas(modifier = modifier) {
        val density = this.density
        val firstBurst = (timeMs - lifeMs).coerceAtLeast(0L) / intervalMs
        val lastBurst = timeMs / intervalMs
        for (n in firstBurst..lastBurst) {
            val age = (timeMs - n * intervalMs).toFloat() / lifeMs
            if (age < 0f || age > 1f) continue
            val random = Random(n * 7919L + 13L)
            val origin = Offset(
                size.width * (0.12f + 0.76f * random.nextFloat()),
                size.height * (0.10f + 0.40f * random.nextFloat())
            )
            val color = colors[random.nextInt(colors.size)]
            val speed = (140f + 170f * random.nextFloat()) * density
            val seconds = age * lifeMs / 1000f
            val gravity = 260f * density
            // The first instant is a bright flash where the rocket went off.
            if (age < 0.08f) {
                drawCircle(Color.White.copy(alpha = 1f - age / 0.08f), radius = 14f * density * (0.4f + age * 8f), center = origin)
            }
            for (i in 0 until sparks) {
                val angle = (2.0 * Math.PI * i / sparks).toFloat() + random.nextFloat() * 0.15f
                val v = speed * (0.55f + 0.45f * random.nextFloat())
                val x = origin.x + cos(angle) * v * seconds
                val y = origin.y + sin(angle) * v * seconds + 0.5f * gravity * seconds * seconds
                val fade = (1f - age).coerceIn(0f, 1f)
                val radius = (3.2f * density) * (0.35f + 0.65f * fade)
                drawCircle(color.copy(alpha = fade), radius = radius, center = Offset(x, y))
                // A short tail behind each spark.
                drawCircle(color.copy(alpha = fade * 0.35f), radius = radius * 0.6f, center = Offset(x - cos(angle) * 5f * density, y - sin(angle) * 5f * density))
            }
        }
    }
}

private suspend fun withFrameMillisCompat(): Long = androidx.compose.runtime.withFrameMillis { it }

/** The level-up (or rank-up) screen: a dimmed backdrop with fireworks going off behind a card showing the new level. */
@Composable
fun LevelUpDialog(standing: Progression.Standing, rankUp: Boolean, onDismiss: () -> Unit) {
    val scale by animateFloatAsState(targetValue = 1f, animationSpec = spring(dampingRatio = 0.35f, stiffness = 200f), label = "pop")
    val palette = remember(standing.rank) {
        listOf(standing.rank.color, Color.White, Color(0xFFFFD54F), Color(0xFF4DD0E1), Color(0xFFFF8A65))
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Fireworks(colors = palette, rankUp = rankUp, modifier = Modifier.fillMaxSize())
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(2.dp, standing.rank.color),
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        if (rankUp) "Rank up!" else "Level up!",
                        style = MaterialTheme.typography.headlineMedium,
                        color = standing.rank.color,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "${standing.level}",
                        style = MaterialTheme.typography.displayLarge,
                        color = standing.rank.color,
                        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.padding(vertical = 8.dp)
                    )
                    Text("Level ${standing.level}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (rankUp) "You are now ${standing.rank.name}!" else standing.rank.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = standing.rank.color
                    )
                    standing.nextRank?.let {
                        Text(
                            "${it.minLevel - standing.level} more to ${it.name}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Nice") }
                }
            }
        }
    }
}
