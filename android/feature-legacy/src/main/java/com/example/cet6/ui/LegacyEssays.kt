package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.cet6.data.*
import com.example.cet6.ui.components.*

@Composable
fun LegacyEssays(vm: StudyViewModel) {
    if (vm.essays.isEmpty()) Note("没有早期版本保存的作文。")
    vm.essays.forEach { saved ->
        var expanded by remember(saved.id) { mutableStateOf(false) }
        ListRow(saved.topic.ifBlank { "历史作文" }, subtitle = shortTime(saved.createdAt), onClick = { expanded = !expanded })
        if (expanded) {
            SelectionContainer {
                Text("原文：\n${saved.content}\n\n批改：\n${saved.feedback}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
