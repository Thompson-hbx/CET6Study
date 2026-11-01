package com.example.cet6.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.cet6.diagnostics.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun ColumnScope.SyncSettings(model: DesktopModel) {
    var address by remember(model.address) { mutableStateOf(model.address) }
    var secret by remember { mutableStateOf("") }
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(44.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
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
                OutlinedTextField(
                    address,
                    { address = it },
                    label = { Text("HTTPS 服务器地址") },
                    placeholder = { Text("https://你的服务器地址") },
                    singleLine = true,
                    enabled = !model.busy,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    secret,
                    { secret = it },
                    label = { Text("配对密钥") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !model.busy,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
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
            Text(
                "两端修改冲突时，会先保存副本，再由你选择保留的版本。重新保存连接会重新配对，旧同步历史仍保留。",
                style = MaterialTheme.typography.bodySmall,
                color = DeskColors.secondary,
            )
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

private fun conflictPreview(value: String): String = JSONObject(value).let { row ->
    val labels = linkedMapOf(
        "title" to "标题", "material" to "材料", "answer" to "作答", "feedback" to "批改", "content" to "内容",
        "errorReason" to "错因", "savedSentences" to "收藏", "practiceGoal" to "训练目标", "state" to "采纳状态",
    )
    labels.mapNotNull { (key, label) -> (row.opt(key) as? String)?.takeIf { it.isNotBlank() }?.let { "$label\n$it" } }
        .joinToString("\n\n").ifBlank { row.toString(2) }
}

@Composable
fun ColumnScope.DiagnosticsPage(reporter: CrashReporter) {
    val clipboard = LocalClipboardManager.current
    var report by remember(reporter) { mutableStateOf<String?>(null) }
    LaunchedEffect(reporter) {
        report = withContext(Dispatchers.IO) {
            try {
                reporter.read() ?: "本机暂无崩溃记录。"
            } catch (_: java.io.IOException) {
                "崩溃记录读取失败，学习数据不受影响。"
            } catch (_: SecurityException) {
                "无权读取崩溃记录，学习数据不受影响。"
            } catch (_: IllegalArgumentException) {
                "崩溃记录格式异常，已停止读取。"
            }
        }
    }
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(44.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("崩溃诊断", style = MaterialTheme.typography.headlineLarge)
        Text("只在本机保留最近一次未捕获异常的类型、代码位置、时间和版本。")
        Text("不记录异常消息、作答、登录凭证或文件路径，不自动上传。原生崩溃、无响应和系统结束进程不在记录范围内。")
        DeskButton("复制诊断记录", enabled = report != null) { report?.let { clipboard.setText(AnnotatedString(it)) } }
        SelectionContainer { Text(report ?: "正在读取…", style = MaterialTheme.typography.bodySmall) }
    }
}
