package com.example.cet6.ui.theme

/** Stable preference keys are separate from company names shown in the UI. */
enum class UiStyle(val key: String, val label: String, val description: String) {
    APPLE("apple", "Apple", "圆角分组、分段切换和悬浮毛玻璃标签栏。支持浅色与深色外观。");

    companion object {
        fun fromKey(key: String?): UiStyle = entries.firstOrNull { it.key == key } ?: APPLE
    }
}
