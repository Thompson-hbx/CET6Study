package com.example.cet6.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The phone's tab icons, drawn from the same paths (app `ui/components/BrandIcons.kt`) so both platforms share one
 * icon set. The [accent] stroke turns vermilion on the current section; icons are drawn with an unspecified tint.
 */
internal object DeskIcons {
    fun today(ink: Color, accent: Color) = icon("today") {
        stroke(ink, "M3.5,12a8.5,8.5 0 1,0 17,0a8.5,8.5 0 1,0 -17,0")
        fill(accent, "M9.4,12a2.6,2.6 0 1,0 5.2,0a2.6,2.6 0 1,0 -5.2,0")
    }

    fun practice(ink: Color, accent: Color) = icon("practice") {
        stroke(ink, "M4.5,19.5l1.1,-4.4L15.4,5.3a2,2 0 0,1 2.9,0l0.4,0.4a2,2 0 0,1 0,2.9L8.9,18.4z")
        stroke(ink, "M13.6,7.1l3.3,3.3")
        stroke(accent, "M12.5,20h7")
    }

    fun review(ink: Color, accent: Color) = icon("review") {
        stroke(ink, "M9.2,3.5h8.1a2.2,2.2 0 0,1 2.2,2.2v10.6a2.2,2.2 0 0,1 -2.2,2.2h-8.1a2.2,2.2 0 0,1 -2.2,-2.2v-10.6a2.2,2.2 0 0,1 2.2,-2.2z")
        stroke(ink, "M4.5,7.5v11a2.5,2.5 0 0,0 2.5,2.5h8.5")
        stroke(accent, "M10.5,9h5.5")
    }

    fun me(ink: Color) = icon("me") {
        stroke(ink, "M8.2,8.5a3.8,3.8 0 1,0 7.6,0a3.8,3.8 0 1,0 -7.6,0")
        stroke(ink, "M4.8,20c1.2,-3.6 3.9,-5.4 7.2,-5.4s6,1.8 7.2,5.4")
    }

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.stroke(color: Color, d: String) = addPath(addPathNodes(d), stroke = SolidColor(color),
        strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)

    private fun ImageVector.Builder.fill(color: Color, d: String) = addPath(addPathNodes(d), fill = SolidColor(color))
}
