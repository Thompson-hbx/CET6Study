package com.example.cet6.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 朱批: ink on paper, vermilion for corrections. Primary is ink (filled buttons, selection), never the brand red,
// so red keeps meaning "a mark worth your attention". White sheets sit on a warm paper ground; dark mode inverts
// to paper-coloured ink on near-black. Text colours hold 4.5:1 on both the ground and the sheets.
// Every Material role is set explicitly so no library default tint leaks in.
val LightPrimary = Color(0xFF16181D)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFECEAE4)
val LightOnPrimaryContainer = Color(0xFF16181D)

val LightSecondary = Color(0xFF3A3D44)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFECEAE4)
val LightOnSecondaryContainer = Color(0xFF16181D)

val LightTertiary = Color(0xFFC0351E)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFCEBE7)
val LightOnTertiaryContainer = Color(0xFF5C1608)

val LightBackground = Color(0xFFF5F4F1)
val LightOnBackground = Color(0xFF16181D)
val LightSurface = Color(0xFFF5F4F1)
val LightOnSurface = Color(0xFF16181D)
val LightSurfaceVariant = Color(0xFFECEAE4)
val LightOnSurfaceVariant = Color(0xFF5F626A)
val LightOutline = Color(0xFF8A8D94)
val LightOutlineVariant = Color(0xFFDEDBD3)
val LightSurfaceLowest = Color(0xFFFFFFFF)
val LightSurfaceLow = Color(0xFFFFFFFF)
val LightSurfaceMid = Color(0xFFF0EEE9)
val LightSurfaceHigh = Color(0xFFECEAE4)
val LightSurfaceHighest = Color(0xFFDEDBD3)

val DarkPrimary = Color(0xFFF5F1E8)
val DarkOnPrimary = Color(0xFF16181D)
val DarkPrimaryContainer = Color(0xFF2E3036)
val DarkOnPrimaryContainer = Color(0xFFF5F1E8)

val DarkSecondary = Color(0xFFC9C6BE)
val DarkOnSecondary = Color(0xFF16181D)
val DarkSecondaryContainer = Color(0xFF2E3036)
val DarkOnSecondaryContainer = Color(0xFFF5F1E8)

val DarkTertiary = Color(0xFFFF8A73)
val DarkOnTertiary = Color(0xFF3A0C04)
val DarkTertiaryContainer = Color(0xFF3D1C16)
val DarkOnTertiaryContainer = Color(0xFFFFD9D0)

val DarkBackground = Color(0xFF111214)
val DarkOnBackground = Color(0xFFF5F1E8)
val DarkSurface = Color(0xFF111214)
val DarkOnSurface = Color(0xFFF5F1E8)
val DarkSurfaceVariant = Color(0xFF2E3036)
val DarkOnSurfaceVariant = Color(0xFFA9AAB0)
val DarkOutline = Color(0xFF6E7077)
val DarkOutlineVariant = Color(0xFF34363C)
val DarkSurfaceLowest = Color(0xFF1B1D21)
val DarkSurfaceLow = Color(0xFF1B1D21)
val DarkSurfaceMid = Color(0xFF24262B)
val DarkSurfaceHigh = Color(0xFF2E3036)
val DarkSurfaceHighest = Color(0xFF3A3D44)

// Correction marks: vermilion for must-fix (solid line), ochre for optional polish (dashed line), indigo for
// sound work. The line style differs too, so the three never rely on hue alone. Each tone reads at 4.5:1 or
// more as text on the ground, on sheets and on its own soft background, in both themes.
val LightMarkMust = Color(0xFFC0351E)
val LightMarkMustSoft = Color(0xFFFCEBE7)
val LightMarkBetter = Color(0xFFA35F00)
val LightMarkBetterSoft = Color(0xFFFFF3E2)
val LightMarkGood = Color(0xFF1F5F99)
val LightMarkGoodSoft = Color(0xFFE6EEF6)
val DarkMarkMust = Color(0xFFFF8A73)
val DarkMarkMustSoft = Color(0xFF3D1C16)
val DarkMarkBetter = Color(0xFFF0B45A)
val DarkMarkBetterSoft = Color(0xFF392A14)
val DarkMarkGood = Color(0xFF8DBBEA)
val DarkMarkGoodSoft = Color(0xFF17283A)

/** The brand's vermilion as a fill: dots, badges and the hand-drawn ring. Use [MarkColors.must] for red text. */
val LightBrand = Color(0xFFE0452B)
val DarkBrand = Color(0xFFFF6A4F)

// Older screens use these accents; mapped to LocalMarks so light and dark themes both keep >= 4.5:1 contrast.
val AccentSuccess: Color
    @Composable get() = LocalMarks.current.good
val AccentSuccessContainer: Color
    @Composable get() = LocalMarks.current.goodSoft
val AccentWarning: Color
    @Composable get() = LocalMarks.current.better
val AccentWarningContainer: Color
    @Composable get() = LocalMarks.current.betterSoft
val AccentInfo = Color(0xFF1F5F99)
val AccentInfoContainer = Color(0xFFE6EEF6)
