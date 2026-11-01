package com.example.cet6.data

import java.util.Locale

data class SharedReading(val title: String, val material: String) {
    val isLink: Boolean get() = Regex("https?://\\S+", RegexOption.IGNORE_CASE).matches(material)

    companion object {
        fun from(mime: String?, text: CharSequence?, subject: CharSequence? = null): SharedReading {
            require(mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) == "text/plain") { "目前仅支持分享纯文本" }
            require(text != null && text.length <= 60_000) { "分享文字为空或超过 60000 字符，请分段导入" }
            val material = text.toString().replace("\r\n", "\n").trim()
            require(material.isNotBlank()) { "分享内容为空，请分享文章正文" }
            val heading = subject?.take(1000)?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                .ifBlank { material.lineSequence().first().take(60) }.take(80)
            return SharedReading("分享导入 · $heading", material)
        }
    }
}
