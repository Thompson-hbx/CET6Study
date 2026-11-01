package com.example.cet6.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.example.cet6.diagnostics.CrashReporter
import kotlinx.coroutines.cancel
import java.awt.Dimension
import java.io.File

fun main(args: Array<String>) {
    require(args.isEmpty() || (args.size == 1 && args[0].startsWith("--data-dir="))) { "支持的参数：--data-dir=<本机数据目录>" }
    val directory = File(
        args.singleOrNull()?.substringAfter("--data-dir=") ?: System.getProperty("xiye.dataDir")
            ?: File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Xiye").absolutePath,
    )
    val diagnostics = CrashReporter(File(directory, "diagnostics"), "desktop-${DesktopStore::class.java.`package`.implementationVersion ?: "dev"}")
    diagnostics.install()
    desktopApplication(directory, diagnostics)
}

private fun desktopApplication(directory: File, diagnostics: CrashReporter) = application {
    val opened = remember { runCatching { DesktopStore(directory) } }
    val scope = rememberCoroutineScope()
    val model = remember { opened.getOrNull()?.let { DesktopModel(it, scope) } }
    DisposableEffect(Unit) {
        onDispose {
            scope.cancel()
            opened.getOrNull()?.close()
        }
    }
    // The data folder and package keep the old Xiye name so existing local records are found after the rename.
    Window(
        onCloseRequest = ::exitApplication,
        title = "朱批",
        icon = painterResource("brand/zhupi.svg"),
        state = rememberWindowState(width = 1440.dp, height = 960.dp),
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(1000, 680) }
        DesktopRoot(model, diagnostics, opened.exceptionOrNull()?.message)
    }
}
