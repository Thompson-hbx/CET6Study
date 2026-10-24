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
import com.example.cet6.ai.AiProvider
import com.example.cet6.ai.ApiConnection
import com.example.cet6.ai.ChatCompletionsClient
import com.example.cet6.auth.SecureApiStore
import com.example.cet6.auth.ChatGptAuth
import com.example.cet6.auth.SecureSessionStore
import com.example.cet6.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class StudyViewModel(app: Application) : AndroidViewModel(app) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
    private val auth = ChatGptAuth(http, SecureSessionStore(app))
    private val ai = ResponsesClient(http, auth)
    private val compatibleAi = ChatCompletionsClient(http)
    private val apiStore = SecureApiStore(app)
    private val db = StudyDatabase.get(app)
    private val dao = db.dao()
    private val study = StudyRepository(db)
    private val backups = BackupRepository(db)
    private val coachRepository = CoachRepository(db)
    private val prefs = app.getSharedPreferences("study_ui", android.content.Context.MODE_PRIVATE)
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
    private suspend fun streamCoach(prompt: String, onDelta: suspend (String) -> Unit): String {
        busy = true
        try {
            return streamAi(prompt) { delta -> withContext(Dispatchers.Main) { onDelta(delta) } }
        } finally { syncSessionState(); busy = false }
    }
    val coach = CoachState(coachRepository, viewModelScope,
        canRequest = { aiReady && chosenModel.isNotBlank() && !busy },
        model = { chosenModel },
        request = ::streamCoach)
    /** Saved explanations and follow-ups; independent of the practice rows above. */
    val talk = CoachTalk(coachRepository, viewModelScope,
        canRequest = { aiReady && chosenModel.isNotBlank() && !busy },
        model = { chosenModel },
        request = ::streamCoach)
    var reviewTurns by androidx.compose.runtime.mutableStateOf<List<CoachTurn>>(emptyList())
        private set
    var lastTab: String
        get() = prefs.getString("tab", "writing") ?: "writing"
        set(value) { prefs.edit().putString("tab", value).apply() }
    var backupBusy by androidx.compose.runtime.mutableStateOf(false)
        private set
    var backupStatus by androidx.compose.runtime.mutableStateOf("")
        private set
    var backupPreview by androidx.compose.runtime.mutableStateOf<StudyBackup?>(null)
        private set
    private val examStore = ExamStore(app)
    var examPapers by androidx.compose.runtime.mutableStateOf<List<ExamPaper>>(emptyList())
        private set
    var examPreview by androidx.compose.runtime.mutableStateOf<List<ExamPaper>>(emptyList())
        private set
    var examStatus by androidx.compose.runtime.mutableStateOf("")
        private set
    var examBusy by androidx.compose.runtime.mutableStateOf(false)
        private set
    private var examLoaded = false
    var pronunciations by androidx.compose.runtime.mutableStateOf<Map<String, String>>(emptyMap())
        private set

    var now by androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis())
        private set
    var plan by androidx.compose.runtime.mutableStateOf<DailyPlan?>(null)
        private set
    var wordReviews by androidx.compose.runtime.mutableStateOf<List<WordReview>>(emptyList())
        private set
    var dailyProgress by androidx.compose.runtime.mutableStateOf<List<DailyWordProgress>>(emptyList())
        private set
    var studyStatus by androidx.compose.runtime.mutableStateOf("")
        private set
    var savingStudy by androidx.compose.runtime.mutableStateOf(false)
        private set
    var schedules by androidx.compose.runtime.mutableStateOf<Map<String, WordSchedule>>(emptyMap())
        private set
    val today: String get() = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    val todayProgress: DailyWordProgress? get() = dailyProgress.find { it.date == today }

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
    var words by androidx.compose.runtime.mutableStateOf<List<WordEntity>>(emptyList())
        private set
    var readingAttempts by androidx.compose.runtime.mutableStateOf<List<ReadingAttempt>>(emptyList())
        private set
    var essays by androidx.compose.runtime.mutableStateOf<List<EssayEntity>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            try {
                examPapers = withContext(Dispatchers.IO) { examStore.loadWithBundledContent() }
                examLoaded = true
            } catch (_: Exception) { examStatus = "本地题库读取失败，已保留原文件；请先导出或联系维护者，不要覆盖导入。" }
        }
        viewModelScope.launch {
            try {
                study.initialize()
                val bundled = withContext(Dispatchers.IO) {
                    getApplication<Application>().assets.open("academic_words.csv").use {
                        VocabularyImport.parse(VocabularyImport.read(it), "项目原创学术词汇练习；释义与例句原创；项目 LICENSE 授权")
                    }
                }
                study.importWords(bundled)
                pronunciations = withContext(Dispatchers.IO) {
                    getApplication<Application>().assets.open("pronunciations.tsv").bufferedReader().useLines { lines ->
                        lines.drop(1).map { it.split('\t', limit = 2) }.filter { it.size == 2 }
                            .associate { it[0] to it[1] }
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { studyStatus = "无法读取本地词库，请重启应用；不要卸载或清除数据。" }
        }
        viewModelScope.launch {
            try { study.words.collectLatest { words = it }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { studyStatus = "无法读取本地词库，请重启应用；不要卸载或清除数据。" }
        }
        viewModelScope.launch { study.wordReviews.collectLatest { wordReviews = it } }
        viewModelScope.launch { study.dailyProgress.collectLatest { dailyProgress = it } }
        viewModelScope.launch { study.schedules.collectLatest { schedules = it.associateBy(WordSchedule::word) } }
        viewModelScope.launch {
            while (true) { refreshStudyDay(); delay(30_000) }
        }
        viewModelScope.launch {
            try { coachRepository.reviews.collectLatest { reviewTurns = it } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { examStatus = "批改记录读取失败，请重启应用重试；不要清除数据。" }
        }
        viewModelScope.launch { dao.readingHistory().collectLatest { readingAttempts = it } }
        viewModelScope.launch { dao.essays().collectLatest { essays = it } }
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
        if (busy || provider != AiProvider.CHATGPT) return
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
        if (!aiReady) {
            models = emptyList()
            chosenModel = ""
        }
    }

    fun refreshModels() {
        if (!aiReady || busy) return
        viewModelScope.launch {
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
        viewModelScope.launch {
            busy = true
            result = ""
            status = "正在请求 ${provider.label}……"
            try {
                val completed = streamAi(prompt) { delta ->
                    withContext(Dispatchers.Main) { result += delta }
                }
                status = "API 测试成功：已收到完整回复。"
                if (saveEssay != null) dao.addEssay(EssayEntity(
                    topic = saveEssay.first, content = saveEssay.second, feedback = completed
                ))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                status = "调用失败：${e.message ?: "未知错误"}（已收到的文本不视为完成）"
            } finally { syncSessionState(); busy = false }
        }
    }

    fun review(word: WordEntity, rating: WordRating) {
        if (savingStudy) return
        savingStudy = true
        viewModelScope.launch {
            try {
                val recorded = study.review(word.word, rating)
                studyStatus = if (recorded) "已保存：${word.word} · ${rating.label}" else
                    "该词已记录或尚未到期，无需重复提交。"
                refreshStudyDay()
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { studyStatus = "未保存：${e.message}"
            } catch (_: Exception) { studyStatus = "学习记录保存失败，请重试；本次不计入完成数量。"
            } finally { savingStudy = false }
        }
    }

    fun refreshStudyClock() { viewModelScope.launch { refreshStudyDay() } }
    fun saveDrafts() { viewModelScope.launch { coach.flush() } }

    fun previewExam(uri: Uri) {
        if (examBusy || !examLoaded) return
        examBusy = true
        examPreview = emptyList()
        viewModelScope.launch {
            try {
                examPreview = withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
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
                    ExamCodec.decode(text).also { examStore.merge(examPapers, it) }
                }
                examStatus = "预览 ${examPreview.size} 套；尚未写入。各专项按资料完整与校对情况开放。"
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { examStatus = "未导入：${e.message}"
            } catch (_: Exception) { examStatus = "文件无法解析，请选择 UTF-8 CET6ExamPack v1 JSON；PDF / Word 请先使用本地转换工具。"
            } finally { examBusy = false }
        }
    }

    fun confirmExamImport() {
        if (examBusy || !examLoaded || examPreview.isEmpty()) return
        val incoming = examPreview
        examBusy = true
        viewModelScope.launch {
            try {
                val merged = withContext(Dispatchers.IO) { examStore.merge(examPapers, incoming).also(examStore::save) }
                examPapers = merged
                examPreview = emptyList()
                examStatus = "已保存 ${incoming.size} 套个人内容；重复导入不增加套卷，既有作答保留。"
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { examStatus = "保存未完成，原题库保留，请重试。"
            } finally { examBusy = false }
        }
    }

    fun exportExam(uri: Uri) {
        if (examBusy || !examLoaded) return
        examBusy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(ExamCodec.encode(examPapers).toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入")
                }
                examStatus = "个人题库已导出；作答历史请另用学习数据备份。"
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { examStatus = "题库导出失败，目标可能留下不完整文件，请重试。"
            } finally { examBusy = false }
        }
    }

    private suspend fun refreshStudyDay() {
        now = System.currentTimeMillis()
        if (plan?.date != today) {
            try { plan = study.plan(LocalDate.parse(today))
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { studyStatus = "每日计划读取失败，请重启应用重试。" }
        }
    }

    fun savePlan(newGoal: Int, reviewGoal: Int) {
        if (savingStudy) return
        savingStudy = true
        viewModelScope.launch {
            try {
                refreshStudyDay()
                val updated = DailyPlan(today, newGoal, reviewGoal)
                study.savePlan(updated)
                plan = updated
                studyStatus = "今日目标已保存，后续日期沿用此目标。"
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { studyStatus = e.message.orEmpty()
            } catch (_: Exception) { studyStatus = "目标保存失败，请重试。"
            } finally { savingStudy = false }
        }
    }

    fun importVocabulary(uri: Uri, source: String) {
        if (savingStudy) return
        savingStudy = true
        studyStatus = "正在校验词库，校验全部通过后才会写入……"
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        VocabularyImport.parse(VocabularyImport.read(it), source)
                    } ?: error("无法读取所选文件")
                }
                val added = study.importWords(imported)
                studyStatus = "导入成功：新增 $added 条，已有 ${imported.size - added} 条已跳过并保留原进度。"
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { studyStatus = "词库未导入：${e.message}"
            } catch (_: Exception) { studyStatus = "词库未导入：请检查 UTF-8 编码、CSV/JSON 格式与文件读取权限。"
            } finally { savingStudy = false }
        }
    }

    fun exportBackup(uri: Uri) {
        if (backupBusy) return
        backupBusy = true
        backupStatus = "正在导出完整学习快照……"
        viewModelScope.launch {
            try {
                check(coach.flush()) { "训练正在处理或草稿保存失败，请稍后重试" }
                val snapshot = backups.snapshot()
                withContext(Dispatchers.IO) {
                    val text = BackupCodec.encode(snapshot)
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(text.toByteArray(Charsets.UTF_8)); output.flush()
                    } ?: error("无法写入所选位置")
                }
                backupStatus = "备份已导出：${snapshot.words.size} 词、${snapshot.reviews.size} 条学习日志、${snapshot.reading.size} 条旧阅读、${snapshot.essays.size} 篇旧作文、${snapshot.coach.size} 条 G3 训练。未包含登录凭证。"
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { backupStatus = "未导出：${e.message}"
            } catch (_: Exception) { backupStatus = "备份写入失败，请换位置重试；所选位置可能留下不完整文件。本机学习数据未改变。"
            } finally { backupBusy = false }
        }
    }

    fun previewBackup(uri: Uri) {
        if (backupBusy) return
        backupBusy = true
        backupPreview = null
        backupStatus = "正在完整校验备份，尚未修改学习数据……"
        viewModelScope.launch {
            try {
                backupPreview = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use(BackupCodec::read)
                        ?: error("无法读取所选文件")
                }
                backupStatus = "备份校验通过，请核对预览后确认合并恢复。"
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { backupStatus = "未恢复：${e.message}"
            } catch (_: Exception) { backupStatus = "备份读取或格式校验失败，未修改本机数据。"
            } finally { backupBusy = false }
        }
    }

    fun cancelBackupRestore() { if (!backupBusy) { backupPreview = null; backupStatus = "已取消恢复，本机数据未改变。" } }

    fun restoreBackup(adoptPlans: Boolean) {
        val incoming = backupPreview ?: return
        if (backupBusy || savingStudy) return
        backupBusy = true
        savingStudy = true
        backupStatus = "正在事务内合并恢复……"
        viewModelScope.launch {
            var committed = false
            try {
                check(coach.flush()) { "训练正在处理或草稿保存失败，请稍后重试" }
                val summary = backups.restore(incoming, adoptPlans)
                committed = true
                backupPreview = null
                refreshStudyDay()
                plan = study.plan(LocalDate.parse(today))
                backupStatus = "恢复完成。${summary.message()}已有词义、来源和学习历史均保留。"
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) {
                backupStatus = if (committed) "数据已恢复，页面刷新失败，请重新打开应用。" else "未恢复：${e.message}"
            } catch (_: Exception) {
                backupStatus = if (committed) "数据已恢复，页面刷新失败，请重新打开应用。" else "恢复未能完成，事务已回滚，请保留备份文件后重试。"
            } finally { backupBusy = false; savingStudy = false }
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
