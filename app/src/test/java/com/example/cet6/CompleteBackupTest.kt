package com.example.cet6

import com.example.cet6.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CompleteBackupTest {
    private val empty = StudyBackup(1000, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    private val paper = ExamPaper("sample-cet6-2026-06-13-1", "2026-06-13", "1", sourceName = "Original test",
        sourceUrl = "local:test", licenseStatus = "original", sample = true)
    private fun encode(data: CompleteBackup) = ByteArrayOutputStream().also { CompleteBackupCodec.write(data, it) }.toByteArray()
    private fun decode(bytes: ByteArray) = CompleteBackupCodec.read(ByteArrayInputStream(bytes))
    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) }
        }
    }
    private fun zip(entries: Map<String, ByteArray>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun roundTripIncludesUnansweredPapersAndAnEmptyLibrary() {
        val data = CompleteBackup(empty, listOf(paper))
        assertEquals(data, decode(encode(data)))
        assertEquals(CompleteBackup(empty, emptyList()), decode(encode(CompleteBackup(empty, emptyList()))))
        assertEquals(CompleteBackup(empty, null), decode(BackupCodec.encode(empty).toByteArray()))
    }

    @Test fun legacyZipWithoutStudyVersionStillRestoresAndInvalidNewVersionsFail() {
        val original = entries(encode(CompleteBackup(empty, emptyList())))
        val manifest = JSONObject(String(original.getValue("manifest.json")))
        val legacy = JSONObject(manifest.toString()).apply { remove("studyVersion"); remove("databaseVersion") }
        assertEquals(empty, decode(zip(original + ("manifest.json" to legacy.toString().toByteArray()))).study)
        listOf(
            JSONObject(manifest.toString()).put("studyVersion", "6"),
            JSONObject(manifest.toString()).put("databaseVersion", 9.5),
            JSONObject(manifest.toString()).put("studyVersion", 99),
            JSONObject(manifest.toString()).apply { remove("databaseVersion") }
        ).forEach { changed ->
            assertTrue(runCatching { decode(zip(original + ("manifest.json" to changed.toString().toByteArray()))) }.isFailure)
        }
    }

    @Test fun rejectsMissingEntriesChecksumsUnknownPathsAndConflictingCounts() {
        val original = entries(encode(CompleteBackup(empty, listOf(paper))))
        val variants = listOf(
            original.filterKeys { it != "papers/0.json" },
            original + ("papers/0.json" to ExamCodec.encode(listOf(paper.copy(revision = 2))).toByteArray()),
            original + ("../unexpected.json" to "{}".toByteArray()),
            original + ("manifest.json" to JSONObject(String(original.getValue("manifest.json")))
                .put("paperCount", 2).toString().toByteArray()),
            original.filterKeys { it != "manifest.json" }
        )
        variants.forEach { assertTrue(runCatching { decode(zip(it)) }.isFailure) }
    }

    @Test fun rejectsCompressedOversizedEntriesBeforeJsonParsing() {
        val bomb = zip(mapOf("study/words-0.json" to ByteArray(2 * 1024 * 1024 + 1) { 32 }))
        assertTrue(runCatching { decode(bomb) }.isFailure)
    }

    @Test fun chunksLearningLargerThanTheLegacy32MiBLimit() {
        val practice = CoachPractice(id = "large-practice", updatedAt = 1000)
        val content = "a".repeat(CoachTurns.MAX_CONTENT)
        val data = empty.copy(coach = listOf(practice), turns = (0 until 570).map {
            CoachTurn(id = "turn-$it", practiceId = practice.id, anchor = Anchor.REVIEW, kind = TurnKind.CHAT,
                role = CoachTurn.ASSISTANT, content = content, createdAt = 1000L + it)
        })
        assertTrue(runCatching { BackupCodec.encode(data) }.isFailure)
        val encoded = encode(CompleteBackup(data, emptyList()))
        assertTrue(entries(encoded).keys.count { it.startsWith("study/turns-") } > 1)
        assertEquals(data, decode(encoded).study)
    }
}
