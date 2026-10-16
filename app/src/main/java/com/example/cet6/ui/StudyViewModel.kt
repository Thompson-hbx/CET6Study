package com.example.cet6.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.example.cet6.ai.AvailableModel
import com.example.cet6.ai.ResponsesClient
import com.example.cet6.auth.ChatGptAuth
import com.example.cet6.auth.SecureSessionStore
import com.example.cet6.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class StudyViewModel(app: Application) : AndroidViewModel(app) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
    private val auth = ChatGptAuth(http, SecureSessionStore(app))
    private val ai = ResponsesClient(http, auth)
    private val db = StudyDatabase.get(app)
    private val dao = db.dao()

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
    var status by androidx.compose.runtime.mutableStateOf("先测试 ChatGPT 登录，再发送一条 API 请求。")
        private set
    var result by androidx.compose.runtime.mutableStateOf("")
        private set
    var words by androidx.compose.runtime.mutableStateOf<List<WordEntity>>(emptyList())
        private set
    var readingAttempts by androidx.compose.runtime.mutableStateOf<List<ReadingAttempt>>(emptyList())
        private set
    var essays by androidx.compose.runtime.mutableStateOf<List<EssayEntity>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            dao.addWords(starterWords)
            dao.words().collectLatest { words = it }
        }
        viewModelScope.launch { dao.readingHistory().collectLatest { readingAttempts = it } }
        viewModelScope.launch { dao.essays().collectLatest { essays = it } }
        if (hasPlan) refreshModels()
    }

    fun selectModel(slug: String) { chosenModel = slug }

    fun signIn() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            status = "等待系统浏览器完成 ChatGPT 授权；请不要关闭应用进程。"
            try {
                val session = auth.signIn { uri: Uri ->
                    getApplication<Application>().startActivity(
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
        if (busy) return
        viewModelScope.launch {
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
        if (!hasPlan) {
            models = emptyList()
            chosenModel = ""
        }
    }

    fun refreshModels() {
        if (!hasPlan || busy) return
        viewModelScope.launch {
            busy = true
            try {
                models = ai.models()
                if (models.none { it.slug == chosenModel }) chosenModel = models.firstOrNull()?.slug.orEmpty()
                status = "已获取 ${models.size} 个当前账号可见模型。"
                if (models.isEmpty()) status = "当前账号没有可见模型；请检查套餐资格和授权。"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { status = "获取模型失败：${e.message}" }
            finally { syncSessionState(); busy = false }
        }
    }

    fun ask(prompt: String, saveEssay: Pair<String, String>? = null) {
        if (busy || !hasPlan || chosenModel.isBlank() || prompt.isBlank()) {
            status = "请先登录、加载并选择模型。"
            return
        }
        viewModelScope.launch {
            busy = true
            result = ""
            status = "正在流式请求 Responses API……"
            try {
                val completed = ai.stream(chosenModel, prompt) { delta ->
                    withContext(Dispatchers.Main) { result += delta }
                }
                status = "API 测试成功：已收到 response.completed。"
                if (saveEssay != null) dao.addEssay(EssayEntity(
                    topic = saveEssay.first, content = saveEssay.second, feedback = completed
                ))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                status = "调用失败：${e.message ?: "未知错误"}（已收到的文本不视为完成）"
            } finally { syncSessionState(); busy = false }
        }
    }

    fun review(word: WordEntity, remember: Boolean) {
        viewModelScope.launch {
            val updated = nextReview(word, remember, System.currentTimeMillis())
            dao.recordReview(word.word, updated.dueAt, updated.repetitions, updated.intervalDays)
        }
    }

    fun recordReading(choice: String) {
        viewModelScope.launch {
            dao.addReadingAttempt(ReadingAttempt(
                questionId = "reading_demo_1", answer = choice,
                correct = choice == "B"
            ))
        }
    }
}
