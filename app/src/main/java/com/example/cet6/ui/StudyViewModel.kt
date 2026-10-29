package com.example.cet6.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.example.cet6.ai.AvailableModel
import com.example.cet6.ai.AiProvider
import com.example.cet6.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant

class StudyViewModel(app: Application) : AndroidViewModel(app) {
    private val db = StudyDatabase.get(app)
    private val dao = db.dao()
    private val study = StudyRepository(db)
    private val backups = BackupRepository(db)
    private val examStore = ExamStore(app)
    private val restoreJournal = RestoreJournal(app)
    private val completeRestores = CompleteRestoreRepository(backups, examStore, restoreJournal)
    private val restoreStartupReady = CompletableDeferred<Unit>()
    var backupRestorePending by androidx.compose.runtime.mutableStateOf(restoreJournal.hasPending())
        private set
    var backupRecoveryRunning by androidx.compose.runtime.mutableStateOf(backupRestorePending)
        private set
    private val coachRepository = CoachRepository(db)
    private val prefs = app.getSharedPreferences("study_ui", android.content.Context.MODE_PRIVATE)
    private val aiConnection = AiConnectionState(app, viewModelScope) { essay, feedback ->
        dao.addEssay(EssayEntity(topic = essay.first, content = essay.second, feedback = feedback))
    }
    val provider: AiProvider get() = aiConnection.provider
    val apiBaseUrl: String get() = aiConnection.apiBaseUrl
    val hasApiKey: Boolean get() = aiConnection.hasApiKey
    val aiReady: Boolean get() = aiConnection.aiReady
    val sessionEmail: String get() = aiConnection.sessionEmail
    val hasPlan: Boolean get() = aiConnection.hasPlan
    val models: List<AvailableModel> get() = aiConnection.models
    val chosenModel: String get() = aiConnection.chosenModel
    val deepReviewEnabled: Boolean get() = aiConnection.deepReviewEnabled
    val reviewEffort get() = aiConnection.reviewEffort
    val reviewFormat get() = aiConnection.reviewFormat
    val busy: Boolean get() = aiConnection.busy
    val status: String get() = aiConnection.status
    val result: String get() = aiConnection.result

    internal suspend fun streamCoach(prompt: String, onDelta: suspend (String) -> Unit): String =
        aiConnection.streamCoach(prompt, onDelta = onDelta)
    private suspend fun streamReview(prompt: String, onDelta: suspend (String) -> Unit): String =
        aiConnection.streamCoach(prompt, deepReview = true, onDelta = onDelta)
    val coach = CoachState(coachRepository, viewModelScope,
        canRequest = { aiReady && chosenModel.isNotBlank() && !busy && !backupBusy && !backupRecoveryRunning },
        model = { chosenModel },
        startupReady = restoreStartupReady.takeIf { backupRestorePending },
        reviewRequest = ::streamReview,
        request = ::streamCoach)
    /** Saved explanations and follow-ups; independent of the practice rows above. */
    val talk = CoachTalk(coachRepository, viewModelScope,
        canRequest = { aiReady && chosenModel.isNotBlank() && !busy && !backupBusy && !backupRecoveryRunning },
        model = { chosenModel },
        reviewRequest = ::streamReview,
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
    var backupPaperPreview by androidx.compose.runtime.mutableStateOf<List<ExamPaper>?>(null)
        private set
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

    var words by androidx.compose.runtime.mutableStateOf<List<WordEntity>>(emptyList())
        private set
    var readingAttempts by androidx.compose.runtime.mutableStateOf<List<ReadingAttempt>>(emptyList())
        private set
    var essays by androidx.compose.runtime.mutableStateOf<List<EssayEntity>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            try {
                if (backupRestorePending) performBackupRestore(null, false, flushDrafts = false)
                examPapers = withContext(Dispatchers.IO) {
                    if (backupRestorePending) examStore.load() else examStore.loadWithBundledContent()
                }
                examLoaded = true
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { examStatus = "本地题库读取失败，已保留原文件；请先导出或联系维护者，不要覆盖导入。"
            } finally { restoreStartupReady.complete(Unit) }
        }
        viewModelScope.launch {
            restoreStartupReady.await()
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
            restoreStartupReady.await()
            while (true) { refreshStudyDay(); delay(30_000) }
        }
        viewModelScope.launch {
            try { coachRepository.reviews.collectLatest { reviewTurns = it } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { examStatus = "批改记录读取失败，请重启应用重试；不要清除数据。" }
        }
        viewModelScope.launch { dao.readingHistory().collectLatest { readingAttempts = it } }
        viewModelScope.launch { dao.essays().collectLatest { essays = it } }
    }

    fun setDeepReview(enabled: Boolean) = aiConnection.setDeepReview(enabled)
    fun selectReviewEffort(value: com.example.cet6.ai.ReasoningEffort) = aiConnection.selectReviewEffort(value)
    fun selectReviewFormat(value: com.example.cet6.ai.ReasoningFormat) = aiConnection.selectReviewFormat(value)
    fun selectModel(slug: String) = aiConnection.selectModel(slug)
    fun selectProvider(value: AiProvider) = aiConnection.selectProvider(value)
    fun saveApiConnection(baseUrl: String, key: String): Boolean = aiConnection.saveApiConnection(baseUrl, key)
    fun clearApiConnection() = aiConnection.clearApiConnection()
    fun signIn() = aiConnection.signIn()
    fun signOut() = aiConnection.signOut()
    fun refreshModels() = aiConnection.refreshModels()
    fun ask(prompt: String, saveEssay: Pair<String, String>? = null) = aiConnection.ask(prompt, saveEssay)

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

    fun refreshStudyClock() { viewModelScope.launch { restoreStartupReady.await(); refreshStudyDay() } }
    fun readingPosition(id: String): Pair<Int, Int>? {
        val parts = prefs.getString("reading_position_$id", null)?.split(':') ?: return null
        if (parts.size != 2) return null
        val tab = parts[0].toIntOrNull() ?: return null
        val question = parts[1].toIntOrNull() ?: return null
        return (tab to question).takeIf { tab in 0..2 && question in 1..9999 }
    }

    fun rememberReadingPosition(id: String, tab: Int, question: Int) {
        prefs.edit().putString("reading_position_$id", "$tab:$question").apply()
    }

    fun saveDrafts() { if (!backupRecoveryRunning) viewModelScope.launch { coach.flush() } }

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

    suspend fun savePracticeWord(entry: CoachPractice, word: String, meaning: String, quote: String): String {
        check(!savingStudy && !backupBusy) { "正在保存，请稍后重试" }
        val candidate = PracticeVocabulary.word(entry, word, meaning, quote)
        savingStudy = true
        return try {
            if (study.importWords(listOf(candidate)) == 1) "已加入新词复习，来源练习与原句已保留。"
            else "词条已存在，原释义与复习进度已保留。"
        } finally { savingStudy = false }
    }

    fun exportBackup(uri: Uri) {
        if (backupBusy || examBusy || talk.busy || coach.working) return
        if (!examLoaded) {
            backupStatus = "个人题库尚未读取成功，未导出完整备份；请先在题库管理核对读取错误，不要覆盖原文件。"
            return
        }
        backupBusy = true
        examBusy = true
        backupStatus = "正在导出学习记录与个人题库……"
        viewModelScope.launch {
            try {
                check(coach.flush()) { "训练正在处理或草稿保存失败，请稍后重试" }
                val snapshot = backups.snapshot()
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        CompleteBackupCodec.write(CompleteBackup(snapshot, examPapers), output)
                    } ?: error("无法写入所选位置")
                }
                backupStatus = "备份已导出：${snapshot.words.size} 词、${snapshot.reviews.size} 条学习日志、${snapshot.reading.size} 条旧阅读、${snapshot.essays.size} 篇旧作文、${snapshot.coach.size} 条训练、${snapshot.turns.size} 条对话、${examPapers.size} 套题库。未包含登录凭证或原卷 PDF。"
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { backupStatus = "未导出：${e.message}"
            } catch (_: Exception) { backupStatus = "备份写入失败，请换位置重试；所选位置可能留下不完整文件。本机学习数据未改变。"
            } finally { backupBusy = false; examBusy = false }
        }
    }

    fun previewBackup(uri: Uri) {
        if (backupBusy) return
        if (backupRestorePending) {
            backupStatus = "上次恢复尚未完成，请先重试；本机已保存的内容仍可读取。"
            return
        }
        backupBusy = true
        backupPreview = null
        backupPaperPreview = null
        backupStatus = "正在完整校验备份，尚未修改学习数据……"
        viewModelScope.launch {
            try {
                val incoming = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use(CompleteBackupCodec::read)
                        ?: error("无法读取所选文件")
                }
                backupPreview = incoming.study
                backupPaperPreview = incoming.papers
                backupStatus = if (incoming.papers == null) "旧版学习 JSON 校验通过；未包含个人题库，请另行导入题库。"
                    else "完整性校验通过：学习记录与个人题库均可恢复，请核对预览。"
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { backupStatus = "未恢复：${e.message}"
            } catch (_: Exception) { backupStatus = "备份读取或格式校验失败，未修改本机数据。"
            } finally { backupBusy = false }
        }
    }

    fun cancelBackupRestore() { if (!backupBusy) { backupPreview = null; backupPaperPreview = null; backupStatus = "已关闭恢复预览。" } }

    fun restoreBackup(adoptPlans: Boolean) {
        val incoming = backupPreview ?: return
        if (backupBusy || savingStudy || examBusy || talk.busy || coach.working) return
        if (backupRestorePending) {
            backupStatus = "上次恢复尚未完成，请先重试。"
            return
        }
        if (backupPaperPreview != null && !examLoaded) {
            backupStatus = "个人题库尚未读取成功，未恢复任何内容；请先核对本机题库读取错误。"
            return
        }
        val backup = CompleteBackup(incoming, backupPaperPreview)
        backupBusy = true
        viewModelScope.launch { performBackupRestore(backup, adoptPlans) }
    }

    fun resumeBackupRestore() {
        if (!backupRestorePending || backupBusy || savingStudy || examBusy || talk.busy || coach.working) return
        backupBusy = true
        viewModelScope.launch { performBackupRestore(null, false) }
    }

    private suspend fun performBackupRestore(incoming: CompleteBackup?, adoptPlans: Boolean, flushDrafts: Boolean = true) {
        backupBusy = true
        examBusy = true
        savingStudy = true
        backupRecoveryRunning = true
        backupStatus = if (incoming == null) "正在继续上次恢复……" else "正在合并恢复学习记录与题库……"
        var restoredStores = false
        try {
            if (flushDrafts) check(coach.flush()) { "训练正在处理或草稿保存失败，请稍后重试" }
            val restored = withContext(Dispatchers.IO) {
                if (incoming == null) completeRestores.resume() else completeRestores.restore(incoming, adoptPlans)
            }
            restoredStores = true
            restored?.papers?.let { examPapers = it; examLoaded = true }
            backupPreview = null
            backupPaperPreview = null
            refreshStudyDay()
            plan = study.plan(LocalDate.parse(today))
            backupStatus = "恢复完成。${restored?.summary?.message().orEmpty()}已有词义、来源和学习历史均保留。"
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (restoreJournal.hasPending()) { backupPreview = null; backupPaperPreview = null }
            backupStatus = if (restoredStores) "数据恢复已完成，页面刷新失败，请重新打开应用。"
            else if (restoreJournal.hasPending())
                "恢复尚未完成，本机已保留恢复任务；部分内容可能已写入。可继续读取离线记录，在此重试或重启后继续；冲突不会覆盖本机记录。"
            else if (e is IllegalArgumentException) "未恢复：${e.message}"
            else "恢复未能完成，请保留原备份后重试；未创建新的恢复任务。"
        } finally {
            backupRestorePending = restoreJournal.hasPending()
            backupRecoveryRunning = false
            backupBusy = false
            examBusy = false
            savingStudy = false
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
