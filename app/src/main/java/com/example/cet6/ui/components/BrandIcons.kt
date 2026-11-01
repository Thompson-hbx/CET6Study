package com.example.cet6.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp
import com.example.cet6.design.ZhupiTokens.Icons
import com.example.cet6.design.ZhupiTokens.Ring

/**
 * The 朱批 line icons for the four tabs, plus the hand-drawn ring of the logo, built from the paths in
 * :design-tokens. Each icon has one [accent] stroke (the today dot, the pen's line, the note on the page) that turns
 * vermilion when its tab is current, so the icons carry their colours themselves and are drawn with an unspecified tint.
 */
object BrandIcons {
    fun today(ink: Color, accent: Color) = icon("today") {
        stroke(ink, Icons.TODAY_RING)
        fill(accent, Icons.TODAY_DOT)
    }

    fun practice(ink: Color, accent: Color) = icon("practice") {
        stroke(ink, Icons.PRACTICE_PEN)
        stroke(ink, Icons.PRACTICE_NIB)
        stroke(accent, Icons.PRACTICE_ACCENT)
    }

    fun review(ink: Color, accent: Color) = icon("review") {
        stroke(ink, Icons.REVIEW_PAGE)
        stroke(ink, Icons.REVIEW_BACK)
        stroke(accent, Icons.REVIEW_ACCENT)
    }

    fun me(ink: Color) = icon("me") {
        stroke(ink, Icons.ME_HEAD)
        stroke(ink, Icons.ME_BODY)
    }

    /** The logo's brush ring, open where the stroke overlaps itself; draw it around a number to circle it. */
    fun ring(color: Color, width: Float = 34f) = ImageVector.Builder("ring", 145.dp, 124.dp, Ring.WIDTH, Ring.HEIGHT).apply {
        group(translationX = -Ring.LEFT, translationY = -Ring.TOP) {
            group(rotate = Ring.ROTATION, pivotX = Ring.PIVOT_X, pivotY = Ring.PIVOT_Y) {
                addPath(addPathNodes(Ring.PATH), stroke = SolidColor(color), strokeLineWidth = width, strokeLineCap = StrokeCap.Round)
            }
        }
    }.build()

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.stroke(color: Color, d: String) = addPath(addPathNodes(d), stroke = SolidColor(color),
        strokeLineWidth = Icons.STROKE_WIDTH, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)

    private fun ImageVector.Builder.fill(color: Color, d: String) = addPath(addPathNodes(d), fill = SolidColor(color))
}
