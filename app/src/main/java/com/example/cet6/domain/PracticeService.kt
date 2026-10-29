package com.example.cet6.domain

import com.example.cet6.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One AI request: the prompt and a callback for each streamed delta; returns the complete reply. */
typealias AiRequest = suspend (String, suspend (String) -> Unit) -> String

data class PracticeState(
    val entries: Map<CoachKind, CoachPractice> = emptyMap(),
    val messages: Map<CoachKind, String> = emptyMap(),
    val history: List<CoachPractice> = emptyList(),
    val working: Boolean = false,
    val partial: String = "",
    val activeRequest: String? = null,
    val partialEntryId: String? = null,
    val partialReadingKey: String? = null,
    val shareStatus: String = "",
    val importedReadingId: String? = null
)

/** Keeps edits local; complete AI feedback is attached to the exact saved submission. */
class PracticeService(
    private val repository: CoachRepository,
    private val scope: CoroutineScope,
    private val canRequest: () -> Boolean,
    private val model: () -> String,
    private val startupReady: Deferred<Unit>?,
    private val reviewRequest: AiRequest?,
    private val request: AiRequest
) {
    val state = ServiceState(PracticeState())
    private val current get() = state.value
    private val writes = mutableMapOf<CoachKind, Job>()
    private val ready = CoachKind.entries.associateWith { CompletableDeferred<Unit>() }
    private val readingReady = ready.getValue(CoachKind.READING)
    private val shareLock = Mutex()
    private val saveLocks = CoachKind.entries.associateWith { Mutex() }

    private fun setEntry(kind: CoachKind, entry: CoachPractice) { state.update { it.copy(entries = it.entries + (kind to entry)) } }
    private fun message(kind: CoachKind, text: String) { state.update { it.copy(messages = it.messages + (kind to text)) } }
    private fun setPartial(text: String) { state.update { it.copy(partial = text) } }
    private fun setShareStatus(text: String) { state.update { it.copy(shareStatus = text) } }
    private fun setWorking(value: Boolean) { state.update { it.copy(working = value) } }

    /** Claims the single working slot; false when another action already holds it. */
    private fun tryStartWork(): Boolean {
        var started = false
        state.update { if (it.working) it else { started = true; it.copy(working = true) } }
        return started
    }

    private fun startStream(entryId: String, readingKey: String? = null) {
        state.update { it.copy(partial = "", partialEntryId = entryId, activeRequest = entryId,
            partialReadingKey = readingKey ?: it.partialReadingKey) }
    }
    private fun appendPartial(delta: String) { state.update { it.copy(partial = it.partial + delta) } }
    private fun endStream() { state.update { it.copy(activeRequest = null) } }

    init {
        scope.launch {
            try { repository.history.collect { history -> state.update { it.copy(history = history) } } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { CoachKind.entries.forEach { message(it, "历史读取失败，请重启应用重试；不要清除数据。") } }
        }
        CoachKind.entries.forEach { kind ->
            scope.launch {
                try {
                    // Pending recovery can add the latest draft; never cache the pre-recovery snapshot.
                    startupReady?.await()
                    setEntry(kind, repository.draft(kind) ?: CoachPractice(kind = kind.name))
                }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { message(kind, "草稿读取失败，请重启重试；未覆盖已有记录。") }
                finally { ready.getValue(kind).complete(Unit) }
            }
        }
    }

    fun shareFailed(message: String) = setShareStatus(message)
    fun consumeReadingImport(id: String) { state.update { if (it.importedReadingId == id) it.copy(importedReadingId = null) else it } }

    fun importReading(input: SharedReading) = scope.launch {
        shareLock.withLock {
            readingReady.await()
            val entry = current.entries[CoachKind.READING]
            if (entry == null) {
                setShareStatus("读取草稿失败，分享内容未导入；请重启后重试，现有数据未改写。")
                return@withLock
            }
            val activate = tryStartWork()
            try {
                if (activate) {
                    writes.remove(CoachKind.READING)?.cancelAndJoin()
                    if (!persist(CoachKind.READING, entry)) {
                        setShareStatus("原草稿保存失败，分享内容未导入，请重试。")
                        return@withLock
                    }
                }
                val imported = CoachPractice(kind = CoachKind.READING.name, title = input.title, material = input.material)
                repository.save(imported)
                if (activate) {
                    setEntry(CoachKind.READING, imported)
                    state.update { it.copy(partial = "", importedReadingId = imported.id) }
                    message(CoachKind.READING, if (input.isLink) "分享链接已保存，请补充文章正文后再练习。" else "分享正文已保存为新草稿，请添加题目并独立作答。")
                }
                setShareStatus(if (!activate) "分享文本已保存到阅读历史，当前请求继续处理。" else
                    if (input.isLink) "已保存分享链接，请补充正文。" else "已导入阅读草稿，原练习已保留。")
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { setShareStatus("分享保存失败，请重试；原练习已保留。")
            } finally { if (activate) setWorking(false) }
        }
    }

    fun edit(kind: CoachKind, transform: (CoachPractice) -> CoachPractice): Boolean {
        if (current.working) return false
        val entry = current.entries[kind] ?: return false
        val next = transform(entry).copy(updatedAt = System.currentTimeMillis())
        try { CoachPrompts.validate(next) }
        catch (e: IllegalArgumentException) { message(kind, e.message.orEmpty()); return false }
        setEntry(kind, next)
        message(kind, "正在保存……")
        writes[kind]?.cancel()
        writes[kind] = scope.launch {
            persist(kind, next)
        }
        return true
    }

    fun addReadingQuestion(text: String): Boolean {
        val entry = current.entries[CoachKind.READING] ?: return false
        val question = text.trim()
        if (current.working || entry.submittedAt > 0 || question.isBlank()) return false
        if (entry.questions.trim() == question || entry.questions.split(Regex("\\n\\s*\\n")).any { it.trim() == question }) {
            message(CoachKind.READING, "这道题已添加，请在已添加题目中修改。")
            return false
        }
        return edit(CoachKind.READING) {
            it.copy(questions = listOf(it.questions, question).filter(String::isNotBlank).joinToString("\n\n"))
        }
    }

    private suspend fun persist(kind: CoachKind, entry: CoachPractice): Boolean = saveLocks.getValue(kind).withLock {
        try {
            repository.save(entry)
            if (current.entries[kind] == entry) message(kind, "已保存到本机")
            true
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            message(kind, "本地保存失败，请点击保存重试；不要清除数据。")
            false
        }
    }

    private fun action(kind: CoachKind, block: suspend () -> Unit) {
        if (!tryStartWork()) return
        scope.launch {
            try { writes.remove(kind)?.cancelAndJoin(); block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message(kind, "操作失败，请重试；已有本地记录保留。") }
            finally { setWorking(false) }
        }
    }

    fun save(kind: CoachKind) = action(kind) { current.entries[kind]?.let { persist(kind, it) } }

    fun startExam(practice: CoachPractice, onReady: (CoachPractice) -> Unit = {}) {
        val kind = CoachKind.valueOf(practice.kind)
        action(kind) {
            ready.getValue(kind).await()
            val entry = current.entries[kind] ?: return@action
            if (!persist(kind, entry)) return@action
            // Reuse the latest saved draft for this exact content revision, never duplicate on repeated taps.
            val meta = ExamCodec.metadata(practice) ?: return@action
            val draft = (listOf(entry) + current.history).firstOrNull { it.kind == kind.name && it.submittedAt == 0L &&
                ExamCodec.itemId(it) == meta.optString("itemId") &&
                ExamCodec.metadata(it)?.optInt("revision") == meta.optInt("revision") }
            val next = draft ?: practice
            if (persist(kind, next)) { setEntry(kind, next); setPartial(""); onReady(next) }
        }
    }

    suspend fun flush(): Boolean {
        if (!tryStartWork()) return false
        return try {
            writes.values.toList().forEach { it.cancelAndJoin() }
            writes.clear()
            current.entries.map { (kind, entry) -> persist(kind, entry) }.all { it }
        } finally { setWorking(false) }
    }

    fun open(kind: CoachKind, saved: CoachPractice? = null, retry: Boolean = false, onReady: (CoachPractice) -> Unit = {}) = action(kind) {
        ready.getValue(kind).await()
        val entry = current.entries[kind] ?: return@action
        if (!persist(kind, entry)) return@action
        val latest = saved?.let { repository.get(it.id) ?: it }
        setEntry(kind, when {
            latest == null -> CoachPractice(kind = kind.name)
            retry -> latest.retryCopy()
            else -> latest
        })
        if (persist(kind, current.entries.getValue(kind))) onReady(current.entries.getValue(kind))
        setPartial("")
    }

    /** A new draft based on a submitted attempt, with the learner's chosen edits already applied. */
    fun revise(source: CoachPractice, answer: String, onReady: (CoachPractice) -> Unit = {}) {
        val kind = CoachKind.valueOf(source.kind)
        action(kind) {
            val entry = current.entries[kind] ?: return@action
            if (!persist(kind, entry)) return@action
            val draft = source.retryCopy().copy(answer = answer)
            if (persist(kind, draft)) {
                setEntry(kind, draft)
                setPartial("")
                message(kind, "已生成修改稿，原稿和批改都保留着。")
                onReady(draft)
            }
        }
    }

    /** Creates an empty practice of [kind] with the learner's own task text, e.g. a self-set essay prompt. */
    fun startCustom(kind: CoachKind, title: String, material: String, onReady: (CoachPractice) -> Unit = {}) {
        action(kind) {
            ready.getValue(kind).await()
            val entry = current.entries[kind] ?: return@action
            if (!persist(kind, entry)) return@action
            val draft = CoachPractice(kind = kind.name, title = title.trim(), material = material.trim())
            if (persist(kind, draft)) { setEntry(kind, draft); setPartial(""); onReady(draft) }
        }
    }

    fun generate(kind: CoachKind) = action(kind) {
        val source = current.entries[kind] ?: return@action
        val prompt = try { MicroPractice.prompt(source) }
        catch (e: IllegalArgumentException) { message(kind, e.message.orEmpty()); return@action }
        if (!persist(kind, source)) return@action
        if (!canRequest()) {
            message(kind, "错因已保存；生成新练习需要联网并选择模型。离线可先使用原题再练。")
            return@action
        }
        message(kind, "正在根据已确认的错因生成新练习……")
        try {
            val completed = request(prompt) { /* Generation output is not feedback for the source attempt. */ }
            val draft = MicroPractice.parse(completed, source)
            if (persist(kind, draft)) {
                setEntry(kind, draft)
                setPartial("")
                message(kind, "AI 原创微练习已保存为新草稿，请独立作答；原练习和错因已保留。")
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            message(kind, "微练习未生成：${CoachFailures.describe(e)} 原练习和错因已保留。")
        }
    }

    fun readingHint(number: Int, level: Int, followUp: String = "") = action(CoachKind.READING) {
        val kind = CoachKind.READING
        val entry = current.entries[kind] ?: return@action
        val prompt = try {
            require(followUp.length <= 2000) { "追问请控制在 2000 字以内" }
            ReadingGuidance.prompt(entry, number, level) + if (followUp.isBlank()) "" else {
                val previous = entry.feedback.substringAfterLast("## 第 $number 题 · 第 $level 层提示", "").substringBefore("\n\n## 第 ").takeLast(4000)
                "\n---本层此前提示（可能有误）---\n$previous\n---用户本层追问---\n$followUp\n仍只提供当前层提示，不提前揭示后续层次的答案。"
            }
        }
        catch (e: IllegalArgumentException) { message(kind, e.message.orEmpty()); return@action }
        if (!persist(kind, entry)) return@action
        if (!canRequest()) { message(kind, "答案已保存在本机。AI 提示需要在“我的”连接并选择模型；也可先按本地提示回看原卷。"); return@action }
        val selectedModel = model()
        startStream(entry.id, "$number/hint/$level")
        message(kind, "正在生成第 $number 题的第 $level 层提示……")
        try {
            val completed = request(prompt) { appendPartial(it) }
            check(completed.isNotBlank())
            val feedback = listOf(entry.feedback, "## 第 $number 题 · 第 $level 层提示\n\n$completed").filter(String::isNotBlank).joinToString("\n\n")
            val next = entry.copy(feedback = feedback, model = selectedModel, updatedAt = System.currentTimeMillis())
            if (persist(kind, next)) { setEntry(kind, next); setPartial(""); message(kind, "本层提示已保存") }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { message(kind, "提示未完成：${CoachFailures.describe(e)} 已保留答案和此前完整提示。")
        } finally { endStream() }
    }

    fun readingDiagnosis(number: Int, followUp: String = "") = action(CoachKind.READING) {
        val kind = CoachKind.READING
        val entry = current.entries[kind] ?: return@action
        val prompt = try {
            require(entry.submittedAt > 0) { "先录入并核对答案，再请求 AI 分析" }
            val passage = ExamCodec.passages(entry).firstOrNull { p -> p.questions.any { it.number == number } }
                ?: throw IllegalArgumentException("找不到对应原题")
            ReadingDiagnosis.prompt(passage, entry.answer, number, followUp, ReadingDiagnosis.saved(entry.feedback, number))
        } catch (e: IllegalArgumentException) { message(kind, e.message.orEmpty()); return@action }
        if (!persist(kind, entry)) return@action
        if (!canRequest()) {
            message(kind, "作答已保存。AI 分析需要在“我的”连接 ChatGPT 或其他 AI 服务并选择模型。")
            return@action
        }
        val selectedModel = model()
        startStream(entry.id, "$number/diagnosis")
        message(kind, "AI 正在结合本篇原文和你的选项分析错因……")
        try {
            val sendRequest = if (followUp.isBlank()) reviewRequest ?: request else request
            val completed = sendRequest(prompt) { appendPartial(it) }
            check(completed.isNotBlank())
            val response = if (followUp.isBlank()) completed else "追问：$followUp\n\n$completed"
            val feedback = listOf(entry.feedback, "${ReadingDiagnosis.heading(number)}\n\n$response")
                .filter(String::isNotBlank).joinToString("\n\n")
            val next = entry.copy(feedback = feedback, model = selectedModel, updatedAt = System.currentTimeMillis())
            if (persist(kind, next)) {
                setEntry(kind, next)
                setPartial("")
                message(kind, "本题 AI 分析已保存；请对照原文核实，错因由你确认。")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message(kind, "分析未完成：${CoachFailures.describe(e)} 已保留作答和此前完整分析。") }
        finally { endStream() }
    }

    fun submit(kind: CoachKind, explain: Boolean, followUp: String = "", focus: WritingFocus = WritingFocus.COMPLETE) = action(kind) {
        val entry = current.entries[kind] ?: return@action
        val prompt = try { CoachPrompts.build(entry, focus) + if (followUp.isBlank()) "" else {
            require(followUp.length <= 4000) { "追问请控制在 4000 字以内" }
            require(entry.feedback.isNotBlank()) { "请先完成一次解析" }
            "\n---此前 AI 反馈（供核对，可能有误）---\n${entry.feedback.takeLast(60_000)}\n---用户追问---\n$followUp\n请结合原文回答，必要时纠正此前错误，不冒充官方答案。" +
                "\n本轮是追问，以上完整批改的输出格式不适用于本轮。直接回答当前问题，仅引用相关原句；除非用户明确要求，不重复完整批改、整篇范文或全文重译。"
        } }
        catch (e: IllegalArgumentException) { message(kind, e.message.orEmpty()); return@action }
        val submitted = entry.copy(submittedAt = entry.submittedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis())
        if (!persist(kind, submitted)) return@action
        setEntry(kind, submitted)
        if (!explain) { message(kind, "作答已保存"); return@action }
        if (!canRequest()) { message(kind, "作答已保存；请到连接页配置 AI 服务并选择模型，再重试 AI。"); return@action }
        val selectedModel = model()
        startStream(submitted.id)
        message(kind, "AI 处理中；网络中断仍保留作答。")
        try {
            val sendRequest = if (followUp.isBlank()) reviewRequest ?: request else request
            val completed = sendRequest(prompt) { appendPartial(it) }
            check(completed.isNotBlank()) { "AI 没有返回可用文本" }
            val feedback = if (followUp.isNotBlank()) "${submitted.feedback}\n\n追问：$followUp\n\n$completed"
                else if (kind != CoachKind.READING) listOf(submitted.feedback, "## ${focus.label}\n\n$completed")
                    .filter(String::isNotBlank).joinToString("\n\n")
                else completed
            val result = submitted.copy(feedback = feedback, model = selectedModel, updatedAt = System.currentTimeMillis())
            if (persist(kind, result)) {
                setEntry(kind, result)
                setPartial("")
                message(kind, "AI 反馈已保存")
            } else {
                message(kind, "AI 已完成但反馈未保存，请重试；下方文本暂留内存。")
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            message(kind, "AI 未完成：${CoachFailures.describe(e)} 作答及此前完整反馈已保留；临时文本不算完整解析。")
        } finally { endStream() }
    }
}
