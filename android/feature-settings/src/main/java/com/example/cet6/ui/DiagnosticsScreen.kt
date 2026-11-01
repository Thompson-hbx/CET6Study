package com.example.cet6.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.cet6.diagnostics.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DiagnosticsScreen(reporter: CrashReporter) {
    val clipboard = LocalClipboardManager.current
    var report by remember(reporter) { mutableStateOf<String?>(null) }
    // Only a real saved record can be copied; status lines such as "no record" are shown but not offered for copying.
    var saved by remember(reporter) { mutableStateOf<String?>(null) }
    LaunchedEffect(reporter) {
        val (text, record) = withContext(Dispatchers.IO) {
            try {
                reporter.read().let { (it ?: "本机暂无崩溃记录。") to it }
            } catch (_: java.io.IOException) {
                "崩溃记录读取失败，学习数据不受影响。" to null
            } catch (_: SecurityException) {
                "无权读取崩溃记录，学习数据不受影响。" to null
            } catch (_: IllegalArgumentException) {
                "崩溃记录格式异常，已停止读取。" to null
            }
        }
        report = text
        saved = record
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("只在本机保留最近一次未捕获异常的类型、代码位置、时间和版本。", style = MaterialTheme.typography.bodyMedium)
        Text(
            "不记录异常消息、作答、登录凭证或文件路径，不自动上传。原生崩溃、无响应和系统结束进程不在记录范围内。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { saved?.let { clipboard.setText(AnnotatedString(it)) } }, enabled = saved != null) {
            Text("复制诊断记录")
        }
        SelectionContainer(Modifier.fillMaxWidth()) { Text(report ?: "正在读取…", style = MaterialTheme.typography.bodySmall) }
    }
}
