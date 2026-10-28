package com.example.cet6

import com.example.cet6.data.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Original archives shared by JVM/Android tests; no learner files or credentials. */
class CompleteManifestChecks {
    private val practice = CoachPractice(id = "manifest-original", title = "Original manifest check", updatedAt = 1000)
    private val study = StudyBackup(1_800_000_000_000L, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        listOf(practice), listOf(CoachTurn(id = "manifest-turn", practiceId = practice.id, anchor = Anchor.REVIEW,
            kind = TurnKind.CHAT, role = CoachTurn.ASSISTANT, content = "Original complete reply.", createdAt = 1000)))
    private val paper = ExamPaper("sample-cet6-2026-06-13-1", "2026-06-13", "1", sourceName = "Original manifest check",
        sourceUrl = "local:test", licenseStatus = "original", sample = true)

    private fun encode(value: CompleteBackup) = ByteArrayOutputStream().also { CompleteBackupCodec.write(value, it) }.toByteArray()
    private fun entries(bytes: ByteArray) = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) }
        }
    }
    private fun zip(entries: Map<String, ByteArray>) = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }.toByteArray()

    fun baseline() = CompleteBackup(study, listOf(paper))

    fun invalidArchives(): List<Pair<String, ByteArray>> {
        val original = entries(encode(baseline()))
        val meta = JSONObject(String(original.getValue("manifest.json"), Charsets.UTF_8))
        val targets = listOf("version", "createdAt", "paperCount", "entryBytes") +
            listOf("words", "reviews", "schedules", "plans", "reading", "essays", "coach", "turns").map { "count:$it" }
        val result = mutableListOf<Pair<String, ByteArray>>()
        for (target in targets) {
            val value = when {
                target == "entryBytes" -> meta.getJSONArray("entries").getJSONObject(0).getLong("bytes")
                target.startsWith("count:") -> meta.getJSONObject("counts").getLong(target.substringAfter(':'))
                else -> meta.getLong(target)
            }
            val variants = mutableListOf<Pair<String, Any>>("string" to value.toString(), "fraction" to value.toDouble() + 0.5)
            if (target != "createdAt") variants += "int-wrap" to value + 4_294_967_296L
            for ((kind, replacement) in variants) {
                val changed = JSONObject(meta.toString())
                when {
                    target == "entryBytes" -> changed.getJSONArray("entries").getJSONObject(0).put("bytes", replacement)
                    target.startsWith("count:") -> changed.getJSONObject("counts").put(target.substringAfter(':'), replacement)
                    else -> changed.put(target, replacement)
                }
                result += "$target/$kind" to zip(original + ("manifest.json" to changed.toString().toByteArray(Charsets.UTF_8)))
            }
        }
        check(result.size == 35)
        return result
    }

    fun verifyInvalid(): Int {
        val invalid = invalidArchives()
        for ((name, bytes) in invalid) check(runCatching { CompleteBackupCodec.read(ByteArrayInputStream(bytes)) }.isFailure) {
            "Coerced manifest metadata was accepted: $name"
        }
        return invalid.size
    }

    fun verifyValid() {
        for (papers in listOf(emptyList(), listOf(paper))) {
            val expected = CompleteBackup(study, papers)
            check(CompleteBackupCodec.read(ByteArrayInputStream(encode(expected))) == expected)
        }
        check(CompleteBackupCodec.read(ByteArrayInputStream(BackupCodec.encode(study).toByteArray(Charsets.UTF_8))) == CompleteBackup(study, null))
    }
}
