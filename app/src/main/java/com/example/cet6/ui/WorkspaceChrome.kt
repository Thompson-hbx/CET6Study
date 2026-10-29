package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cet6.ui.components.*

/** Task context sits below the large title; compact navigation leaves room for the keyboard and reader. */
@Composable
fun WorkspaceTopBar(title: String, context: String, onBack: () -> Unit, detail: String? = null,
    compact: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    Column {
        ScreenTopBar(if (compact) title else "", subtitle = if (compact) context else null,
            onBack = onBack, showDivider = false, actions = actions)
        if (!compact) Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.weight(1f).semantics { heading() })
                if (detail != null) Note(detail)
            }
            Text(context, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One continuous document surface, with its word count and target kept beside the text. */
@Composable
fun WorkspaceDocument(title: String, text: String, readOnly: Boolean, words: Int,
    minWords: Int? = null, maxWords: Int? = null, minHeight: Int = 280, placeholder: String,
    onChange: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = CardMargin)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() })
                WordCount(words, minWords, maxWords, showTarget = false)
            }
            if (minWords != null) Note(if (maxWords != null) "目标 $minWords–$maxWords 词" else "至少 $minWords 词",
                Modifier.padding(top = 4.dp))
            Hairline(Modifier.padding(top = 16.dp, bottom = 18.dp))
            Editor(text, readOnly, placeholder, minHeight, onChange)
        }
    }
}
