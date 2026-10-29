package com.example.cet6.ui

import androidx.compose.runtime.getValue
import com.example.cet6.data.*
import com.example.cet6.domain.AiRequest
import com.example.cet6.domain.ConversationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** Compose view of [ConversationService] for screens that read ViewModel properties. */
class CoachTalk internal constructor(val service: ConversationService) {
    constructor(repository: CoachRepository, scope: CoroutineScope, canRequest: () -> Boolean, model: () -> String,
        reviewRequest: AiRequest?, request: AiRequest) :
        this(ConversationService(repository, scope, canRequest, model, reviewRequest, request))
    constructor(repository: CoachRepository, scope: CoroutineScope, canRequest: () -> Boolean, model: () -> String,
        request: AiRequest) : this(repository, scope, canRequest, model, null, request)

    private val ui by service.state.composeState()
    val pending: ConversationService.Pending? get() = ui.pending
    val failure: ConversationService.Failure? get() = ui.failure
    val busy get() = pending != null

    fun turns(practiceId: String): Flow<List<CoachTurn>> = service.turns(practiceId)
    fun pendingFor(practiceId: String, anchor: String) = pending?.takeIf { it.practiceId == practiceId && it.anchor == anchor }
    /** [chat] selects follow-up failures (shown by the composer) or other requests (shown where they were started). */
    fun failureFor(practiceId: String, anchor: String, chat: Boolean? = null) =
        failure?.takeIf { it.practiceId == practiceId && it.anchor == anchor && (chat == null || it.chat == chat) }
    fun dismissFailure() = service.dismissFailure()
    fun send(practice: CoachPractice, anchor: String, kind: String, question: String = "", prompt: () -> String) =
        service.send(practice, anchor, kind, question, prompt)
    fun stop() = service.stop()
    fun updateDecision(turn: CoachTurn, issueId: Int, decision: ReviewDecision) = service.updateDecision(turn, issueId, decision)
}
