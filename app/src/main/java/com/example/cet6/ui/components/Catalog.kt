package com.example.cet6.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cet6.ui.theme.LocalMarks

/**
 * A row of filter chips: the chosen one filled in ink, the rest outlined on a white sheet. Chips are radio
 * choices, so screen readers (and the UI scripts) see them as checked rather than selected.
 */
@Composable
fun TrackSegments(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val chip = RoundedCornerShape(50)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val chosen = i == selected
            Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(chip)
                .background(if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow)
                .then(if (chosen) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, chip))
                .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(i) })
                .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Top-level switch inside a tab, e.g. 写作 / 翻译 / 阅读: plain words, the current one bold with a vermilion
 * dot beneath it, like a mark in the margin. Like [TrackSegments] the words are radio choices, which the UI
 * scripts read as checked.
 */
@Composable
fun DotTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val chosen = i == selected
            Column(Modifier.clip(RoundedCornerShape(10.dp))
                .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(i) })
                .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (chosen) FontWeight.Black else FontWeight.Normal,
                    color = if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.size(6.dp).background(if (chosen) LocalMarks.current.brand else Color.Transparent, CircleShape))
            }
        }
    }
}
