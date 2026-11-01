package com.example.cet6.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp
import java.awt.GraphicsEnvironment
import java.io.File

/** 朱批 palette, shared with the phone: ink on paper, vermilion only for corrections and brand marks. */
internal object DeskColors {
    val ink = Color(0xFF16181D)
    val secondary = Color(0xFF5F626A)
    val muted = Color(0xFF8A8D94)
    val line = Color(0xFFE4E1DA)
    val canvas = Color(0xFFF5F4F1)
    val sidebar = Color(0xFFEDEBE6)
    val hover = Color(0xFFE4E1DA)
    val sheet = Color(0xFFFFFFFF)
    val chip = Color(0xFFECEAE4)
    val brand = Color(0xFFE0452B)
    val must = Color(0xFFC0351E)
    val mustSoft = Color(0xFFFCEBE7)
    val better = Color(0xFFA35F00)
    val betterSoft = Color(0xFFFFF3E2)
    val good = Color(0xFF1F5F99)
    val danger = must
}

/** English the learner reads or writes, set like print; the same Source Serif the phone bundles. */
internal val SerifFamily = FontFamily(
    Font("source_serif4_regular.ttf", FontWeight.Normal),
    Font("source_serif4_semibold.ttf", FontWeight.SemiBold)
)

@Composable
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal fun DesktopTheme(content: @Composable () -> Unit) {
    val family = remember {
        val windowsFonts = System.getenv("WINDIR")?.let { File(it, "Fonts") }
        val regular = windowsFonts?.resolve("msyh.ttc")
        val bold = windowsFonts?.resolve("msyhbd.ttc")
        val installed = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        // Use an installed CJK sans serif; no system fonts are redistributed with the Windows app.
        val name = listOf("Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", "Segoe UI")
            .firstOrNull { it in installed }
        if (regular?.isFile == true && bold?.isFile == true) {
            // Explicit font files prevent Skia's normal-weight CJK fallback to a serif face.
            FontFamily(Font(regular, FontWeight.Normal), Font(bold, FontWeight.Bold))
        } else name?.let { FontFamily(it) } ?: FontFamily.SansSerif
    }
    fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
        color = DeskColors.ink, letterSpacing = 0.sp)
    // Desktop reading distance: body 15, titles stepping up to a 30 large title, as on the phone's scale.
    MaterialTheme(
        colorScheme = lightColorScheme(primary = DeskColors.ink, onPrimary = Color.White,
            background = DeskColors.canvas, surface = DeskColors.sheet, onSurface = DeskColors.ink,
            onSurfaceVariant = DeskColors.secondary, outline = DeskColors.line, outlineVariant = DeskColors.line,
            secondaryContainer = DeskColors.chip, surfaceContainer = DeskColors.canvas, error = DeskColors.must),
        typography = Typography(
            headlineLarge = style(30, 40, FontWeight.Bold), headlineMedium = style(26, 34, FontWeight.Bold),
            headlineSmall = style(22, 30, FontWeight.Bold), titleLarge = style(20, 28, FontWeight.Bold),
            titleMedium = style(17, 26, FontWeight.Bold), titleSmall = style(15, 22, FontWeight.Bold),
            bodyLarge = style(17, 30), bodyMedium = style(15, 24), bodySmall = style(13, 20),
            labelLarge = style(14, 20, FontWeight.Medium), labelMedium = style(13, 18), labelSmall = style(12, 16))) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium, content = content)
    }
}
