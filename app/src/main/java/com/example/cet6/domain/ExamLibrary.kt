package com.example.cet6.domain

import android.content.Context
import android.net.Uri
import com.example.cet6.data.ExamCodec
import com.example.cet6.data.ExamPaper
import com.example.cet6.data.ExamStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExamLibraryState(
    val papers: List<ExamPaper> = emptyList(),
    val preview: List<ExamPaper> = emptyList(),
    val status: String = "",
    val busy: Boolean = false,
    /** False until the local bank was read; exports and restores refuse to overwrite an unread bank. */
    val loaded: Boolean = false
)

/** The personal exam bank: loading, import preview and export. */
class ExamLibrary(
    private val context: Context,
    private val store: ExamStore,
    private val scope: CoroutineScope
) {
    val state = ServiceState(ExamLibraryState())
    private val current get() = state.value
    internal fun setStatus(text: String) { state.update { it.copy(status = text) } }
    internal fun setBusy(value: Boolean) { state.update { it.copy(busy = value) } }
    internal fun restored(papers: List<ExamPaper>) { state.update { it.copy(papers = papers, loaded = true) } }

    /** Reads the bank once at startup; after a restore the bundled content must not be merged in again. */
    internal suspend fun load(restorePending: Boolean) {
        try {
            val papers = withContext(Dispatchers.IO) { if (restorePending) store.load() else store.loadWithBundledContent() }
            state.update { it.copy(papers = papers, loaded = true) }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { setStatus("本地题库读取失败，已保留原文件；请先导出或联系维护者，不要覆盖导入。") }
    }

    private fun tryStart(): Boolean {
        var started = false
        state.update { if (it.busy || !it.loaded) it else { started = true; it.copy(busy = true) } }
        return started
    }

    fun preview(uri: Uri) {
        if (!tryStart()) return
        state.update { it.copy(preview = emptyList()) }
        scope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= ExamCodec.MAX_BYTES) { "内容包超过 4 MiB" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                        ?: error("无法读取文件")
                    require(bytes.size <= ExamCodec.MAX_BYTES) { "内容包超过 4 MiB" }
                    val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    ExamCodec.decode(text).also { store.merge(current.papers, it) }
                }
                state.update { it.copy(preview = preview, status = "预览 ${preview.size} 套；尚未写入。各专项按资料完整与校对情况开放。") }
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { setStatus("未导入：${e.message}")
            } catch (_: Exception) { setStatus("文件无法解析，请选择 UTF-8 CET6ExamPack v1 JSON；PDF / Word 请先使用本地转换工具。")
            } finally { setBusy(false) }
        }
    }

    fun confirmImport() {
        val incoming = current.preview
        if (incoming.isEmpty() || !tryStart()) return
        scope.launch {
            try {
                val merged = withContext(Dispatchers.IO) { store.merge(current.papers, incoming).also(store::save) }
                state.update { it.copy(papers = merged, preview = emptyList(),
                    status = "已保存 ${incoming.size} 套个人内容；重复导入不增加套卷，既有作答保留。") }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { setStatus("保存未完成，原题库保留，请重试。")
            } finally { setBusy(false) }
        }
    }

    fun export(uri: Uri) {
        if (!tryStart()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(ExamCodec.encode(current.papers).toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入")
                }
                setStatus("个人题库已导出；作答历史请另用学习数据备份。")
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { setStatus("题库导出失败，目标可能留下不完整文件，请重试。")
            } finally { setBusy(false) }
        }
    }
}
