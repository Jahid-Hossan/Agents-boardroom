package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val BoardroomDarkColorScheme = darkColorScheme(
    primary = AmberAccent,
    onPrimary = AmberOnAccent,
    primaryContainer = BgElevated,
    onPrimaryContainer = AmberHover,
    secondary = HandoffBlue,
    onSecondary = TextPrimary,
    secondaryContainer = BgElevated,
    onSecondaryContainer = TextPrimary,
    tertiary = StatusOk,
    onTertiary = AmberOnAccent,
    background = BgCanvas,
    onBackground = TextPrimary,
    surface = BgSurface,
    onSurface = TextPrimary,
    surfaceVariant = BgElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = BgCanvas,
    surfaceContainerLow = BgSurface,
    surfaceContainer = BgElevated,
    surfaceContainerHigh = BgHover,
    outline = BorderStrong,
    outlineVariant = BorderSubtle,
    error = StatusErr,
    onError = TextPrimary,
    errorContainer = StatusErrSoft,
    onErrorContainer = StatusErrText
)

val BoardroomShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp)
)

@Composable
fun MuseBoardroomTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = BoardroomDarkColorScheme,
        typography = BoardroomTypography,
        shapes = BoardroomShapes,
        content = content
    )
}
