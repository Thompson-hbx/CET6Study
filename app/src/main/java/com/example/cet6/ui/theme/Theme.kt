package com.example.cet6.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary, onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer, onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = DarkPrimary,
    secondary = LightSecondary, onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer, onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary, onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer, onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground, onBackground = LightOnBackground,
    surface = LightSurface, onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant, onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightPrimary,
    inverseSurface = Color(0xFF2C3036), inverseOnSurface = Color(0xFFEEF1F5),
    error = LightMarkMust, onError = Color.White,
    errorContainer = LightMarkMustSoft, onErrorContainer = Color(0xFF5C1610),
    outline = LightOutline, outlineVariant = LightOutlineVariant,
    scrim = Color.Black,
    surfaceBright = LightSurfaceLowest, surfaceDim = LightSurfaceHighest,
    surfaceContainerLowest = LightSurfaceLowest, surfaceContainerLow = LightSurfaceLow,
    surfaceContainer = LightSurfaceMid, surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = LightSurfaceHighest
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary, onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer, onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = LightPrimary,
    secondary = DarkSecondary, onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer, onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary, onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer, onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground, onBackground = DarkOnBackground,
    surface = DarkSurface, onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant, onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkPrimary,
    inverseSurface = Color(0xFFE5E8ED), inverseOnSurface = Color(0xFF22272E),
    error = DarkMarkMust, onError = Color(0xFF4A0E08),
    errorContainer = DarkMarkMustSoft, onErrorContainer = Color(0xFFFFDAD5),
    outline = DarkOutline, outlineVariant = DarkOutlineVariant,
    scrim = Color.Black,
    surfaceBright = DarkSurfaceHighest, surfaceDim = DarkSurfaceLowest,
    surfaceContainerLowest = DarkSurfaceLowest, surfaceContainerLow = DarkSurfaceLow,
    surfaceContainer = DarkSurfaceMid, surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHighest
)

/** Correction tones shared by every review: must-fix, optional polish and sound work. */
@Immutable
data class MarkColors(
    val must: Color, val mustSoft: Color,
    val better: Color, val betterSoft: Color,
    val good: Color, val goodSoft: Color
)

private val LightMarks = MarkColors(LightMarkMust, LightMarkMustSoft, LightMarkBetter, LightMarkBetterSoft, LightMarkGood, LightMarkGoodSoft)
private val DarkMarks = MarkColors(DarkMarkMust, DarkMarkMustSoft, DarkMarkBetter, DarkMarkBetterSoft, DarkMarkGood, DarkMarkGoodSoft)

val LocalMarks = staticCompositionLocalOf { LightMarks }

@Composable
fun CET6Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalMarks provides if (darkTheme) DarkMarks else LightMarks) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}
