package com.example.cet6.domain

import android.app.Application
import com.example.cet6.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Builds and wires the services. It lives as long as [scope] (today the ViewModel), so a new
 * ViewModel still starts from disk exactly as before, including resuming a journaled restore.
 */
class StudyServices(app: Application, scope: CoroutineScope) {
    val db: StudyDatabase = StudyDatabase.get(app)
    val coachRepository = CoachRepository(db)
    private val examStore = ExamStore(app)
    private val restoreJournal = RestoreJournal(app)
    /** Completes once a pending restore has finished and the exam bank was read; drafts and words load after it. */
    private val startupReady = CompletableDeferred<Unit>()
    private val restorePendingAtStart = restoreJournal.hasPending()

    val ai: AiGateway = AiGateway(app, scope) { essay, feedback ->
        db.dao().addEssay(EssayEntity(topic = essay.first, content = essay.second, feedback = feedback))
    }
    val exams: ExamLibrary = ExamLibrary(app, examStore, scope)
    val study: StudyPlanService = StudyPlanService(app, StudyRepository(db), scope, startupReady) { backup.state.value.busy }
    val practice: PracticeService = PracticeService(coachRepository, scope,
        canRequest = { gate.canRequestAi() },
        model = { ai.state.value.chosenModel },
        startupReady = startupReady.takeIf { restorePendingAtStart },
        reviewRequest = { prompt, onDelta -> ai.stream(prompt, deepReview = true, onDelta = onDelta) },
        request = { prompt, onDelta -> ai.stream(prompt, onDelta = onDelta) })
    /** Saved explanations and follow-ups; independent of the practice rows above. */
    val conversation: ConversationService = ConversationService(coachRepository, scope,
        canRequest = { gate.canRequestAi() },
        model = { ai.state.value.chosenModel },
        reviewRequest = { prompt, onDelta -> ai.stream(prompt, deepReview = true, onDelta = onDelta) },
        request = { prompt, onDelta -> ai.stream(prompt, onDelta = onDelta) })
    private val backups = BackupRepository(db)
    val backup: BackupService = BackupService(app, backups,
        CompleteRestoreRepository(backups, examStore, restoreJournal), restoreJournal, study, exams, scope,
        practiceBusy = { conversation.state.value.pending != null || practice.state.value.working },
        flushDrafts = practice::flush)
    val gate: AppGate = AppGate(ai, backup)

    init {
        scope.launch {
            try {
                if (restorePendingAtStart) backup.resumeAtStartup()
                exams.load(backup.state.value.restorePending)
            } finally { startupReady.complete(Unit) }
        }
    }
}
