package com.example.cet6.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun WordContentLicenses() {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    OutlinedButton(onClick = { open = true }) { Text("词汇来源与开源许可") }
    if (open) {
        val text = remember {
            "内置词义和例句为项目原创；音标为 CMUdict 美式读音的宽式转写，包含不同读法，供学习参考，不是完整词典。发音由本机系统语音引擎提供。\n\n" +
                listOf("cmudict.txt", "fsrs.txt").joinToString("\n\n") { name ->
                    context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
                }
        }
        AlertDialog(onDismissRequest = { open = false }, title = { Text("来源与许可") },
            text = { Text(text, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } })
    }
}
