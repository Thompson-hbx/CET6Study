package com.example.cet6.ui

import androidx.compose.runtime.Composable
import com.example.cet6.ui.components.CheckRow
import com.example.cet6.ui.components.Group
import com.example.cet6.ui.components.GroupDivider
import com.example.cet6.ui.theme.UiStyle

@Composable
fun UiStyleScreen(selected: UiStyle, onSelect: (UiStyle) -> Unit) {
    Group(header = "界面风格", footer = selected.description) {
        UiStyle.entries.forEachIndexed { index, style ->
            if (index > 0) GroupDivider()
            CheckRow(style.label, selected == style, onClick = { onSelect(style) })
        }
    }
}
