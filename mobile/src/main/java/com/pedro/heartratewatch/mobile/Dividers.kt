package com.pedro.heartratewatch.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The divider lines between sections. The same name and parameters as Material's, used instead of it
 * by the files in this package: in the light theme the lines are drawn thicker so they stand out
 * against the pale background, in the dark theme they keep the usual hairline.
 */
@Composable
fun HorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp? = null,
    color: Color = MaterialTheme.colorScheme.outlineVariant
) {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    androidx.compose.material3.HorizontalDivider(
        modifier = modifier,
        thickness = thickness ?: if (light) 2.dp else 1.dp,
        color = color
    )
}
