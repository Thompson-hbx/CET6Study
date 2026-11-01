package com.example.cet6.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

/** Blur only the backdrop; labels and controls stay sharp. Older Android uses an opaque tint. */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun GlassSurface(state: HazeState, shape: Shape, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val background = MaterialTheme.colorScheme.background
    val style = remember(surface, background) {
        HazeStyle(
            backgroundColor = background,
            tint = HazeTint(surface.copy(alpha = 0.72f)),
            blurRadius = 28.dp,
            noiseFactor = 0.04f,
            fallbackTint = HazeTint(surface)
        )
    }
    Box(modifier.shadow(10.dp, shape, clip = false).clip(shape).hazeChild(state, style) {
        // Downsample only the blurred backdrop; foreground text and icons keep their native resolution.
        inputScale = HazeInputScale.Fixed(0.66f)
    }
        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape), content = content)
}
