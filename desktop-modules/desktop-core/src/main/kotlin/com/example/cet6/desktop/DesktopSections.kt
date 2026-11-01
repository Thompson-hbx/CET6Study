package com.example.cet6.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*

fun sectionName(key: String) = when (key) {
    "TODAY" -> "今日"
    "REVIEW" -> "复盘"
    "WRITING" -> "写作"
    "TRANSLATION" -> "翻译"
    "READING" -> "阅读"
    "WORDS" -> "生词"
    "DIAGNOSTICS" -> "崩溃诊断"
    else -> "设备同步"
}
