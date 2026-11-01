package com.example.cet6.ui.theme

/** Stable preference keys are separate from the names shown in the UI; an unknown saved key falls back to the default. */
enum class UiStyle(val key: String, val label: String, val description: String) {
    ZHUPI("zhupi", "朱批", "墨色文字、朱红批注，白色卷面放在纸色底上，配悬浮标签栏。支持浅色与深色外观。");

    companion object {
        fun fromKey(key: String?): UiStyle = entries.firstOrNull { it.key == key } ?: ZHUPI
    }
}
