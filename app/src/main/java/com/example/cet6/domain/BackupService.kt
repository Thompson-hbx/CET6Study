package com.example.cet6.domain

import android.content.Context
import android.net.Uri
import com.example.cet6.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupState(
    val busy: Boolean = false,
    val status: String = "",
    val preview: StudyBackup? = null,
    val paperPreview: List<ExamPaper>? = null,
    /** A restore journal exists on disk: the last restore has not finished merging. */
    val restorePending: Boolean = false,
    /** A restore is merging right now; screens show only progress meanwhile. */
    val recoveryRunning: Boolean = false
)

/**
 * Complete backup export, verified preview and resumable restore. While it runs it holds the
 * study and exam write slots and refuses to start next to an active practice request.
 */
class BackupService(
    private val context: Context,
    private val backups: BackupRepository,
    private val restores: CompleteRestoreRepository,
    private val journal: RestoreJournal,
    private val study: StudyPlanService,
    private val exams: ExamLibrary,
    private val scope: CoroutineScope,
    private val practiceBusy: () -> Boolean,
    private val flushDrafts: suspend () -> Boolean
) {
    val state = ServiceState(journal.hasPending().let { BackupState(restorePending = it, recoveryRunning = it) })
    private val current get() = state.value
    private fun setStatus(text: String) { state.update { it.copy(status = text) } }
    private fun clearPreview() { state.update { it.copy(preview = null, paperPreview = null) } }

    fun export(uri: Uri) {
        if (current.busy || exams.state.value.busy || practiceBusy()) return
        if (!exams.state.value.loaded) {
            setStatus("个人题库尚未读取成功，未导出完整备份；请先在题库管理核对读取错误，不要覆盖原文件。")
            return
        }
        state.update { it.copy(busy = true, status = "正在导出学习记录与个人题库……") }
        exams.setBusy(true)
        scope.launch {
            try {
                check(flushDrafts()) { "训练正在处理或草稿保存失败，请稍后重试" }
                val snapshot = backups.snapshot()
                val papers = exams.state.value.papers
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        CompleteBackupCodec.write(CompleteBackup(snapshot, papers), output)
                    } ?: error("无法写入所选位置")
                }
                setStatus("备份已导出：${snapshot.words.size} 词、${snapshot.reviews.size} 条学习日志、${snapshot.reading.size} 条旧阅读、${snapshot.essays.size} 篇旧作文、${snapshot.coach.size} 条训练、${snapshot.turns.size} 条对话、${papers.size} 套题库。未包含登录凭证或原卷 PDF。")
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { setStatus("未导出：${e.message}")
            } catch (_: Exception) { setStatus("备份写入失败，请换位置重试；所选位置可能留下不完整文件。本机学习数据未改变。")
            } finally { state.update { it.copy(busy = false) }; exams.setBusy(false) }
        }
    }

    fun preview(uri: Uri) {
        if (current.busy) return
        if (current.restorePending) {
            setStatus("上次恢复尚未完成，请先重试；本机已保存的内容仍可读取。")
            return
        }
        state.update { it.copy(busy = true, preview = null, paperPreview = null, status = "正在完整校验备份，尚未修改学习数据……") }
        scope.launch {
            try {
                val incoming = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(CompleteBackupCodec::read)
                        ?: error("无法读取所选文件")
                }
                state.update { it.copy(preview = incoming.study, paperPreview = incoming.papers,
                    status = if (incoming.papers == null) "旧版学习 JSON 校验通过；未包含个人题库，请另行导入题库。"
                        else "完整性校验通过：学习记录与个人题库均可恢复，请核对预览。") }
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { setStatus("未恢复：${e.message}")
            } catch (_: Exception) { setStatus("备份读取或格式校验失败，未修改本机数据。")
            } finally { state.update { it.copy(busy = false) } }
        }
    }

    fun cancelRestore() { if (!current.busy) { clearPreview(); setStatus("已关闭恢复预览。") } }

    private fun othersBusy() = current.busy || study.state.value.saving || exams.state.value.busy || practiceBusy()

    fun restore(adoptPlans: Boolean) {
        val incoming = current.preview ?: return
        if (othersBusy()) return
        if (current.restorePending) {
            setStatus("上次恢复尚未完成，请先重试。")
            return
        }
        if (current.paperPreview != null && !exams.state.value.loaded) {
            setStatus("个人题库尚未读取成功，未恢复任何内容；请先核对本机题库读取错误。")
            return
        }
        val backup = CompleteBackup(incoming, current.paperPreview)
        state.update { it.copy(busy = true) }
        scope.launch { performRestore(backup, adoptPlans) }
    }

    fun resume() {
        if (!current.restorePending || othersBusy()) return
        state.update { it.copy(busy = true) }
        scope.launch { performRestore(null, false) }
    }

    /** Continues a journaled restore at startup, before drafts are loaded, so there is nothing to flush. */
    internal suspend fun resumeAtStartup() = performRestore(null, false, flushFirst = false)

    private suspend fun performRestore(incoming: CompleteBackup?, adoptPlans: Boolean, flushFirst: Boolean = true) {
        state.update { it.copy(busy = true, recoveryRunning = true,
            status = if (incoming == null) "正在继续上次恢复……" else "正在合并恢复学习记录与题库……") }
        exams.setBusy(true)
        study.setSaving(true)
        var restoredStores = false
        try {
            if (flushFirst) check(flushDrafts()) { "训练正在处理或草稿保存失败，请稍后重试" }
            val restored = withContext(Dispatchers.IO) {
                if (incoming == null) restores.resume() else restores.restore(incoming, adoptPlans)
            }
            restoredStores = true
            restored?.papers?.let(exams::restored)
            clearPreview()
            study.reloadPlan()
            setStatus("恢复完成。${restored?.summary?.message().orEmpty()}已有词义、来源和学习历史均保留。")
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (journal.hasPending()) clearPreview()
            setStatus(if (restoredStores) "数据恢复已完成，页面刷新失败，请重新打开应用。"
            else if (journal.hasPending())
                "恢复尚未完成，本机已保留恢复任务；部分内容可能已写入。可继续读取离线记录，在此重试或重启后继续；冲突不会覆盖本机记录。"
            else if (e is IllegalArgumentException) "未恢复：${e.message}"
            else "恢复未能完成，请保留原备份后重试；未创建新的恢复任务。")
        } finally {
            state.update { it.copy(restorePending = journal.hasPending(), recoveryRunning = false, busy = false) }
            exams.setBusy(false)
            study.setSaving(false)
        }
    }
}
