package com.example.cet6.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FilterOutputStream

internal data class RestoreIntent(val backup: CompleteBackup, val adoptPlans: Boolean)

data class CompleteRestoreResult(val summary: RestoreSummary, val papers: List<ExamPaper>?)

/** Persist the validated input before either store changes; replay merges are idempotent. */
class RestoreJournal(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "restore-pending-v1.bin"))
    fun hasPending(): Boolean = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    internal fun write(intent: RestoreIntent) {
        check(!hasPending()) { "请先完成上次恢复" }
        val output = file.startWrite()
        var finished = false
        try {
            // The ZIP codec closes its stream; AtomicFile must finish the original descriptor itself.
            val header = DataOutputStream(object : FilterOutputStream(output) {
                override fun close() { flush() }
            })
            header.writeInt(0x43365231) // C6R1
            header.writeInt((if (intent.adoptPlans) 1 else 0) or (if (intent.backup.papers != null) 2 else 0))
            CompleteBackupCodec.write(intent.backup.copy(papers = intent.backup.papers ?: emptyList()), header)
            file.finishWrite(output)
            finished = true
            check(read() == intent) { "恢复任务未能完整保存，尚未修改学习数据" }
        } catch (e: Throwable) {
            if (!finished) file.failWrite(output)
            throw e
        }
    }

    internal fun read(): RestoreIntent = DataInputStream(file.openRead().buffered()).use { input ->
        require(input.readInt() == 0x43365231) { "恢复任务格式无效，原文件已保留" }
        val flags = input.readInt()
        require(flags in 0..3) { "恢复任务选项无效，原文件已保留" }
        val backup = CompleteBackupCodec.read(input)
        require(backup.papers != null) { "恢复任务内容不完整" }
        RestoreIntent(if (flags and 2 != 0) backup else backup.copy(papers = null), flags and 1 != 0)
    }

    internal fun clear() {
        file.delete()
        check(!hasPending()) { "恢复内容已写入，完成标记未能保存，请重试" }
    }
}

/** A durable roll-forward operation, rather than a transaction spanning Room and AtomicFile. */
class CompleteRestoreRepository(
    private val backups: BackupRepository,
    private val exams: ExamStore,
    private val journal: RestoreJournal
) {
    private val lock = Mutex()

    suspend fun restore(backup: CompleteBackup, adoptPlans: Boolean): CompleteRestoreResult = lock.withLock {
        check(!journal.hasPending()) { "请先完成上次恢复" }
        BackupMerger.merge(backups.snapshot(), backup.study, adoptPlans)
        backup.papers?.let { exams.merge(exams.load(), it) }
        val intent = RestoreIntent(backup, adoptPlans)
        journal.write(intent)
        apply(intent)
    }

    suspend fun resume(): CompleteRestoreResult? = lock.withLock {
        if (journal.hasPending()) apply(journal.read()) else null
    }

    private suspend fun apply(intent: RestoreIntent): CompleteRestoreResult {
        val existing = intent.backup.papers?.let { exams.load() }
        val merged = intent.backup.papers?.let { exams.merge(requireNotNull(existing), it) }
        val summary = backups.restore(intent.backup.study, intent.adoptPlans)
        // Empty libraries and unchanged imports need no file; ExamCodec rejects empty content packs.
        if (merged != null && merged != existing) exams.save(merged)
        journal.clear()
        return CompleteRestoreResult(summary, merged)
    }
}
