package com.example.cet6.ui

import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
    TextButton(onClick = { raw = !raw }) { Text(if (raw) "返回排版阅读" else "查看原始文本") }
    TextButton(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }) {
        Text(if (copied) "已复制完整反馈" else "复制完整反馈")
    }
    if (raw) SelectionContainer { Text(text) }
    else AndroidView(factory = { ctx -> TextView(ctx).apply {
        setTextIsSelectable(true)
        setLineSpacing(0f, 1.25f)
    } }, modifier = Modifier.fillMaxWidth(), update = { view ->
        view.setTextColor(color)
        view.textSize = size
        renderer.setParsedMarkdown(view, rendered)
    })
}
