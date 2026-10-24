package com.example.cet6

import com.example.cet6.data.*
import org.junit.Assert.*
import org.junit.Test

class PaperDocumentsTest {
    @Test fun sharedReadingLinkNeverOverridesAnImportedSetOrSample() {
        val march = ExamCatalog.papers().first { it.examDate == "2023-03-12" && it.setCode == "2" }
        assertEquals("cet6-2023-03-12-1", PaperDocuments.sharedReadingPaperId(march))
        assertEquals("cet6-2023-03-12-1", PaperDocuments.sharedReadingPaperId(march.copy(setCode = "3")))
        assertNull(PaperDocuments.sharedReadingPaperId(march.copy(sample = true)))
        assertNull(PaperDocuments.sharedReadingPaperId(march.copy(setCode = "1")))
        assertNull(PaperDocuments.sharedReadingPaperId(march.copy(passages = listOf(ExamPassage("1", "", "Imported", emptyList())))))
        assertNull(PaperDocuments.sharedReadingPaperId(march.copy(examDate = "2024-06-15")))
        assertEquals("待导入", march.status)
        val imported = march.copy(licenseStatus = "personal-use", reviewed = true,
            writingPrompt = "Original prompt", writingInstructions = "Write a paragraph", chineseText = "测试原文")
        assertEquals("阅读共用", imported.status)
        assertEquals("待校对", imported.copy(reviewed = false).status)
        assertEquals("待校对", imported.copy(chineseText = "").status)
    }
    @Test fun sharedMarchSourceAndRegularSetsResolveToTheirActualFiles() {
        val march = ExamCatalog.papers().first { it.examDate == "2023-03-12" && it.setCode == "2" }
        assertEquals(listOf("cet6_2023_03_2-3.pdf", "cet6_2023_03_2-3_ans.pdf"), PaperDocuments.filenames(march))
        assertEquals(PaperDocuments.filenames(march), PaperDocuments.filenames(march.copy(setCode = "3")))
        assertTrue(PaperDocuments.filenames(march.copy(sample = true)).isEmpty())
        ExamCatalog.papers().flatMap(PaperDocuments::filenames).forEach { assertTrue(PaperDocuments.validFilename(it)) }
    }

    @Test fun viewerRejectsPathsOutsideBundledDocumentNames() {
        listOf("../secret.pdf", "/secret.pdf", "cet6_2023_03_1.pdf/../../secret", "file://test", "").forEach {
            assertFalse(PaperDocuments.validFilename(it))
        }
    }
}
