package com.example.cet6.ui

import androidx.compose.runtime.getValue
import com.example.cet6.data.*
import com.example.cet6.domain.AiRequest
import com.example.cet6.domain.PracticeService
import kotlinx.coroutines.CoroutineScope

/** Compose view of [PracticeService] for screens that read ViewModel properties. */
class CoachState internal constructor(val service: PracticeService) {
    constructor(repository: CoachRepository, scope: CoroutineScope, canRequest: () -> Boolean, model: () -> String,
        request: AiRequest) : this(PracticeService(repository, scope, canRequest, model, null, null, request))

    private val ui by service.state.composeState()
    val entries: Map<CoachKind, CoachPractice> get() = ui.entries
    val messages: Map<CoachKind, String> get() = ui.messages
    val history: List<CoachPractice> get() = ui.history
    val working: Boolean get() = ui.working
    val partial: String get() = ui.partial
    val activeRequest: String? get() = ui.activeRequest
    val partialEntryId: String? get() = ui.partialEntryId
    val partialReadingKey: String? get() = ui.partialReadingKey
    val shareStatus: String get() = ui.shareStatus
    val importedReadingId: String? get() = ui.importedReadingId

    fun shareFailed(message: String) = service.shareFailed(message)
    fun consumeReadingImport(id: String) = service.consumeReadingImport(id)
    fun importReading(input: SharedReading) = service.importReading(input)
    fun edit(kind: CoachKind, transform: (CoachPractice) -> CoachPractice): Boolean = service.edit(kind, transform)
    fun addReadingQuestion(text: String): Boolean = service.addReadingQuestion(text)
    fun save(kind: CoachKind) = service.save(kind)
    fun startExam(practice: CoachPractice, onReady: (CoachPractice) -> Unit = {}) = service.startExam(practice, onReady)
    suspend fun flush(): Boolean = service.flush()
    fun open(kind: CoachKind, saved: CoachPractice? = null, retry: Boolean = false, onReady: (CoachPractice) -> Unit = {}) =
        service.open(kind, saved, retry, onReady)
    fun revise(source: CoachPractice, answer: String, onReady: (CoachPractice) -> Unit = {}) = service.revise(source, answer, onReady)
    fun startCustom(kind: CoachKind, title: String, material: String, onReady: (CoachPractice) -> Unit = {}) =
        service.startCustom(kind, title, material, onReady)
    fun generate(kind: CoachKind) = service.generate(kind)
    fun readingHint(number: Int, level: Int, followUp: String = "") = service.readingHint(number, level, followUp)
    fun readingDiagnosis(number: Int, followUp: String = "") = service.readingDiagnosis(number, followUp)
    fun submit(kind: CoachKind, explain: Boolean, followUp: String = "", focus: WritingFocus = WritingFocus.COMPLETE) =
        service.submit(kind, explain, followUp, focus)
}
