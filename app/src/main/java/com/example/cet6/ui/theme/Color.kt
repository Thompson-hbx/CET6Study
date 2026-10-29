package com.example.cet6.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// iOS-style grouped look: white cells on a light grey grouped background (black and near-black in dark mode),
// hairline separators and one blue tint. Hues follow the iOS system palette; where the stock value falls under
// 4.5:1 as text (systemBlue, the greens, secondary label) the darker accessible variant is used instead.
// Every Material role is set explicitly so no library default tint leaks in.
val LightPrimary = Color(0xFF0066CC)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE5F0FF)
val LightOnPrimaryContainer = Color(0xFF00336B)

val LightSecondary = Color(0xFF3A3A3C)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE5E5EA)
val LightOnSecondaryContainer = Color(0xFF1C1C1E)

val LightTertiary = Color(0xFFC93400)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFF1E3)
val LightOnTertiaryContainer = Color(0xFF5C1F00)

val LightBackground = Color(0xFFF2F2F7)
val LightOnBackground = Color(0xFF000000)
val LightSurface = Color(0xFFF2F2F7)
val LightOnSurface = Color(0xFF000000)
val LightSurfaceVariant = Color(0xFFE5E5EA)
val LightOnSurfaceVariant = Color(0xFF6C6C70)
val LightOutline = Color(0xFF8E8E93)
val LightOutlineVariant = Color(0xFFC6C6C8)
val LightSurfaceLowest = Color(0xFFFFFFFF)
val LightSurfaceLow = Color(0xFFFFFFFF)
val LightSurfaceMid = Color(0xFFEBEBF0)
val LightSurfaceHigh = Color(0xFFE5E5EA)
val LightSurfaceHighest = Color(0xFFD1D1D6)

val DarkPrimary = Color(0xFF409CFF)
val DarkOnPrimary = Color(0xFF001A3D)
val DarkPrimaryContainer = Color(0xFF10294D)
val DarkOnPrimaryContainer = Color(0xFFCFE3FF)

val DarkSecondary = Color(0xFFAEAEB2)
val DarkOnSecondary = Color(0xFF1C1C1E)
val DarkSecondaryContainer = Color(0xFF2C2C2E)
val DarkOnSecondaryContainer = Color(0xFFE5E5EA)

val DarkTertiary = Color(0xFFFFB340)
val DarkOnTertiary = Color(0xFF3A2000)
val DarkTertiaryContainer = Color(0xFF3A2A12)
val DarkOnTertiaryContainer = Color(0xFFFFDDB0)

val DarkBackground = Color(0xFF000000)
val DarkOnBackground = Color(0xFFFFFFFF)
val DarkSurface = Color(0xFF000000)
val DarkOnSurface = Color(0xFFFFFFFF)
val DarkSurfaceVariant = Color(0xFF2C2C2E)
val DarkOnSurfaceVariant = Color(0xFF98989F)
val DarkOutline = Color(0xFF636366)
val DarkOutlineVariant = Color(0xFF38383A)
val DarkSurfaceLowest = Color(0xFF1C1C1E)
val DarkSurfaceLow = Color(0xFF1C1C1E)
val DarkSurfaceMid = Color(0xFF2C2C2E)
val DarkSurfaceHigh = Color(0xFF3A3A3C)
val DarkSurfaceHighest = Color(0xFF48484A)

// Correction marks: red for must-fix, orange for optional polish, green for sound work (iOS accessible
// variants). Each tone reads at 4.5:1 or more as text on the grouped background, on cells and on its own
// soft background, in both themes.
val LightMarkMust = Color(0xFFD70015)
val LightMarkMustSoft = Color(0xFFFFEBEC)
val LightMarkBetter = Color(0xFFC93400)
val LightMarkBetterSoft = Color(0xFFFFF1E3)
val LightMarkGood = Color(0xFF1F7A36)
val LightMarkGoodSoft = Color(0xFFE8F6EC)
val DarkMarkMust = Color(0xFFFF6961)
val DarkMarkMustSoft = Color(0xFF3A1D1C)
val DarkMarkBetter = Color(0xFFFFB340)
val DarkMarkBetterSoft = Color(0xFF3A2A12)
val DarkMarkGood = Color(0xFF30DB5B)
val DarkMarkGoodSoft = Color(0xFF143222)

// Older screens use these accents; mapped to LocalMarks so light and dark themes both keep >= 4.5:1 contrast.
val AccentSuccess: Color
    @Composable get() = LocalMarks.current.good
val AccentSuccessContainer: Color
    @Composable get() = LocalMarks.current.goodSoft
val AccentWarning: Color
    @Composable get() = LocalMarks.current.better
val AccentWarningContainer: Color
    @Composable get() = LocalMarks.current.betterSoft
val AccentInfo = Color(0xFF0066CC)
val AccentInfoContainer = Color(0xFFE5F0FF)
