package com.example.cet6.ui

import android.content.Context
import androidx.compose.runtime.getValue
import com.example.cet6.ai.AiProvider
import com.example.cet6.ai.AvailableModel
import com.example.cet6.ai.ReasoningEffort
import com.example.cet6.ai.ReasoningFormat
import com.example.cet6.domain.AiGateway
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/** Compose view of [AiGateway] for screens that read ViewModel properties. */
internal class AiConnectionState(val gateway: AiGateway) {
    constructor(context: Context, scope: CoroutineScope, http: OkHttpClient,
        saveEssay: suspend (Pair<String, String>, String) -> Unit) : this(AiGateway(context, scope, http, saveEssay))

    private val ui by gateway.state.composeState()
    val provider: AiProvider get() = ui.provider
    val apiBaseUrl: String get() = ui.apiBaseUrl
    val hasApiKey: Boolean get() = ui.hasApiKey
    val aiReady: Boolean get() = ui.aiReady
    val deepReviewEnabled: Boolean get() = ui.deepReviewEnabled
    val reviewEffort: ReasoningEffort get() = ui.reviewEffort
    val reviewFormat: ReasoningFormat get() = ui.reviewFormat
    val sessionEmail: String get() = ui.sessionEmail
    val hasPlan: Boolean get() = ui.hasPlan
    val models: List<AvailableModel> get() = ui.models
    val chosenModel: String get() = ui.chosenModel
    val busy: Boolean get() = ui.busy
    val status: String get() = ui.status
    val result: String get() = ui.result

    fun setDeepReview(enabled: Boolean) = gateway.setDeepReview(enabled)
    fun selectReviewEffort(value: ReasoningEffort) = gateway.selectReviewEffort(value)
    fun selectReviewFormat(value: ReasoningFormat) = gateway.selectReviewFormat(value)
    internal suspend fun streamCoach(prompt: String, deepReview: Boolean = false, onDelta: suspend (String) -> Unit): String =
        gateway.stream(prompt, deepReview, onDelta)
    fun selectModel(slug: String) = gateway.selectModel(slug)
    fun selectProvider(value: AiProvider) = gateway.selectProvider(value)
    fun saveApiConnection(baseUrl: String, key: String): Boolean = gateway.saveApiConnection(baseUrl, key)
    fun clearApiConnection() = gateway.clearApiConnection()
    fun signIn() = gateway.signIn()
    fun signOut() = gateway.signOut()
    fun refreshModels() = gateway.refreshModels()
    fun ask(prompt: String, saveEssay: Pair<String, String>? = null) = gateway.ask(prompt, saveEssay)
}
