package com.example.cet6.ui

fun saveLabel(message: String?): String = when (message) {
    null, "" -> "自动保存在本机"
    "已保存到本机" -> "已保存"
    "正在保存……" -> "保存中…"
    else -> message
}
