package com.example.cet6.domain

import com.example.cet6.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Requests whose results become [CoachTurn]s. Unlike the older feedback field, nothing here
 * rewrites the practice row, so the learner can keep editing while a reply streams in. Only a
 * complete reply is saved; a stopped or failed request leaves the stored turns exactly as before.
 */
class ConversationService(
    private val repository: CoachRepository,
    private val scope: CoroutineScope,
    private val canRequest: () -> Boolean,
    private val model: () -> String,
    private val reviewRequest: AiRequest?,
    private val request: AiRequest
) {
    data class Pending(val practiceId: String, val anchor: String, val kind: String, val question: String, val text: String = "")
    data class Failure(val practiceId: String, val anchor: String, val message: String, val question: String = "",
        val kind: String = "", val stopped: Boolean = false, val retry: (() -> Unit)? = null) {
        val chat get() = kind == TurnKind.CHAT
    }
    data class State(val pending: Pending? = null, val failure: Failure? = null)

    val state = ServiceState(State())
    private var job: Job? = null
    private var retryCurrent: (() -> Unit)? = null
    private val stateWrites = Mutex()

    private fun setPending(transform: (Pending?) -> Pending?) { state.update { it.copy(pending = transform(it.pending)) } }
    private fun setFailure(value: Failure?) { state.update { it.copy(failure = value) } }

    fun turns(practiceId: String): Flow<List<CoachTurn>> = repository.turns(practiceId)
    fun dismissFailure() = setFailure(null)

    /**
     * Sends [prompt] for [practice]. A non-blank [question] is the learner's own words and is saved
     * together with the reply, so an unanswered question never lingers in the thread.
     */
    fun send(practice: CoachPractice, anchor: String, kind: String, question: String = "", prompt: () -> String) {
        if (state.value.pending != null) return
        val text = try { prompt() } catch (e: IllegalArgumentException) {
            setFailure(Failure(practice.id, anchor, e.message ?: "内容不完整，暂时无法发送", question, kind)); return
        }
        if (!canRequest()) {
            setFailure(Failure(practice.id, anchor, "需要先在「我的 → AI 连接」配置 AI 服务并选择模型。", question, kind))
            return
        }
        setFailure(null)
        retryCurrent = { send(practice, anchor, kind, question, prompt) }
        val startedAt = System.currentTimeMillis()
        setPending { Pending(practice.id, anchor, kind, question) }
        val runningJob = scope.launch {
            try {
                val selectedModel = model()
                val sendRequest = if (isDeepReviewKind(kind)) reviewRequest ?: request else request
                val reply = sendRequest(text) { delta ->
                    setPending { pending ->
                        pending?.let {
                            val next = it.text + delta
                            CoachTurns.checkReplyLength(next)
                            it.copy(text = next)
                        }
                    }
                }
                check(reply.isNotBlank()) { "AI 没有返回可用文本" }
                CoachTurns.checkReplyLength(reply)
                val userTurn = question.takeIf(String::isNotBlank)?.let {
                    CoachTurn(practiceId = practice.id, anchor = anchor, kind = TurnKind.CHAT,
                        role = CoachTurn.USER, content = it.trim(), createdAt = startedAt)
                }
                repository.saveExchange(userTurn, CoachTurn(practiceId = practice.id, anchor = anchor,
                    kind = kind, role = CoachTurn.ASSISTANT, content = reply, model = selectedModel,
                    createdAt = maxOf(System.currentTimeMillis(), startedAt + 1)))
                setPending { null }
            } catch (e: CancellationException) {
                setPending { null }
                throw e
            } catch (e: Exception) {
                setPending { null }
                // Closing the socket on stop surfaces as an I/O error; that is the learner's choice, not a failure.
                if (!isActive) return@launch
                setFailure(Failure(practice.id, anchor, CoachFailures.describe(e), question, kind) {
                    send(practice, anchor, kind, question, prompt)
                })
            }
        }
        job = runningJob
        runningJob.invokeOnCompletion {
            if (job === runningJob) setPending { null }
        }
    }

    /** Keep the question and an explicit retry; partial output is never saved as a reply. */
    fun stop() {
        val current = state.value.pending ?: return
        setFailure(Failure(current.practiceId, current.anchor, "已停止，本次未完成的回复未保存。", current.question,
            current.kind, stopped = true, retry = retryCurrent))
        job?.cancel()
        // The cancelled coroutine clears pending after its socket closes, before another request can start.
    }

    fun updateDecision(turn: CoachTurn, issueId: Int, decision: ReviewDecision) = scope.launch {
        try { stateWrites.withLock { repository.updateDecision(turn.id, issueId, decision) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { setFailure(Failure(turn.practiceId, turn.anchor, "本机保存失败，请重试。")) }
    }
}

/** Only full reviews and evidence analysis opt in; hints and follow-up chat retain server defaults. */
internal fun isDeepReviewKind(kind: String): Boolean = kind in setOf(
    TurnKind.WRITING_REVIEW, TurnKind.TRANSLATION_REVIEW, TurnKind.READING_EXPLAIN)
