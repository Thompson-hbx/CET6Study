package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
// import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.cet6.data.*
import com.example.cet6.ui.components.*

/** 练习: large title and a three-way skill switch; the catalogue below carries the content. */
@Composable
fun PracticeHeader(kind: CoachKind, onKind: (CoachKind) -> Unit) {
    val kinds = listOf(CoachKind.WRITING, CoachKind.TRANSLATION, CoachKind.READING)
    Column {
        TabTitle("练习")
        DotTabs(
            listOf("写作", "翻译", "阅读"),
            kinds.indexOf(kind),
            { onKind(kinds[it]) },
            Modifier.padding(horizontal = CardMargin - 10.dp, vertical = 4.dp),
        )
    }
}
