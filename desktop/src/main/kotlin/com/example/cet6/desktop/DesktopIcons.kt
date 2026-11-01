package com.example.cet6.desktop

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
 * The phone's section icons and logo ring, built from the same :design-tokens paths so both platforms draw one set.
 * The [accent] stroke turns vermilion on the current section; icons are drawn with an unspecified tint.
 */
internal object DeskIcons {
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

    /** The logo's brush ring; stretch it around a number to circle it. */
    fun ring(color: Color, width: Float = 30f) = ImageVector.Builder("ring", 145.dp, 124.dp, Ring.WIDTH, Ring.HEIGHT).apply {
        group(translationX = -Ring.LEFT, translationY = -Ring.TOP) {
            group(rotate = Ring.ROTATION, pivotX = Ring.PIVOT_X, pivotY = Ring.PIVOT_Y) {
                addPath(addPathNodes(Ring.PATH), stroke = SolidColor(color), strokeLineWidth = width, strokeLineCap = StrokeCap.Round)
            }
        }
    }.build()

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.stroke(color: Color, d: String) = addPath(
        addPathNodes(d),
        stroke = SolidColor(color),
        strokeLineWidth = Icons.STROKE_WIDTH,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )

    private fun ImageVector.Builder.fill(color: Color, d: String) = addPath(addPathNodes(d), fill = SolidColor(color))
}
