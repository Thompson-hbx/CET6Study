package com.example.cet6.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cet6.ui.theme.*

@Composable
fun InteractiveStepPill(
    stepNumber: String,
    stepLabel: String,
    isCurrent: Boolean,
    isPassed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        selected = isCurrent,
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(8.dp),
        color = when {
            isCurrent -> MaterialTheme.colorScheme.primaryContainer
            isPassed -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
            else -> MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(if (isCurrent) 1.5.dp else 1.dp, when {
            isCurrent -> MaterialTheme.colorScheme.primary
            isPassed -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        })
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
        ) {
            Text(stepNumber, style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"))
            Text(stepLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (badge != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    isSuccess: Boolean = false,
    isWarning: Boolean = false
) {
    val (bgColor, textColor) = when {
        isSuccess -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        isWarning -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(textColor)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

/** One stage of a task, e.g. 作答 → 批改 → 修改; a stage that cannot be opened yet stays visible but disabled. */
data class WorkflowStep(val label: String, val done: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * Where the learner is in a task, replacing tabs that showed empty pages before submission. Each stage is
 * announced as a tab with its state so screen readers and the UI scripts can still find and select it.
 */
@Composable
fun WorkflowSteps(steps: List<WorkflowStep>, current: Int?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { i, step ->
            if (i > 0) HorizontalDivider(Modifier.weight(1f), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            val selected = i == current
            val tint = when {
                selected -> MaterialTheme.colorScheme.primary
                step.done -> LocalMarks.current.good
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(Modifier.clip(RoundedCornerShape(50))
                .selectable(selected = selected, enabled = step.enabled, role = Role.Tab, onClick = step.onClick)
                .semantics { stateDescription = when { selected -> "当前"; step.done -> "已完成"; !step.enabled -> "暂不可用"; else -> "" } }
                .heightIn(min = 44.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(22.dp).clip(CircleShape).then(when {
                    selected -> Modifier.background(MaterialTheme.colorScheme.primary)
                    step.done -> Modifier.background(LocalMarks.current.goodSoft)
                    else -> Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                }), contentAlignment = Alignment.Center) {
                    if (step.done && !selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                    else Text("${i + 1}", style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else tint)
                }
                Text(step.label, style = MaterialTheme.typography.labelLarge, color = if (step.enabled) tint else tint.copy(alpha = 0.6f),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
