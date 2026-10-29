package com.example.cet6.domain

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.cet6.ai.AiProvider
import com.example.cet6.ai.ApiConnection
import com.example.cet6.ai.AvailableModel
import com.example.cet6.ai.ChatCompletionsClient
import com.example.cet6.ai.ReasoningEffort
import com.example.cet6.ai.ReasoningFormat
import com.example.cet6.ai.ResponsesClient
import com.example.cet6.ai.ReviewReasoning
import com.example.cet6.auth.ChatGptAuth
import com.example.cet6.auth.SecureApiStore
import com.example.cet6.auth.SecureSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Everything a screen may show about the AI connection; the API key itself never leaves [AiGateway]. */
data class AiState(
    val provider: AiProvider,
    val apiBaseUrl: String,
    val hasApiKey: Boolean = false,
    val sessionEmail: String = "",
    val hasPlan: Boolean = false,
    val models: List<AvailableModel> = emptyList(),
    val chosenModel: String = "",
    val deepReviewEnabled: Boolean = false,
    val reviewEffort: ReasoningEffort = ReasoningEffort.HIGH,
    val reviewFormat: ReasoningFormat = ReasoningFormat.OPENAI,
    val busy: Boolean = false,
    val status: String = "选择 AI 服务，连接后可发送测试请求。",
    val result: String = ""
) {
    val aiReady: Boolean get() = if (provider == AiProvider.CHATGPT) hasPlan else hasApiKey
}

/** Owns provider configuration, OAuth status and streaming requests. */
class AiGateway(
    private val context: Context,
    private val scope: CoroutineScope,
    http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build(),
    private val saveEssay: suspend (Pair<String, String>, String) -> Unit
) {
    private val auth = ChatGptAuth(http, SecureSessionStore(context))
    private val ai = ResponsesClient(http, auth)
    private val compatibleAi = ChatCompletionsClient(http)
    private val apiStore = SecureApiStore(context)
    private val prefs = context.getSharedPreferences("study_ui", Context.MODE_PRIVATE)
    private var apiConnection: ApiConnection? = null

    val state: ServiceState<AiState>
    private val current get() = state.value
    private fun set(transform: AiState.() -> AiState) { state.update(transform) }

    init {
        val provider = AiProvider.entries.firstOrNull { it.name == prefs.getString("ai_provider", null) } ?: AiProvider.CHATGPT
        val session = auth.session()
        state = ServiceState(AiState(provider = provider, apiBaseUrl = provider.defaultUrl,
            sessionEmail = session?.email?.ifBlank { "已验证 ChatGPT 身份" }.orEmpty(),
            hasPlan = session?.canUsePlan == true))
        restoreApiConnection()
        set { copy(chosenModel = prefs.getString(modelPreference(), "").orEmpty()) }
        restoreReviewSettings()
        if (current.aiReady) refreshModels()
    }

    private fun modelPreference(): String =
        if (current.provider == AiProvider.CHATGPT) "model" else "model_${current.provider.name}"

    // Destination and model are part of the key so one provider's settings cannot leak into another's.
    private fun reviewPreference(): String =
        "deep_review_${current.provider.name}_${current.apiBaseUrl}_${current.chosenModel}"

    private fun setConnection(connection: ApiConnection?) {
        apiConnection = connection
        set { copy(apiBaseUrl = connection?.baseUrl ?: provider.defaultUrl, hasApiKey = connection != null) }
    }

    private fun restoreReviewSettings() {
        val key = reviewPreference()
        val format = when (current.provider) {
            AiProvider.CHATGPT -> ReasoningFormat.OPENAI
            AiProvider.DEEPSEEK -> ReasoningFormat.DEEPSEEK
            AiProvider.CUSTOM -> ReasoningFormat.entries.firstOrNull { it.name == prefs.getString("${key}_format", null) }
                ?: ReasoningFormat.OPENAI
        }
        val effort = format.efforts.firstOrNull { it.name == prefs.getString("${key}_effort", null) }
            ?: ReasoningEffort.HIGH
        set { copy(reviewFormat = format, reviewEffort = effort, deepReviewEnabled = prefs.getBoolean("${key}_enabled", false)) }
    }

    fun setDeepReview(enabled: Boolean) {
        if (current.busy) return
        set { copy(deepReviewEnabled = enabled) }
        prefs.edit().putBoolean("${reviewPreference()}_enabled", enabled).apply()
    }

    fun selectReviewEffort(value: ReasoningEffort) {
        if (current.busy || value !in current.reviewFormat.efforts) return
        set { copy(reviewEffort = value) }
        prefs.edit().putString("${reviewPreference()}_effort", value.name).apply()
    }

    fun selectReviewFormat(value: ReasoningFormat) {
        if (current.busy || current.provider != AiProvider.CUSTOM) return
        set { copy(reviewFormat = value) }
        prefs.edit().putString("${reviewPreference()}_format", value.name).apply()
        if (current.reviewEffort !in value.efforts) selectReviewEffort(ReasoningEffort.HIGH)
    }

    private suspend fun streamAi(prompt: String, deepReview: Boolean = false,
        onDelta: suspend (String) -> Unit): String {
        val settings = current
        val reasoning = if (deepReview && settings.deepReviewEnabled)
            ReviewReasoning(settings.reviewEffort, settings.reviewFormat) else null
        return if (settings.provider == AiProvider.CHATGPT) ai.stream(settings.chosenModel, prompt, reasoning, onDelta)
        else compatibleAi.stream(apiConnection ?: error("请先保存 API 配置"), settings.chosenModel, prompt, reasoning, onDelta)
    }

    /** Streams one coaching request; [onDelta] runs on the main thread. */
    suspend fun stream(prompt: String, deepReview: Boolean = false, onDelta: suspend (String) -> Unit): String {
        set { copy(busy = true) }
        try {
            return streamAi(prompt, deepReview) { delta -> withContext(Dispatchers.Main) { onDelta(delta) } }
        } finally { syncSessionState(); set { copy(busy = false) } }
    }

    fun selectModel(slug: String) {
        if (current.busy) return
        set { copy(chosenModel = slug.trim()) }
        restoreReviewSettings()
        prefs.edit().putString(modelPreference(), current.chosenModel).apply()
    }

    private fun restoreApiConnection() {
        setConnection(null)
        if (current.provider == AiProvider.CHATGPT) return
        try { setConnection(apiStore.load(current.provider)) }
        catch (_: Exception) { set { copy(status = "API 配置无法解密，请重新填写并保存；原配置尚未覆盖。") } }
    }

    fun selectProvider(value: AiProvider) {
        if (current.busy || value == current.provider) return
        set { copy(provider = value, models = emptyList(), result = "", status = "已切换到 ${value.label}。") }
        prefs.edit().putString("ai_provider", value.name).apply()
        restoreApiConnection()
        set { copy(chosenModel = prefs.getString(modelPreference(), "").orEmpty()) }
        restoreReviewSettings()
        if (current.aiReady) refreshModels()
    }

    fun saveApiConnection(baseUrl: String, key: String): Boolean {
        val provider = current.provider
        if (current.busy || provider == AiProvider.CHATGPT) return false
        return try {
            val url = if (provider == AiProvider.DEEPSEEK) provider.defaultUrl else baseUrl
            // A changed destination always requires a newly entered key.
            val savedKey = if (key.isBlank() && ApiConnection.validatedBaseUrl(url).toString() == apiConnection?.baseUrl)
                apiConnection?.apiKey.orEmpty() else key
            val connection = ApiConnection.create(url, savedKey)
            apiStore.save(provider, connection)
            setConnection(connection)
            restoreReviewSettings()
            set { copy(models = emptyList(), result = "",
                status = "API 配置已加密保存；请刷新模型或填写模型名称，再发送测试请求。") }
            true
        } catch (_: IllegalArgumentException) {
            set { copy(status = "配置无效：请填写 HTTPS Base URL（不含账号、参数或接口路径）和有效 API Key；更换地址时需重新填写 Key。") }
            false
        } catch (_: Exception) {
            set { copy(status = "API 配置保存失败，请检查设备安全存储后重试。") }
            false
        }
    }

    fun clearApiConnection() {
        val provider = current.provider
        if (current.busy || provider == AiProvider.CHATGPT) return
        try {
            apiStore.clear(provider)
            setConnection(null)
            set { copy(models = emptyList(), chosenModel = "") }
            restoreReviewSettings()
            prefs.edit().remove(modelPreference()).apply()
            set { copy(result = "", status = "已清除本机 ${provider.label} 配置；如需撤销 Key，请到服务商控制台操作。") }
        } catch (_: Exception) { set { copy(status = "清除 API 配置失败，请重试。") } }
    }

    fun signIn() {
        if (current.busy || current.provider != AiProvider.CHATGPT) return
        scope.launch {
            set { copy(busy = true, status = "等待系统浏览器完成 ChatGPT 授权；请不要关闭应用进程。") }
            try {
                val session = auth.signIn { uri: Uri ->
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                set { copy(sessionEmail = session.email.ifBlank { "已验证 ChatGPT 身份" }, hasPlan = session.canUsePlan,
                    status = if (session.canUsePlan) "OAuth 登录成功，已授予套餐调用权限。" else
                        "登录成功，但没有授予套餐额度权限，请重新授权。") }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                set { copy(status = "登录失败：${e.message ?: "未知错误"}") }
            } finally { set { copy(busy = false) } }
            if (current.hasPlan) refreshModels()
        }
    }

    fun signOut() {
        if (current.busy || current.provider != AiProvider.CHATGPT) return
        scope.launch {
            set { copy(busy = true, status = "正在尝试撤销会话并清除本机凭证……") }
            try {
                val revoked = auth.signOut()
                set { copy(result = "", status = if (revoked) "已撤销远端会话并清除本机令牌。" else
                    "已清除本机令牌；未确认远端撤销，请到 ChatGPT 设置检查。") }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { set { copy(status = "退出未完全成功，请重试并检查 ChatGPT 设置。") }
            } finally {
                syncSessionState()
                set { copy(busy = false) }
            }
        }
    }

    private fun syncSessionState() {
        val session = auth.session()
        set {
            val synced = copy(sessionEmail = session?.email?.ifBlank { "已验证 ChatGPT 身份" }.orEmpty(),
                hasPlan = session?.canUsePlan == true)
            if (synced.aiReady) synced else synced.copy(models = emptyList(), chosenModel = "")
        }
    }

    fun refreshModels() {
        if (!current.aiReady || current.busy) return
        scope.launch {
            set { copy(busy = true) }
            try {
                val provider = current.provider
                val models = if (provider == AiProvider.CHATGPT) ai.models()
                    else compatibleAi.models(apiConnection ?: error("请先保存 API 配置"))
                val remembered = prefs.getString(modelPreference(), null)
                set {
                    val keep = provider != AiProvider.CHATGPT && chosenModel.isNotBlank() || models.any { it.slug == chosenModel }
                    copy(models = models, chosenModel = if (keep) chosenModel else
                        (models.firstOrNull { it.slug == remembered } ?: models.firstOrNull())?.slug.orEmpty())
                }
                prefs.edit().putString(modelPreference(), current.chosenModel).apply()
                restoreReviewSettings()
                set { copy(status = if (models.isEmpty()) "没有获取到可用模型；请检查权限，或为兼容接口手动填写模型名称。"
                    else "已获取 ${models.size} 个当前账号可见模型。") }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { set { copy(status = "获取模型失败，请检查网络、权限与接口配置；兼容接口也可手动填写模型名称。") } }
            finally { syncSessionState(); set { copy(busy = false) } }
        }
    }

    fun ask(prompt: String, saveEssay: Pair<String, String>? = null) {
        val settings = current
        if (settings.busy || !settings.aiReady || settings.chosenModel.isBlank() || prompt.isBlank()) {
            set { copy(status = "请先连接 AI 服务、选择或填写模型。") }
            return
        }
        scope.launch {
            set { copy(busy = true, result = "", status = "正在请求 ${provider.label}……") }
            try {
                val completed = streamAi(prompt, deepReview = true) { delta ->
                    withContext(Dispatchers.Main) { set { copy(result = result + delta) } }
                }
                set { copy(status = "API 测试成功：已收到完整回复。") }
                if (saveEssay != null) this@AiGateway.saveEssay(saveEssay, completed)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                set { copy(status = "调用失败：${e.message ?: "未知错误"}（已收到的文本不视为完成）") }
            } finally { syncSessionState(); set { copy(busy = false) } }
        }
    }
}
