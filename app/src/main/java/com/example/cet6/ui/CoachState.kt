package com.example.cet6.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.cet6.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps edits local; complete AI feedback is attached to the exact saved submission. */
class CoachState(
    private val repository: CoachRepository,
    private val scope: CoroutineScope,
    private val canRequest: () -> Boolean,
    private val model: () -> String,
    private val request: suspend (String, suspend (String) -> Unit) -> String
) {
    val entries = mutableStateMapOf<CoachKind, CoachPractice>()
    val messages = mutableStateMapOf<CoachKind, String>()
    var history by mutableStateOf<List<CoachPractice>>(emptyList())
        private set
    var working by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    var activeRequest by mutableStateOf<String?>(null)
        private set
    var partialEntryId by mutableStateOf<String?>(null)
        private set
    private val writes = mutableMapOf<CoachKind, Job>()
    private val readingReady = CompletableDeferred<Unit>()
    private val shareLock = Mutex()
    var shareStatus by mutableStateOf("")
        private set
    var importedReadingId by mutableStateOf<String?>(null)
        private set

    init {
        scope.launch {
            try { repository.history.collect { history = it } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { CoachKind.entries.forEach { messages[it] = "历史读取失败，请重启应用重试；不要清除数据。" } }
        }
        CoachKind.entries.forEach { kind ->
            scope.launch {
                try { entries[kind] = repository.draft(kind) ?: CoachPractice(kind = kind.name) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { messages[kind] = "草稿读取失败，请重启重试；未覆盖已有记录。" }
                finally { if (kind == CoachKind.READING) readingReady.complete(Unit) }
            }
        }
    }

    fun shareFailed(message: String) { shareStatus = message }
    fun consumeReadingImport(id: String) { if (importedReadingId == id) importedReadingId = null }

    fun importReading(input: SharedReading) = scope.launch {
        shareLock.withLock {
            readingReady.await()
            val current = entries[CoachKind.READING]
            if (current == null) {
                shareStatus = "读取草稿失败，分享内容未导入；请重启后重试，现有数据未改写。"
                return@withLock
            }
            val activate = !working
            if (activate) working = true
            try {
                if (activate) {
                    writes.remove(CoachKind.READING)?.cancelAndJoin()
                    if (!persist(CoachKind.READING, current)) {
                        shareStatus = "原草稿保存失败，分享内容未导入，请重试。"
                        return@withLock
                    }
                }
                val imported = CoachPractice(kind = CoachKind.READING.name, title = input.title, material = input.material)
                repository.save(imported)
                if (activate) {
                    entries[CoachKind.READING] = imported
                    partial = ""
                    importedReadingId = imported.id
                    messages[CoachKind.READING] = if (input.isLink) "分享链接已保存，请补充文章正文后再练习。" else "分享正文已保存为新草稿，请添加题目并独立作答。"
                }
                shareStatus = if (!activate) "分享文本已保存到阅读历史，当前请求继续处理。" else
                    if (input.isLink) "已保存分享链接，请补充正文。" else "已导入阅读草稿，原练习已保留。"
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { shareStatus = "分享保存失败，请重试；原练习已保留。"
            } finally { if (activate) working = false }
        }
    }

    fun edit(kind: CoachKind, transform: (CoachPractice) -> CoachPractice): Boolean {
        if (working) return false
        val current = entries[kind] ?: return false
        val next = transform(current).copy(updatedAt = System.currentTimeMillis())
        try { CoachPrompts.validate(next) }
        catch (e: IllegalArgumentException) { messages[kind] = e.message.orEmpty(); return false }
        entries[kind] = next
        messages[kind] = "正在保存……"
        writes[kind]?.cancel()
        writes[kind] = scope.launch {
            delay(350)
            persist(kind, next)
        }
        return true
    }

    fun addReadingQuestion(text: String): Boolean {
        val entry = entries[CoachKind.READING] ?: return false
        val question = text.trim()
        if (working || entry.submittedAt > 0 || question.isBlank()) return false
        if (entry.questions.trim() == question || entry.questions.split(Regex("\\n\\s*\\n")).any { it.trim() == question }) {
            messages[CoachKind.READING] = "这道题已添加，请在已添加题目中修改。"
            return false
        }
        return edit(CoachKind.READING) {
            it.copy(questions = listOf(it.questions, question).filter(String::isNotBlank).joinToString("\n\n"))
        }
    }

    private suspend fun persist(kind: CoachKind, entry: CoachPractice): Boolean = try {
        repository.save(entry)
        messages[kind] = "已保存到本机"
        true
    } catch (e: CancellationException) { throw e
    } catch (_: Exception) {
        messages[kind] = "本地保存失败，请点击保存重试；不要清除数据。"
        false
    }

    private fun action(kind: CoachKind, block: suspend () -> Unit) {
        if (working) return
        working = true
        scope.launch {
            try { writes.remove(kind)?.cancelAndJoin(); block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { messages[kind] = "操作失败，请重试；已有本地记录保留。" }
            finally { working = false }
        }
    }

    fun save(kind: CoachKind) = action(kind) { entries[kind]?.let { persist(kind, it) } }

    suspend fun flush(): Boolean {
        if (working) return false
        working = true
        return try {
            writes.values.toList().forEach { it.cancelAndJoin() }
            writes.clear()
            entries.toMap().all { (kind, entry) -> persist(kind, entry) }
        } finally { working = false }
    }

    fun open(kind: CoachKind, saved: CoachPractice? = null, retry: Boolean = false) = action(kind) {
        val current = entries[kind] ?: return@action
        if (!persist(kind, current)) return@action
        val latest = saved?.let { repository.get(it.id) ?: it }
        entries[kind] = when {
            latest == null -> CoachPractice(kind = kind.name)
            retry -> latest.retryCopy()
            else -> latest
        }
        persist(kind, entries.getValue(kind))
        partial = ""
    }

    fun generate(kind: CoachKind) = action(kind) {
        val source = entries[kind] ?: return@action
        val prompt = try { MicroPractice.prompt(source) }
        catch (e: IllegalArgumentException) { messages[kind] = e.message.orEmpty(); return@action }
        if (!persist(kind, source)) return@action
        if (!canRequest()) {
            messages[kind] = "错因已保存；生成新练习需要联网并选择模型。离线可先使用原题再练。"
            return@action
        }
        messages[kind] = "正在根据已确认的错因生成新练习……"
        try {
            val completed = request(prompt) { /* Generation output is not feedback for the source attempt. */ }
            val draft = MicroPractice.parse(completed, source)
            if (persist(kind, draft)) {
                entries[kind] = draft
                partial = ""
                messages[kind] = "AI 原创微练习已保存为新草稿，请独立作答；原练习和错因已保留。"
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            messages[kind] = "微练习未生成：${CoachFailures.describe(e)} 原练习和错因已保留。"
        }
    }

    fun submit(kind: CoachKind, explain: Boolean, followUp: String = "") = action(kind) {
        val current = entries[kind] ?: return@action
        val prompt = try { CoachPrompts.build(current) + if (followUp.isBlank()) "" else {
            require(followUp.length <= 4000) { "追问请控制在 4000 字以内" }
            require(current.feedback.isNotBlank()) { "请先完成一次解析" }
            "\n---此前 AI 反馈（供核对，可能有误）---\n${current.feedback.takeLast(60_000)}\n---用户追问---\n$followUp\n请结合原文回答，必要时纠正此前错误，不冒充官方答案。"
        } }
        catch (e: IllegalArgumentException) { messages[kind] = e.message.orEmpty(); return@action }
        val submitted = current.copy(submittedAt = current.submittedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis())
        if (!persist(kind, submitted)) return@action
        entries[kind] = submitted
        if (!explain) { messages[kind] = "作答已保存，可离线查看，联网后可请求 AI。"; return@action }
        if (!canRequest()) { messages[kind] = "作答已保存；请到连接页登录并选择模型，再重试 AI。"; return@action }
        val selectedModel = model()
        partial = ""
        partialEntryId = submitted.id
        activeRequest = submitted.id
        messages[kind] = "AI 处理中；网络中断仍保留作答。"
        try {
            val completed = request(prompt) { partial += it }
            check(completed.isNotBlank()) { "AI 没有返回可用文本" }
            val feedback = if (followUp.isBlank()) completed else "${submitted.feedback}\n\n追问：$followUp\n\n$completed"
            val result = submitted.copy(feedback = feedback, model = selectedModel, updatedAt = System.currentTimeMillis())
            if (persist(kind, result)) {
                entries[kind] = result
                partial = ""
                messages[kind] = "完整 AI 反馈已保存；请核对证据和错因。"
            } else {
                messages[kind] = "AI 已完成但反馈未保存，请重试；下方文本暂留内存。"
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            messages[kind] = "AI 未完成：${CoachFailures.describe(e)} 作答及此前完整反馈已保留；临时文本不算完整解析。"
        } finally { activeRequest = null }
    }
}
