package com.example.cet6.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val Pill = RoundedCornerShape(50)

/** Pill button: ink-filled for the one primary action, white with a hairline otherwise, paper when toggled on. */
@Composable
internal fun DeskButton(
    text: String,
    icon: ImageVector? = null,
    primary: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    trailingIcon: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val background = when {
        primary -> if (enabled) DeskColors.ink else DeskColors.ink.copy(alpha = .35f)
        selected -> DeskColors.chip
        hovered && enabled -> DeskColors.canvas
        else -> DeskColors.sheet
    }
    val tint = when {
        primary -> Color.White
        !enabled -> DeskColors.muted
        else -> DeskColors.ink
    }
    Surface(
        color = background,
        shape = Pill,
        border = if (focused) BorderStroke(2.dp, DeskColors.brand) else if (primary || selected) null else BorderStroke(1.dp, DeskColors.line),
        modifier = modifier.clip(Pill).hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Row(
            Modifier.height(40.dp).padding(horizontal = if (primary) 20.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null && !trailingIcon) Icon(icon, null, Modifier.size(18.dp), tint = tint)
            Text(text, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
            if (icon != null && trailingIcon) Icon(icon, null, Modifier.size(18.dp), tint = tint)
        }
    }
}

/** Filter chips, the chosen one filled in ink; the same control as the phone's filters. */
@Composable
internal fun DeskTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            val chosen = selected == index
            Surface(
                color = if (chosen) DeskColors.ink else DeskColors.sheet,
                shape = Pill,
                border = if (chosen) null else BorderStroke(1.dp, DeskColors.line),
                modifier = Modifier.weight(1f).clip(Pill).clickable(role = Role.Tab) { onSelect(index) },
            ) {
                Box(Modifier.height(32.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                        color = if (chosen) Color.White else DeskColors.secondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
