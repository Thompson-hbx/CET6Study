package com.example.cet6.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
// import androidx.compose.ui.semantics.heading
// import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.cet6.ui.theme.LocalMarks

// Grouped form parts for screens whose content column is already inset from the screen edge:
// a quiet header, white sheet rows separated by inset hairlines, and an explanatory footer.

/** One grouped section. Rows inside are separated with [GroupDivider]. */
@Composable
fun Group(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = CardPadding, end = CardPadding, top = 8.dp, bottom = 6.dp), /* .semantics { heading() } */
            )
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
        if (footer != null) GroupFooter(footer)
    }
}

/** Explanatory grey text under a group; also used on its own for status lines. */
@Composable
fun GroupFooter(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier.padding(start = CardPadding, end = CardPadding, top = 6.dp),
    )
}

@Composable
fun GroupDivider(start: androidx.compose.ui.unit.Dp = CardPadding) = Hairline(start = start)

private val RowPadding = Modifier.heightIn(min = 44.dp).padding(horizontal = CardPadding, vertical = 11.dp)

/** A read-only line: title on the left, its value in grey on the right. */
@Composable
fun ValueRow(title: String, value: String, valueTone: Tone = Tone.Muted) {
    Row(
        Modifier.fillMaxWidth().then(RowPadding)/* .semantics(mergeDescendants = true) {} */,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = valueTone.color(), modifier = Modifier.padding(start = 12.dp))
    }
}

/** A tappable line in the tint colour, or red when it removes something. */
@Composable
fun ActionRow(text: String, onClick: () -> Unit, enabled: Boolean = true, destructive: Boolean = false) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        destructive -> LocalMarks.current.must
        else -> LocalMarks.current.good
    }
    Box(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).then(RowPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** A line with a switch; the whole row toggles. */
@Composable
fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            /* .semantics { contentDescription = title } */
            .heightIn(min = 44.dp).padding(start = CardPadding, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest, uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedBorderColor = Color.Transparent,
                disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), disabledCheckedThumbColor = Color.White,
                disabledCheckedBorderColor = Color.Transparent,
                disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                disabledUncheckedThumbColor = Color.White, disabledUncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** One choice of a list where exactly one is picked; the picked one carries a tinted check mark. */
@Composable
fun CheckRow(title: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).then(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

/**
 * An inline text field: a short label on the left and borderless input on the right, or input alone when
 * [label] is null. The label also names the field for screen readers.
 */
@Composable
fun FieldRow(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    enabled: Boolean = true,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    minLines: Int = 1,
    accessibilityLabel: String = label ?: placeholder,
    maxLines: Int = Int.MAX_VALUE,
) {
    val style = MaterialTheme.typography.bodyLarge.copy(
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth().then(RowPadding), verticalAlignment = if (minLines > 1) Alignment.Top else Alignment.CenterVertically) {
        if (label != null) Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(min = 96.dp).padding(end = 12.dp))
        BasicTextField(
            value, onValueChange, enabled = enabled, textStyle = style, singleLine = minLines == 1, minLines = minLines, maxLines = maxLines,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
            modifier = Modifier.weight(1f)/* .semantics { contentDescription = accessibilityLabel } */,
            decorationBox = { field ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = style, color = MaterialTheme.colorScheme.outline)
                    field()
                }
            },
        )
    }
}

/**
 * Roles for the small tiles that lead rows. On paper the tiles stay ink-on-paper so a list reads calm; only
 * [Red], a row that needs attention, keeps the vermilion of a correction.
 */
object TileColors {
    val Blue = Color(0xFF007AFF)
    val Green = Color(0xFF34C759)
    val Orange = Color(0xFFFF9500)
    val Indigo = Color(0xFF5856D6)
    val Red = Color(0xFFFF3B30)
    val Teal = Color(0xFF30B0C7)
    val Gray = Color(0xFF8E8E93)
}

/** An ink glyph on a paper-coloured rounded square; vermilion for [TileColors.Red]. Decorative: the row text names it. */
@Composable
fun IconTile(icon: ImageVector, color: Color) {
    val alert = color == TileColors.Red
    val marks = LocalMarks.current
    Box(
        Modifier.size(TileSize).background(if (alert) marks.mustSoft else MaterialTheme.colorScheme.background, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (alert) marks.must else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(19.dp))
    }
}

private val TileSize = 34.dp

/** Divider start for rows that lead with an [IconTile], so the line begins under the text. */
val TiledRowDividerStart = CardPadding + TileSize + 12.dp
