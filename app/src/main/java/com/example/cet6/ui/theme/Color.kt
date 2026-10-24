package com.example.cet6.ui.theme

import androidx.compose.ui.graphics.Color

// Clear desk, ink-blue pen. Cards sit on a cool grey desk; every Material role is set explicitly so no
// library default tint leaks in. Text roles keep at least 4.5:1 against the desk, cards and chips.
val LightPrimary = Color(0xFF2A56C6)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFDCE5FF)
val LightOnPrimaryContainer = Color(0xFF0D2A6E)

val LightSecondary = Color(0xFF4C5A73)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE3E8F0)
val LightOnSecondaryContainer = Color(0xFF1F2A3D)

val LightTertiary = Color(0xFF8A4B00)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFDE9CC)
val LightOnTertiaryContainer = Color(0xFF4A2800)

val LightBackground = Color(0xFFF3F5F8)
val LightOnBackground = Color(0xFF171A1F)
val LightSurface = Color(0xFFF3F5F8)
val LightOnSurface = Color(0xFF171A1F)
val LightSurfaceVariant = Color(0xFFE7EBF0)
val LightOnSurfaceVariant = Color(0xFF525B68)
val LightOutline = Color(0xFF7D8693)
val LightOutlineVariant = Color(0xFFD8DDE4)
val LightSurfaceLowest = Color(0xFFFFFFFF)
val LightSurfaceLow = Color(0xFFFFFFFF)
val LightSurfaceMid = Color(0xFFEEF1F5)
val LightSurfaceHigh = Color(0xFFE7EBF0)
val LightSurfaceHighest = Color(0xFFE0E5EB)

val DarkPrimary = Color(0xFFA8C0FF)
val DarkOnPrimary = Color(0xFF0B2667)
val DarkPrimaryContainer = Color(0xFF243B7A)
val DarkOnPrimaryContainer = Color(0xFFDCE5FF)

val DarkSecondary = Color(0xFFB7C3D8)
val DarkOnSecondary = Color(0xFF1F2A3D)
val DarkSecondaryContainer = Color(0xFF2B3445)
val DarkOnSecondaryContainer = Color(0xFFDCE3EF)

val DarkTertiary = Color(0xFFF2BE6B)
val DarkOnTertiary = Color(0xFF452A00)
val DarkTertiaryContainer = Color(0xFF5A3B00)
val DarkOnTertiaryContainer = Color(0xFFFFDDB0)

val DarkBackground = Color(0xFF0F1217)
val DarkOnBackground = Color(0xFFE5E8ED)
val DarkSurface = Color(0xFF0F1217)
val DarkOnSurface = Color(0xFFE5E8ED)
val DarkSurfaceVariant = Color(0xFF232931)
val DarkOnSurfaceVariant = Color(0xFFA6AFBC)
val DarkOutline = Color(0xFF7A8494)
val DarkOutlineVariant = Color(0xFF2C333D)
val DarkSurfaceLowest = Color(0xFF0B0E12)
val DarkSurfaceLow = Color(0xFF171B21)
val DarkSurfaceMid = Color(0xFF1C2128)
val DarkSurfaceHigh = Color(0xFF232931)
val DarkSurfaceHighest = Color(0xFF2B323B)

// Correction marks: red pen for must-fix, amber for optional polish, green for sound work. Each tone
// reads at 4.5:1 or more as text on the desk, on cards and on its own soft background, in both themes.
val LightMarkMust = Color(0xFFBE3328)
val LightMarkMustSoft = Color(0xFFFDE8E5)
val LightMarkBetter = Color(0xFF965300)
val LightMarkBetterSoft = Color(0xFFFCF0DA)
val LightMarkGood = Color(0xFF1B7647)
val LightMarkGoodSoft = Color(0xFFE1F3E8)
val DarkMarkMust = Color(0xFFFF9488)
val DarkMarkMustSoft = Color(0xFF3D1E1B)
val DarkMarkBetter = Color(0xFFF0BE62)
val DarkMarkBetterSoft = Color(0xFF382C13)
val DarkMarkGood = Color(0xFF78CFA0)
val DarkMarkGoodSoft = Color(0xFF153020)

// Older screens use these fixed accents; kept in the same family as the marks above.
val AccentSuccess = LightMarkGood
val AccentSuccessContainer = LightMarkGoodSoft
val AccentWarning = LightMarkBetter
val AccentWarningContainer = LightMarkBetterSoft
val AccentInfo = Color(0xFF2A56C6)
val AccentInfoContainer = Color(0xFFDCE5FF)
