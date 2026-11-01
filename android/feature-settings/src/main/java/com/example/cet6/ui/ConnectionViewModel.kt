package com.example.cet6.ui

import androidx.lifecycle.ViewModel
import com.example.cet6.ai.AiProvider
import com.example.cet6.ai.ReasoningEffort
import com.example.cet6.ai.ReasoningFormat
import com.example.cet6.domain.AiGateway
import com.example.cet6.domain.AiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** What the AI connection screen can ask for; text being typed stays in the screen until it is submitted. */
sealed interface ConnectionIntent {
    data class SelectProvider(val provider: AiProvider) : ConnectionIntent
    data class SaveApi(val baseUrl: String, val key: String) : ConnectionIntent
    data object ClearApi : ConnectionIntent
    data object SignIn : ConnectionIntent
    data object SignOut : ConnectionIntent
    data object RefreshModels : ConnectionIntent
    data class SelectModel(val slug: String) : ConnectionIntent
    data class SetDeepReview(val enabled: Boolean) : ConnectionIntent
    data class SelectReviewEffort(val effort: ReasoningEffort) : ConnectionIntent
    data class SelectReviewFormat(val format: ReasoningFormat) : ConnectionIntent
    data class SendTest(val message: String) : ConnectionIntent
}

/** One-off outcomes the screen reacts to but does not draw from state. */
sealed interface ConnectionEffect {
    /** The typed key was stored, so the screen drops it from memory. */
    data object ApiKeySaved : ConnectionEffect
}

/**
 * State and intents of the AI connection screen. [ai] belongs to the activity's [StudyViewModel], which lives in
 * the same ViewModelStore, so both are cleared together.
 */
class ConnectionViewModel(private val ai: AiGateway) : ViewModel() {
    val state: StateFlow<AiState> = ai.state.flow
    private val effects = Channel<ConnectionEffect>(Channel.BUFFERED)
    val effect: Flow<ConnectionEffect> = effects.receiveAsFlow()

    fun onIntent(intent: ConnectionIntent) {
        when (intent) {
            is ConnectionIntent.SelectProvider -> ai.selectProvider(intent.provider)
            is ConnectionIntent.SaveApi ->
                if (ai.saveApiConnection(intent.baseUrl, intent.key)) effects.trySend(ConnectionEffect.ApiKeySaved)
            ConnectionIntent.ClearApi -> ai.clearApiConnection()
            ConnectionIntent.SignIn -> ai.signIn()
            ConnectionIntent.SignOut -> ai.signOut()
            ConnectionIntent.RefreshModels -> ai.refreshModels()
            is ConnectionIntent.SelectModel -> ai.selectModel(intent.slug)
            is ConnectionIntent.SetDeepReview -> ai.setDeepReview(intent.enabled)
            is ConnectionIntent.SelectReviewEffort -> ai.selectReviewEffort(intent.effort)
            is ConnectionIntent.SelectReviewFormat -> ai.selectReviewFormat(intent.format)
            is ConnectionIntent.SendTest -> ai.ask(intent.message)
        }
    }
}
