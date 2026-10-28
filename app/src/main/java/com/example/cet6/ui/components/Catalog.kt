package com.example.cet6.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A grey track holding equal segments, the chosen one lifted on a white tile. Segments are radio choices,
 * so screen readers (and the UI scripts) see them as checked rather than selected.
 */
@Composable
fun TrackSegments(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val chosen = i == selected
            Box(Modifier.weight(1f).heightIn(min = 40.dp)
                .then(if (chosen) Modifier.shadow(1.dp, RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(9.dp))
                    else Modifier)
                .clip(RoundedCornerShape(9.dp))
                .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Filter pills: the active one is filled with ink, the rest are outlined. */
@Composable
fun FilterPills(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val chosen = i == selected
            Surface(shape = RoundedCornerShape(50), color = if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.background,
                border = if (chosen) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(50))
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(i) })) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        color = if (chosen) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** One catalogue line: set label, what the task is about, and its status, all on a single row with no card. */
@Composable
fun CatalogRow(label: String, title: String, status: String?, tone: Tone, enabled: Boolean = true, statusDescription: String? = null,
    onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 56.dp)
        .padding(horizontal = PageGutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = labelWidth(listOf("第 3 套"), MaterialTheme.typography.bodyMedium)))
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        if (!status.isNullOrBlank()) Text(status, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = tone.color(), maxLines = 1,
            modifier = if (statusDescription != null) Modifier.semantics { contentDescription = statusDescription } else Modifier)
    }
}

/** Divider between [CatalogRow]s, aligned with their text. */
@Composable
fun CatalogDivider() = Hairline(Modifier.padding(end = PageGutter), start = PageGutter)
