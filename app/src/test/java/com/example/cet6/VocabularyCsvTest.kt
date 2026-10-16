package com.example.cet6

import com.example.cet6.data.VocabularyImport
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class VocabularyCsvTest {
    @Test fun handlesQuotedCommaNewlineAndEscapedQuote() {
        val text = "word,meaning,example\r\nallocate,\"分配，拨给\",\"A \"\"quote\"\".\nNext line.\"\r\n"
        val word = VocabularyImport.parse(text, "本人原创").single()
        assertEquals("分配，拨给", word.meaning)
        assertEquals("A \"quote\".\nNext line.", word.example)
    }
    @Test fun normalizesBomCaseAndWhitespace() {
        val word = VocabularyImport.parse("\uFEFFword,meaning,example\n Allocate , 分配 ,\n", " 原创 ").single()
        assertEquals("allocate", word.word)
        assertEquals("原创", word.source)
        assertEquals(0L, word.dueAt)
    }
    @Test fun rejectsInvalidWholeFiles() {
        val invalid = listOf(
            "word,meaning,example\nx,义,例\nX,义,例",
            "word,meaning,example\nx,义,\"unclosed",
            "word,meaning,example\nx,义,\"closed\"extra",
            "word,meaning,example\nx,,example",
            "word,meaning,example\nx,义,例,extra",
            "word,meaning,example\n123,义,例",
            "word,meaning\nx,义"
        )
        invalid.forEach { text -> assertTrue(text, runCatching { VocabularyImport.parse(text, "本人原创") }.isFailure) }
    }
    @Test fun rejectsInvalidEncodingAndExcessiveInput() {
        assertTrue(runCatching { VocabularyImport.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28))) }.isFailure)
        assertTrue(runCatching { VocabularyImport.read(ByteArrayInputStream(ByteArray(VocabularyImport.MAX_BYTES + 1))) }.isFailure)
    }
}
