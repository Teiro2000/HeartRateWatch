package com.pedro.heartratewatch.mobile.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
    val useLightTheme by remember(context) { ThemePreferenceRepository(context).useLightThemeFlow }
        .collectAsStateWithLifecycle(initialValue = false)

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
    val typography = if (useLightTheme) MaterialTheme.typography.serifTitles() else MaterialTheme.typography.monospaced()
    MaterialTheme(colorScheme = colorScheme, typography = typography) {
        Surface(color = colorScheme.background, content = content)
    }
}
