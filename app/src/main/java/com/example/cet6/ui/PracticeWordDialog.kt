package com.example.cet6.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cet6.data.CoachPractice
import com.example.cet6.data.PracticeVocabulary
import com.example.cet6.ui.components.Note
import com.example.cet6.ui.components.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun PracticeWordDialog(vm: StudyViewModel, entry: CoachPractice, onDismiss: () -> Unit) {
    var word by remember { mutableStateOf("") }
    var meaning by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val candidates = remember(entry.material, entry.answer, word) { PracticeVocabulary.candidates(entry, word) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("加入生词复习") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("来源：${entry.title.ifBlank { "当前练习" }}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Note("输入英文单词或短语，点选下方匹配到的原句（或手动粘贴原句），确认释义后加入复习。")
            OutlinedTextField(word, { word = it; message = "" }, label = { Text("单词或短语") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
            if (candidates.isNotEmpty()) {
                Note("匹配到的来源句（点一下填入）：", tone = Tone.Ink)
                candidates.forEach { sentence ->
                    val selected = quote.trim() == sentence
                    Surface(
                        onClick = { quote = sentence },
                        enabled = !saving,
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            sentence,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            OutlinedTextField(meaning, { meaning = it; message = "" }, label = { Text("确认的释义") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(quote, { quote = it; message = "" }, label = { Text("包含该词的来源句") }, enabled = !saving, minLines = 2, modifier = Modifier.fillMaxWidth())
            if (message.isNotBlank()) Note(message, tone = if (message.startsWith("已加入") || message.startsWith("该词已在")) Tone.Good else Tone.Must)
        }
    }, confirmButton = { TextButton(enabled = !saving && word.isNotBlank() && meaning.isNotBlank() && quote.isNotBlank(), onClick = {
        saving = true
        scope.launch {
            try { message = vm.savePracticeWord(entry, word, meaning, quote) }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { message = e.message.orEmpty() }
            catch (_: Exception) { message = "本机保存未完成，请稍后重试。" }
            finally { saving = false }
        }
    }) { Text("确认加入") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("完成") } })
}
