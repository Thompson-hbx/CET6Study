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
import com.example.cet6.domain.StudyServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Exposes the services to the current screens as Compose-observable properties; holds no business rules itself. */
class StudyViewModel(app: Application) : AndroidViewModel(app) {
    private val services = StudyServices(app, viewModelScope)
    private val dao = services.db.dao()
    private val prefs = app.getSharedPreferences("study_ui", android.content.Context.MODE_PRIVATE)
    private val aiConnection = AiConnectionState(services.ai)
    /** For screen ViewModels that live in the same ViewModelStore as this one. */
    internal val ai get() = services.ai
    private val studyUi by services.study.state.composeState()
    private val examUi by services.exams.state.composeState()
    private val backupUi by services.backup.state.composeState()

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
    val coach = CoachState(services.practice)
    /** Saved explanations and follow-ups; independent of the practice rows above. */
    val talk = CoachTalk(services.conversation)
    var reviewTurns by androidx.compose.runtime.mutableStateOf<List<CoachTurn>>(emptyList())
        private set
    var lastTab: String
        get() = prefs.getString("tab", "writing") ?: "writing"
        set(value) { prefs.edit().putString("tab", value).apply() }

    val backupRestorePending: Boolean get() = backupUi.restorePending
    val backupRecoveryRunning: Boolean get() = backupUi.recoveryRunning
    val backupBusy: Boolean get() = backupUi.busy
    val backupStatus: String get() = backupUi.status
    val backupPreview: StudyBackup? get() = backupUi.preview
    val backupPaperPreview: List<ExamPaper>? get() = backupUi.paperPreview

    val examPapers: List<ExamPaper> get() = examUi.papers
    val examPreview: List<ExamPaper> get() = examUi.preview
    val examStatus: String get() = examUi.status
    val examBusy: Boolean get() = examUi.busy

    val pronunciations: Map<String, String> get() = studyUi.pronunciations
    val now: Long get() = studyUi.now
    val plan: DailyPlan? get() = studyUi.plan
    val wordReviews: List<WordReview> get() = studyUi.wordReviews
    val dailyProgress: List<DailyWordProgress> get() = studyUi.dailyProgress
    val studyStatus: String get() = studyUi.status
    val savingStudy: Boolean get() = studyUi.saving
    val schedules: Map<String, WordSchedule> get() = studyUi.schedules
    val today: String get() = studyUi.today
    val todayProgress: DailyWordProgress? get() = dailyProgress.find { it.date == today }
    val words: List<WordEntity> get() = studyUi.words

    var readingAttempts by androidx.compose.runtime.mutableStateOf<List<ReadingAttempt>>(emptyList())
        private set
    var essays by androidx.compose.runtime.mutableStateOf<List<EssayEntity>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            try { services.coachRepository.reviews.collectLatest { reviewTurns = it } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { services.exams.setStatus("批改记录读取失败，请重启应用重试；不要清除数据。") }
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

    fun review(word: WordEntity, rating: WordRating) = services.study.review(word, rating)
    fun refreshStudyClock() = services.study.refreshClock()
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

    fun previewExam(uri: Uri) = services.exams.preview(uri)
    fun confirmExamImport() = services.exams.confirmImport()
    fun exportExam(uri: Uri) = services.exams.export(uri)

    fun savePlan(newGoal: Int, reviewGoal: Int) = services.study.savePlan(newGoal, reviewGoal)
    fun importVocabulary(uri: Uri, source: String) = services.study.importVocabulary(uri, source)
    suspend fun savePracticeWord(entry: CoachPractice, word: String, meaning: String, quote: String): String =
        services.study.savePracticeWord(entry, word, meaning, quote)

    fun exportBackup(uri: Uri) = services.backup.export(uri)
    fun previewBackup(uri: Uri) = services.backup.preview(uri)
    fun cancelBackupRestore() = services.backup.cancelRestore()
    fun restoreBackup(adoptPlans: Boolean) = services.backup.restore(adoptPlans)
    fun resumeBackupRestore() = services.backup.resume()

    fun recordReading(choice: String) {
        viewModelScope.launch {
            dao.addReadingAttempt(ReadingAttempt(
                questionId = "reading_demo_1", answer = choice,
                correct = choice == "B"
            ))
        }
    }
}
