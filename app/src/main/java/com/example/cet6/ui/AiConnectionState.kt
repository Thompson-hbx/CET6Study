package com.example.cet6.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.example.cet6.ai.AvailableModel
import com.example.cet6.ai.ResponsesClient
import com.example.cet6.ai.AiProvider
import com.example.cet6.ai.ApiConnection
import com.example.cet6.ai.ChatCompletionsClient
import com.example.cet6.auth.SecureApiStore
import com.example.cet6.auth.ChatGptAuth
import com.example.cet6.auth.SecureSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Owns provider configuration, OAuth status and requests for the ViewModel's lifetime. */
internal class AiConnectionState(
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
    var provider by androidx.compose.runtime.mutableStateOf(
        AiProvider.entries.firstOrNull { it.name == prefs.getString("ai_provider", null) } ?: AiProvider.CHATGPT)
        private set
    private var apiConnection by androidx.compose.runtime.mutableStateOf<ApiConnection?>(null)
    val apiBaseUrl: String get() = apiConnection?.baseUrl ?: provider.defaultUrl
    val hasApiKey: Boolean get() = apiConnection != null
    val aiReady: Boolean get() = if (provider == AiProvider.CHATGPT) hasPlan else hasApiKey
    private fun modelPreference(): String = if (provider == AiProvider.CHATGPT) "model" else "model_${provider.name}"

    private suspend fun streamAi(prompt: String, onDelta: suspend (String) -> Unit): String =
        if (provider == AiProvider.CHATGPT) ai.stream(chosenModel, prompt, onDelta)
        else compatibleAi.stream(apiConnection ?: error("请先保存 API 配置"), chosenModel, prompt, onDelta)
    internal suspend fun streamCoach(prompt: String, onDelta: suspend (String) -> Unit): String {
        busy = true
        try {
            return streamAi(prompt) { delta -> withContext(Dispatchers.Main) { onDelta(delta) } }
        } finally { syncSessionState(); busy = false }
    }
    var sessionEmail by androidx.compose.runtime.mutableStateOf(
        auth.session()?.email?.ifBlank { "已验证 ChatGPT 身份" }.orEmpty())
        private set
    var hasPlan by androidx.compose.runtime.mutableStateOf(auth.session()?.canUsePlan == true)
        private set
    var models by androidx.compose.runtime.mutableStateOf<List<AvailableModel>>(emptyList())
        private set
    var chosenModel by androidx.compose.runtime.mutableStateOf("")
        private set
    var busy by androidx.compose.runtime.mutableStateOf(false)
        private set
    var status by androidx.compose.runtime.mutableStateOf("选择 AI 服务，连接后可发送测试请求。")
        private set
    var result by androidx.compose.runtime.mutableStateOf("")
        private set

    init {
        restoreApiConnection()
        chosenModel = prefs.getString(modelPreference(), "").orEmpty()
        if (aiReady) refreshModels()
    }

    fun selectModel(slug: String) {
        if (busy) return
        chosenModel = slug.trim()
        prefs.edit().putString(modelPreference(), chosenModel).apply()
    }

    private fun restoreApiConnection() {
        apiConnection = null
        if (provider == AiProvider.CHATGPT) return
        try { apiConnection = apiStore.load(provider) }
        catch (_: Exception) { status = "API 配置无法解密，请重新填写并保存；原配置尚未覆盖。" }
    }

    fun selectProvider(value: AiProvider) {
        if (busy || value == provider) return
        provider = value
        prefs.edit().putString("ai_provider", value.name).apply()
        models = emptyList()
        result = ""
        status = "已切换到 ${value.label}。"
        restoreApiConnection()
        chosenModel = prefs.getString(modelPreference(), "").orEmpty()
        if (aiReady) refreshModels()
    }

    fun saveApiConnection(baseUrl: String, key: String): Boolean {
        if (busy || provider == AiProvider.CHATGPT) return false
        return try {
            val url = if (provider == AiProvider.DEEPSEEK) provider.defaultUrl else baseUrl
            // A changed destination always requires a newly entered key.
            val savedKey = if (key.isBlank() && ApiConnection.validatedBaseUrl(url).toString() == apiConnection?.baseUrl)
                apiConnection?.apiKey.orEmpty() else key
            val connection = ApiConnection.create(url, savedKey)
            apiStore.save(provider, connection)
            apiConnection = connection
            models = emptyList()
            result = ""
            status = "API 配置已加密保存；请刷新模型或填写模型名称，再发送测试请求。"
            true
        } catch (_: IllegalArgumentException) {
            status = "配置无效：请填写 HTTPS Base URL（不含账号、参数或接口路径）和有效 API Key；更换地址时需重新填写 Key。"
            false
        } catch (_: Exception) {
            status = "API 配置保存失败，请检查设备安全存储后重试。"
            false
        }
    }

    fun clearApiConnection() {
        if (busy || provider == AiProvider.CHATGPT) return
        try {
            apiStore.clear(provider)
            apiConnection = null
            models = emptyList()
            chosenModel = ""
            prefs.edit().remove(modelPreference()).apply()
            result = ""
            status = "已清除本机 ${provider.label} 配置；如需撤销 Key，请到服务商控制台操作。"
        } catch (_: Exception) { status = "清除 API 配置失败，请重试。" }
    }

    fun signIn() {
        if (busy || provider != AiProvider.CHATGPT) return
        scope.launch {
            busy = true
            status = "等待系统浏览器完成 ChatGPT 授权；请不要关闭应用进程。"
            try {
                val session = auth.signIn { uri: Uri ->
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                sessionEmail = session.email.ifBlank { "已验证 ChatGPT 身份" }
                hasPlan = session.canUsePlan
                status = if (hasPlan) "OAuth 登录成功，已授予套餐调用权限。" else
                    "登录成功，但没有授予套餐额度权限，请重新授权。"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                status = "登录失败：${e.message ?: "未知错误"}"
            } finally { busy = false }
            if (hasPlan) refreshModels()
        }
    }

    fun signOut() {
        if (busy || provider != AiProvider.CHATGPT) return
        scope.launch {
            busy = true
            status = "正在尝试撤销会话并清除本机凭证……"
            try {
                val revoked = auth.signOut()
                result = ""
                status = if (revoked) "已撤销远端会话并清除本机令牌。" else
                    "已清除本机令牌；未确认远端撤销，请到 ChatGPT 设置检查。"
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { status = "退出未完全成功，请重试并检查 ChatGPT 设置。"
            } finally {
                syncSessionState()
                busy = false
            }
        }
    }

    private fun syncSessionState() {
        val current = auth.session()
        sessionEmail = current?.email?.ifBlank { "已验证 ChatGPT 身份" }.orEmpty()
        hasPlan = current?.canUsePlan == true
        if (!aiReady) {
            models = emptyList()
            chosenModel = ""
        }
    }

    fun refreshModels() {
        if (!aiReady || busy) return
        scope.launch {
            busy = true
            try {
                models = if (provider == AiProvider.CHATGPT) ai.models()
                    else compatibleAi.models(apiConnection ?: error("请先保存 API 配置"))
                val remembered = prefs.getString(modelPreference(), null)
                if (provider == AiProvider.CHATGPT || chosenModel.isBlank()) {
                    if (models.none { it.slug == chosenModel }) chosenModel =
                        (models.firstOrNull { it.slug == remembered } ?: models.firstOrNull())?.slug.orEmpty()
                }
                prefs.edit().putString(modelPreference(), chosenModel).apply()
                status = "已获取 ${models.size} 个当前账号可见模型。"
                if (models.isEmpty()) status = "没有获取到可用模型；请检查权限，或为兼容接口手动填写模型名称。"
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { status = "获取模型失败，请检查网络、权限与接口配置；兼容接口也可手动填写模型名称。" }
            finally { syncSessionState(); busy = false }
        }
    }

    fun ask(prompt: String, saveEssay: Pair<String, String>? = null) {
        if (busy || !aiReady || chosenModel.isBlank() || prompt.isBlank()) {
            status = "请先连接 AI 服务、选择或填写模型。"
            return
        }
        scope.launch {
            busy = true
            result = ""
            status = "正在请求 ${provider.label}……"
            try {
                val completed = streamAi(prompt) { delta ->
                    withContext(Dispatchers.Main) { result += delta }
                }
                status = "API 测试成功：已收到完整回复。"
                if (saveEssay != null) this@AiConnectionState.saveEssay(saveEssay, completed)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                status = "调用失败：${e.message ?: "未知错误"}（已收到的文本不视为完成）"
            } finally { syncSessionState(); busy = false }
        }
    }

}
