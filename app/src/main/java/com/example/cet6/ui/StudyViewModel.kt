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
    private val db = StudyDatabase.get(app)
    private val dao = db.dao()
    private val study = StudyRepository(db)
    private val backups = BackupRepository(db)
    val coach = CoachState(CoachRepository(db), viewModelScope,
        canRequest = { hasPlan && chosenModel.isNotBlank() && !busy },
        model = { chosenModel },
        request = { prompt, onDelta ->
            busy = true
            try {
                ai.stream(chosenModel, prompt) { delta -> withContext(Dispatchers.Main) { onDelta(delta) } }
            } finally { syncSessionState(); busy = false }
        })
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
            try {
                examPapers = withContext(Dispatchers.IO) { examStore.load() }
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
                examStatus = "预览 ${examPreview.size} 套；尚未写入。待校对内容不能开始训练。"
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
