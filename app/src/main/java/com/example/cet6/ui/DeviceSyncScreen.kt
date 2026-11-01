package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun DeviceSyncScreen(vm: StudyViewModel) {
    val state = vm.deviceSync
    var address by remember(state.address) { mutableStateOf(state.address) }
    var secret by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("手机与电脑接着学", style = MaterialTheme.typography.titleLarge)
        Text("两端填写相同服务器和配对密钥。同步学习记录、草稿、批改与追问；AI 凭证、原卷 PDF 和未作答题库单独保存在设备上。")
        OutlinedTextField(
            address,
            { address = it },
            label = { Text("HTTPS 服务器地址") },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            secret,
            { secret = it },
            label = { Text("配对密钥") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { if (vm.configureSync(address, secret)) secret = "" }, enabled = !state.busy && secret.isNotBlank()) { Text("加密保存并同步") }
        if (state.address.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(state.enabled, vm::enableDeviceSync, enabled = !state.busy)
                Text("自动同步（应用运行期间每 30 秒检查）")
            }
            OutlinedButton(vm::synchronizeDevices, enabled = !state.busy) { Text("立即同步") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.status)
        state.conflicts.forEach { conflict ->
            Card(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("两端修改冲突", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(conflictPreview("本机", conflict.local) + "\n\n" + conflictPreview("服务器", conflict.remote)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ vm.resolveSync(conflict, false) }, enabled = !state.busy) { Text("保留本机版本") }
                        OutlinedButton({ vm.resolveSync(conflict, true) }, enabled = !state.busy) { Text("使用服务器版本") }
                    }
                }
            }
        }
        Text("发生冲突时不会自动覆盖。选择前，两端版本会存入本机同步历史；服务器也保留每次成功上传的版本。重新保存连接会重新配对，旧同步历史仍保留。")
    }
}

private fun conflictPreview(label: String, text: String): String {
    val row = JSONObject(text)
    val title = row.optString("title", row.optString("word", row.optString("date", "学习记录")))
    val labels = linkedMapOf(
        "material" to "材料",
        "answer" to "作答",
        "feedback" to "批改",
        "content" to "内容",
        "errorReason" to "错因",
        "savedSentences" to "收藏",
        "practiceGoal" to "训练目标",
        "state" to "采纳状态",
    )
    val content = labels.mapNotNull { (key, name) -> (row.opt(key) as? String)?.takeIf { it.isNotBlank() }?.let { "$name\n$it" } }
        .joinToString("\n\n").ifBlank { row.toString(2) }
    return "$label · $title\n$content"
}
