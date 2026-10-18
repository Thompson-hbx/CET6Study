package com.example.cet6.ui

import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin

@Composable
fun MarkdownFeedback(text: String) {
    val context = LocalContext.current
    val renderer = remember(context) { Markwon.builder(context).usePlugin(TablePlugin.create(context)).build() }
    val rendered = remember(renderer, text) { renderer.toMarkdown(text) }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val size = MaterialTheme.typography.bodyLarge.fontSize.value
    var raw by rememberSaveable { mutableStateOf(false) }
    var copied by rememberSaveable(text) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = { raw = !raw },
            shape = MaterialTheme.shapes.small
        ) {
            Text(if (raw) "返回排版阅读" else "查看原始文本")
        }
        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(text)); copied = true },
            shape = MaterialTheme.shapes.small
        ) {
            Text(if (copied) "已复制完整反馈" else "复制完整反馈")
        }
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.padding(14.dp)) {
            if (raw) SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
            else AndroidView(factory = { ctx -> TextView(ctx).apply {
                setTextIsSelectable(true)
                setLineSpacing(0f, 1.35f)
            } }, modifier = Modifier.fillMaxWidth(), update = { view ->
                view.setTextColor(color)
                view.textSize = size
                renderer.setParsedMarkdown(view, rendered)
            })
        }
    }
}
