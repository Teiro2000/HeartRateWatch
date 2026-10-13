package com.pedro.heartratewatch.mobile.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.mobile.ThemePreferenceRepository

// Pip-Boy inspired: green phosphor CRT, monospace text. Red is kept for error/danger since it
// reads universally even against an otherwise monochrome-green scheme -- same reasoning as the
// watch tile's permanent Start/Stop bar colors. Dark is literal black, not a dark-green tint --
// tested that tint in practice and it read as "light green on dark green" rather than the
// intended "light green on black".
private val PipBoyGreen = Color(0xFF4FFF8A)
private val PipBoyGreenDim = Color(0xFF2F8F57)
private val PipBoyBlack = Color(0xFF000000)
private val PipBoyBlackContainer = Color(0xFF061109)
private val PipBoyRed = Color(0xFFFF6B5F)

// Light mode is "Sage": warm paper, soft green, serif titles -- a calm daytime look rather than a
// light Pip-Boy. Dark mode is "Terminal" (the Pip-Boy look above).
private val SageGreen = Color(0xFF4F6B52)
private val SagePaper = Color(0xFFF6F3EC)
private val SageCard = Color(0xFFE9E3D4)
private val SageInk = Color(0xFF2F3A2F)
private val SageMuted = Color(0xFF6B6F63)
private val SageOutline = Color(0xFFA9A28F)
private val SageLine = Color(0xFFDDD6C6)
private val SageRed = Color(0xFFC62828)
private val TerminalLine = Color(0xFF1E6B3E)

/** Sage: sans-serif body text with serif titles. */
private fun Typography.serifTitles(): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = FontFamily.Serif),
    displayMedium = displayMedium.copy(fontFamily = FontFamily.Serif),
    displaySmall = displaySmall.copy(fontFamily = FontFamily.Serif),
    headlineLarge = headlineLarge.copy(fontFamily = FontFamily.Serif),
    headlineMedium = headlineMedium.copy(fontFamily = FontFamily.Serif),
    headlineSmall = headlineSmall.copy(fontFamily = FontFamily.Serif)
)

/** Terminal: monospace everywhere. */
private fun Typography.monospaced(): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = FontFamily.Monospace),
    displayMedium = displayMedium.copy(fontFamily = FontFamily.Monospace),
    displaySmall = displaySmall.copy(fontFamily = FontFamily.Monospace),
    headlineLarge = headlineLarge.copy(fontFamily = FontFamily.Monospace),
    headlineMedium = headlineMedium.copy(fontFamily = FontFamily.Monospace),
    headlineSmall = headlineSmall.copy(fontFamily = FontFamily.Monospace),
    titleLarge = titleLarge.copy(fontFamily = FontFamily.Monospace),
    titleMedium = titleMedium.copy(fontFamily = FontFamily.Monospace),
    titleSmall = titleSmall.copy(fontFamily = FontFamily.Monospace),
    bodyLarge = bodyLarge.copy(fontFamily = FontFamily.Monospace),
    bodyMedium = bodyMedium.copy(fontFamily = FontFamily.Monospace),
    bodySmall = bodySmall.copy(fontFamily = FontFamily.Monospace),
    labelLarge = labelLarge.copy(fontFamily = FontFamily.Monospace),
    labelMedium = labelMedium.copy(fontFamily = FontFamily.Monospace),
    labelSmall = labelSmall.copy(fontFamily = FontFamily.Monospace)
)

@Composable
fun PipBoyTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val themePreference = remember(context) { ThemePreferenceRepository(context) }
    val useLightTheme by themePreference.useLightThemeFlow
        .collectAsStateWithLifecycle(initialValue = themePreference.currentUseLightTheme())

    // Building on darkColorScheme()/lightColorScheme() (rather than copying MaterialTheme's own
    // default scheme) matters: each fills in every role with a sensible default of the right
    // brightness first, including ones not explicitly overridden below (dialog/dropdown/card
    // containers, outlines, etc.) -- copying a mismatched default left some of those the wrong
    // brightness regardless of the handful of roles actually listed here.
    val colorScheme = if (useLightTheme) {
        lightColorScheme(
            primary = SageGreen,
            onPrimary = SagePaper,
            primaryContainer = SageCard,
            onPrimaryContainer = SageInk,
            secondary = SageGreen,
            onSecondary = SagePaper,
            secondaryContainer = SageCard,
            onSecondaryContainer = SageInk,
            tertiary = SageGreen,
            onTertiary = SagePaper,
            tertiaryContainer = SageCard,
            onTertiaryContainer = SageInk,
            background = SagePaper,
            onBackground = SageInk,
            surface = SagePaper,
            onSurface = SageInk,
            surfaceVariant = SageCard,
            onSurfaceVariant = SageMuted,
            outline = SageOutline,
            outlineVariant = SageLine,
            error = SageRed,
            onError = SagePaper
        )
    } else {
        darkColorScheme(
            primary = PipBoyGreen,
            onPrimary = PipBoyBlack,
            primaryContainer = PipBoyBlackContainer,
            onPrimaryContainer = PipBoyGreen,
            secondary = PipBoyGreenDim,
            onSecondary = PipBoyBlack,
            secondaryContainer = PipBoyBlackContainer,
            onSecondaryContainer = PipBoyGreen,
            tertiary = PipBoyGreenDim,
            onTertiary = PipBoyBlack,
            tertiaryContainer = PipBoyBlackContainer,
            onTertiaryContainer = PipBoyGreen,
            background = PipBoyBlack,
            onBackground = PipBoyGreen,
            surface = PipBoyBlack,
            onSurface = PipBoyGreen,
            surfaceVariant = PipBoyBlackContainer,
            onSurfaceVariant = PipBoyGreenDim,
            outline = PipBoyGreenDim,
            outlineVariant = TerminalLine,
            error = PipBoyRed,
            onError = PipBoyBlack
        )
    }
    // The window behind Compose (visible while a screen opens) matches the theme, not the manifest's light default.
    val window = (context as? android.app.Activity)?.window
    SideEffect { window?.setBackgroundDrawable(ColorDrawable(colorScheme.background.toArgb())) }
    val typography = if (useLightTheme) MaterialTheme.typography.serifTitles() else MaterialTheme.typography.monospaced()
    // The wallpaper is drawn once behind everything. Screens are Scaffolds, which paint the scheme's
    // background color over whatever is behind them, so that role is made transparent and the real
    // color is painted by the Surface underneath the wallpaper instead.
    val solidBackground = colorScheme.background
    MaterialTheme(colorScheme = colorScheme.copy(background = Color.Transparent), typography = typography) {
        Surface(color = solidBackground) {
            Box(modifier = Modifier.fillMaxSize().drawBehind { drawWallpaper(useLightTheme) }) {
                content()
            }
        }
    }
}

/**
 * Terminal (dark) gets a faint green grid, kept low contrast so text and charts on top stay as
 * readable as on a plain background. Sage (light) stays plain.
 */
private fun DrawScope.drawWallpaper(light: Boolean) {
    if (light) return
    val step = 32.dp.toPx()
    val color = PipBoyGreen.copy(alpha = 0.10f)
    val stroke = 1.dp.toPx()
    var x = 0f
    while (x <= size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
        x += step
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        y += step
    }
}
