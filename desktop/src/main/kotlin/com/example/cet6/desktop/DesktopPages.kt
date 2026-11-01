package com.example.cet6.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import org.json.JSONObject

@Composable
internal fun ColumnScope.SyncSettings(model: DesktopModel) {
    var address by remember(model.address) { mutableStateOf(model.address) }
    var secret by remember { mutableStateOf("") }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(44.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("设备同步", style = MaterialTheme.typography.headlineLarge)
        Text("手机与电脑，接着学。", style = MaterialTheme.typography.titleMedium, color = DeskColors.secondary)
        Spacer(Modifier.height(8.dp))
        Surface(Modifier.fillMaxWidth(), color = DeskColors.sheet, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Devices, null, tint = DeskColors.ink)
                    Text("连接你的个人服务器", style = MaterialTheme.typography.titleMedium)
                }
                Text("在两台设备上填写相同的服务器地址与配对密钥。", color = DeskColors.secondary)
                OutlinedTextField(address, { address = it }, label = { Text("HTTPS 服务器地址") },
                    placeholder = { Text("https://你的服务器地址") }, singleLine = true, enabled = !model.busy,
                    shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text("配对密钥") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !model.busy,
                    shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth())
                Text("配对密钥由 Windows 用户加密存储保护，不进入学习备份。", style = MaterialTheme.typography.bodySmall, color = DeskColors.secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DeskButton("加密保存并同步", primary = true, enabled = !model.busy && secret.isNotBlank()) {
                        if (model.configure(address, secret)) secret = ""
                    }
                    if (model.address.isNotBlank()) DeskButton("立即同步", Icons.Outlined.Sync, enabled = !model.busy, onClick = model::synchronize)
                }
                if (model.address.isNotBlank()) {
                    HorizontalDivider(color = DeskColors.line)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自动同步", style = MaterialTheme.typography.titleSmall)
                            Text("应用运行期间，每 30 秒检查一次更新。", color = DeskColors.secondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(model.enabled, model::setAutoSync, enabled = !model.busy)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("同步哪些内容", style = MaterialTheme.typography.titleSmall)
            Text("草稿、作答、已有批改、追问和单词进度。未作答题库、原卷 PDF、AI 登录和 API Key 仍单独保存在设备上。", color = DeskColors.secondary)
            Text("两端修改冲突时，会先保存副本，再由你选择保留的版本。重新保存连接会重新配对，旧同步历史仍保留。",
                style = MaterialTheme.typography.bodySmall, color = DeskColors.secondary)
        }
        model.conflicts.forEach { conflict ->
            Surface(color = DeskColors.sheet, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("两端都有新修改", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("本机版本", style = MaterialTheme.typography.titleSmall)
                            SelectionContainer { Text(conflictPreview(conflict.local)) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("服务器版本", style = MaterialTheme.typography.titleSmall)
                            SelectionContainer { Text(conflictPreview(conflict.remote)) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DeskButton("保留本机版本", enabled = !model.busy) { model.resolve(conflict, false) }
                        DeskButton("使用服务器版本", enabled = !model.busy) { model.resolve(conflict, true) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.WordList(model: DesktopModel) {
    var search by remember { mutableStateOf("") }
    val words = remember(model.document, search) {
        model.document.records.filterKeys { it.startsWith("vocabulary:") }.values.map { JSONObject(it).getJSONObject("word") }
            .filter { it.getString("word").contains(search, true) || it.getString("meaning").contains(search, true) }.sortedBy { it.getString("word") }
    }
    Column(Modifier.weight(1f).fillMaxWidth().padding(44.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                Text("生词", style = MaterialTheme.typography.headlineLarge)
                Text("从练习中积累，再回到语境里。", color = DeskColors.secondary)
            }
            OutlinedTextField(search, { search = it }, placeholder = { Text("搜索单词或释义") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
                shape = RoundedCornerShape(9.dp), modifier = Modifier.width(280.dp))
        }
        Text("查看从手机同步的词条与进度，复习评分请在手机完成。", style = MaterialTheme.typography.bodySmall, color = DeskColors.secondary)
        LazyColumn(Modifier.fillMaxWidth()) {
            if (words.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 80.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Article, null, Modifier.size(44.dp), tint = DeskColors.muted)
                    Text(if (search.isBlank()) "还没有同步的生词" else "没有找到这个词", style = MaterialTheme.typography.titleMedium)
                    Text("在手机练习中收藏生词，再同步到这台电脑。", color = DeskColors.secondary)
                }
            }
            items(words, key = { it.getString("word") }) { word ->
                Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(word.getString("word"), style = MaterialTheme.typography.titleLarge)
                    Text(word.getString("meaning"))
                    if (word.getString("example").isNotBlank()) SelectionContainer { Text(word.getString("example"), style = MaterialTheme.typography.bodyMedium) }
                    Text("${word.getInt("repetitions")} 次复习 · ${word.getString("source")}", style = MaterialTheme.typography.bodySmall, color = DeskColors.secondary)
                }
                HorizontalDivider(color = DeskColors.line)
            }
        }
    }
}

private fun conflictPreview(value: String): String = JSONObject(value).let { row ->
    val labels = linkedMapOf("title" to "标题", "material" to "材料", "answer" to "作答", "feedback" to "批改", "content" to "内容",
        "errorReason" to "错因", "savedSentences" to "收藏", "practiceGoal" to "训练目标", "state" to "采纳状态")
    labels.mapNotNull { (key, label) -> (row.opt(key) as? String)?.takeIf { it.isNotBlank() }?.let { "$label\n$it" } }
        .joinToString("\n\n").ifBlank { row.toString(2) }
}
