package com.example.cet6.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cet6.diagnostics.CrashReporter

@Composable
fun DesktopRoot(model: DesktopModel?, diagnostics: CrashReporter, errorMessage: String?) {
    DesktopTheme {
        Surface(Modifier.fillMaxSize(), color = DeskColors.canvas) {
            if (model == null) {
                Column(Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("本机学习数据暂时无法打开", style = MaterialTheme.typography.headlineSmall)
                    Text(errorMessage ?: "请检查数据目录和正在运行的朱批窗口")
                    Text("原文件已保留，请勿清空数据目录。")
                }
            } else {
                DesktopWorkspace(model, diagnostics)
            }
        }
    }
}
