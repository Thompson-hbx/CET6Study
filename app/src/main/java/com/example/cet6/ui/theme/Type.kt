package com.example.cet6.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.cet6.R

// Type scale: large title 34, section 20, body 17, footnote 13. Chinese UI uses the system sans serif (Noto Sans
// CJK on Android); English the learner reads or writes, and the big counts, use the bundled Source Serif.
val Typography = Typography(
    // Large Title
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
    ),
    // Title 1
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    // Title 2
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    // Title 3
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
    ),
    // Headline
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    // Subheadline, emphasised
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    // Body; extra leading for Chinese
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 26.sp,
    ),
    // Subheadline / Callout
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    // Footnote
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    // Button text
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    // Caption 1
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // Caption 2 / tab bar
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 13.sp,
    ),
)

/** Bundled rather than downloadable: many phones here lack the Play services font provider. OFL 1.1, see assets/licenses. */
val SourceSerif = FontFamily(
    Font(R.font.source_serif4_regular, FontWeight.Normal),
    Font(R.font.source_serif4_semibold, FontWeight.SemiBold),
)

/** English the learner reads or writes: passages, essays, translations. */
val PassageText = TextStyle(
    fontFamily = SourceSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 17.sp,
    lineHeight = 28.sp,
)

/** Large counts set like print: the signature numerals on 今日 and 复盘. */
val NumeralText = TextStyle(
    fontFamily = SourceSerif,
    fontWeight = FontWeight.SemiBold,
    fontSize = 34.sp,
    lineHeight = 40.sp,
    fontFeatureSettings = "lnum, tnum",
)

/** Chinese source text in translation tasks; a little more leading for dense characters. */
val SourceText = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 17.sp,
    lineHeight = 30.sp,
    letterSpacing = 0.2.sp,
)
