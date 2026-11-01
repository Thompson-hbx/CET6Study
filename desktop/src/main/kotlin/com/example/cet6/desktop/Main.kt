package com.example.cet6.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.cancel
import java.awt.Dimension
import java.io.File

fun main(args: Array<String>) = application {
    require(args.isEmpty() || (args.size == 1 && args[0].startsWith("--data-dir="))) { "支持的参数：--data-dir=<本机数据目录>" }
    val directory = remember { File(args.singleOrNull()?.substringAfter("--data-dir=") ?: System.getProperty("xiye.dataDir")
        ?: File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Xiye").absolutePath) }
    val opened = remember { runCatching { DesktopStore(directory) } }
    val scope = rememberCoroutineScope()
    val model = remember { opened.getOrNull()?.let { DesktopModel(it, scope) } }
    DisposableEffect(Unit) { onDispose { scope.cancel(); opened.getOrNull()?.close() } }
    // The data folder and package keep the old Xiye name so existing local records are found after the rename.
    Window(onCloseRequest = ::exitApplication, title = "朱批",
        icon = painterResource("brand/zhupi.svg"),
        state = rememberWindowState(width = 1440.dp, height = 960.dp)) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(1000, 680) }
        DesktopTheme {
            Surface(Modifier.fillMaxSize(), color = DeskColors.canvas) {
                if (model == null) Column(Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("本机学习数据暂时无法打开", style = MaterialTheme.typography.headlineSmall)
                    Text(opened.exceptionOrNull()?.message ?: "请检查数据目录和正在运行的朱批窗口")
                    Text("原文件已保留，请勿清空数据目录。")
                } else DesktopWorkspace(model)
            }
        }
    }
}
