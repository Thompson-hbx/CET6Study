package com.example.cet6

import com.example.cet6.data.SharedReading
import org.junit.Assert.*
import org.junit.Test

class SharedReadingTest {
    @Test fun preservesArticleAndNormalizesSubject() {
        val reading = SharedReading.from("text/plain; charset=utf-8", "  First paragraph.\r\n第二段。  ", "  图书馆\n 阅读  ")
        assertEquals("First paragraph.\n第二段。", reading.material)
        assertEquals("分享导入 · 图书馆 阅读", reading.title)
        assertFalse(reading.isLink)
    }

    @Test fun rejectsUnsupportedOrEmptyPayloads() {
        listOf(null, "", " \n\t", "x".repeat(60_001)).forEach { text ->
            assertTrue(runCatching { SharedReading.from("text/plain", text) }.isFailure)
        }
        assertTrue(runCatching { SharedReading.from("text/html", "<p>article</p>") }.isFailure)
        assertTrue(runCatching { SharedReading.from(null, "article") }.isFailure)
    }

    @Test fun acceptsLinkWithoutReplacingItWithInventedContent() {
        val reading = SharedReading.from("TEXT/PLAIN", "https://example.com/article")
        assertTrue(reading.isLink)
        assertEquals("https://example.com/article", reading.material)
        assertTrue(reading.title.startsWith("分享导入 · "))
    }
}
