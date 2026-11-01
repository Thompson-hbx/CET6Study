package com.example.cet6.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.cet6.design.ZhupiTokens

// 朱批: ink on paper, vermilion for corrections. The values live in :design-tokens, shared with the Windows app;
// this file maps them onto Material roles. Primary is ink (filled buttons, selection), never the brand red, so
// red keeps meaning "a mark worth your attention". Every Material role is set explicitly so no library default
// tint leaks in.
private val L = ZhupiTokens.Light
private val D = ZhupiTokens.Dark

val LightPrimary = Color(L.ink)
val LightOnPrimary = Color(L.sheet)
val LightPrimaryContainer = Color(L.chip)
val LightOnPrimaryContainer = Color(L.text)

val LightSecondary = Color(0xFF3A3D44)
val LightOnSecondary = Color(L.sheet)
val LightSecondaryContainer = Color(L.chip)
val LightOnSecondaryContainer = Color(L.text)

val LightTertiary = Color(L.must)
val LightOnTertiary = Color(L.sheet)
val LightTertiaryContainer = Color(L.mustSoft)
val LightOnTertiaryContainer = Color(0xFF5C1608)

val LightBackground = Color(L.ground)
val LightOnBackground = Color(L.text)
val LightSurface = Color(L.ground)
val LightOnSurface = Color(L.text)
val LightSurfaceVariant = Color(L.chip)
val LightOnSurfaceVariant = Color(L.textSecondary)
val LightOutline = Color(L.textMuted)
val LightOutlineVariant = Color(L.line)
val LightSurfaceLowest = Color(L.sheet)
val LightSurfaceLow = Color(L.sheet)
val LightSurfaceMid = Color(L.raised)
val LightSurfaceHigh = Color(L.chip)
val LightSurfaceHighest = Color(L.line)

val DarkPrimary = Color(D.ink)
val DarkOnPrimary = Color(D.onInk)
val DarkPrimaryContainer = Color(D.chip)
val DarkOnPrimaryContainer = Color(D.text)

val DarkSecondary = Color(0xFFC9C6BE)
val DarkOnSecondary = Color(D.onInk)
val DarkSecondaryContainer = Color(D.chip)
val DarkOnSecondaryContainer = Color(D.text)

val DarkTertiary = Color(D.must)
val DarkOnTertiary = Color(0xFF3A0C04)
val DarkTertiaryContainer = Color(D.mustSoft)
val DarkOnTertiaryContainer = Color(0xFFFFD9D0)

val DarkBackground = Color(D.ground)
val DarkOnBackground = Color(D.text)
val DarkSurface = Color(D.ground)
val DarkOnSurface = Color(D.text)
val DarkSurfaceVariant = Color(D.chip)
val DarkOnSurfaceVariant = Color(D.textSecondary)
val DarkOutline = Color(D.textMuted)
val DarkOutlineVariant = Color(D.line)
val DarkSurfaceLowest = Color(D.sheet)
val DarkSurfaceLow = Color(D.sheet)
val DarkSurfaceMid = Color(D.raised)
val DarkSurfaceHigh = Color(D.chip)
val DarkSurfaceHighest = Color(0xFF3A3D44)

// Correction marks: vermilion for must-fix (solid line), ochre for optional polish (dashed line), indigo for
// sound work. Contrast is checked in :design-tokens' tests.
val LightMarkMust = Color(L.must)
val LightMarkMustSoft = Color(L.mustSoft)
val LightMarkBetter = Color(L.better)
val LightMarkBetterSoft = Color(L.betterSoft)
val LightMarkGood = Color(L.good)
val LightMarkGoodSoft = Color(L.goodSoft)
val DarkMarkMust = Color(D.must)
val DarkMarkMustSoft = Color(D.mustSoft)
val DarkMarkBetter = Color(D.better)
val DarkMarkBetterSoft = Color(D.betterSoft)
val DarkMarkGood = Color(D.good)
val DarkMarkGoodSoft = Color(D.goodSoft)

/** The brand's vermilion as a fill: dots, badges and the hand-drawn ring. Use [MarkColors.must] for red text. */
val LightBrand = Color(L.brand)
val DarkBrand = Color(D.brand)

// Older screens use these accents; mapped to LocalMarks so light and dark themes both keep >= 4.5:1 contrast.
val AccentSuccess: Color
    @Composable get() = LocalMarks.current.good
val AccentSuccessContainer: Color
    @Composable get() = LocalMarks.current.goodSoft
val AccentWarning: Color
    @Composable get() = LocalMarks.current.better
val AccentWarningContainer: Color
    @Composable get() = LocalMarks.current.betterSoft
val AccentInfo = Color(L.good)
val AccentInfoContainer = Color(L.goodSoft)
