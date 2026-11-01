package com.example.cet6.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cet6.domain.BackupState
import com.example.cet6.domain.ConversationService
import com.example.cet6.domain.ExamLibraryState
import com.example.cet6.domain.PracticeState
import com.example.cet6.domain.StudyPlanState
import com.example.cet6.domain.StudyServices
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the backup screen shows; the enable rules live here so they can be checked without a device. */
data class BackupUiState(
    val backup: BackupState,
    val today: String,
    val wordCount: Int,
    val practiceCount: Int,
    val paperCount: Int,
    val canExport: Boolean,
    val canPreview: Boolean,
    /** Resuming or confirming a restore writes learning data, so nothing else may be saving or requesting. */
    val canMerge: Boolean,
) {
    companion object {
        fun from(
            backup: BackupState,
            study: StudyPlanState,
            practice: PracticeState,
            conversation: ConversationService.State,
            exams: ExamLibraryState,
        ): BackupUiState {
            val coachIdle = !practice.working && conversation.pending == null
            return BackupUiState(
                backup = backup,
                today = study.today,
                wordCount = study.words.size,
                practiceCount = practice.history.size,
                paperCount = exams.papers.size,
                canExport = !backup.busy && !exams.busy && coachIdle,
                canPreview = !backup.busy && !backup.restorePending,
                canMerge = !backup.busy && !study.saving && !exams.busy && coachIdle,
            )
        }
    }
}

sealed interface BackupIntent {
    data class Export(val uri: Uri) : BackupIntent
    data class Preview(val uri: Uri) : BackupIntent
    data class Restore(val adoptPlans: Boolean) : BackupIntent
    data object Resume : BackupIntent
    data object CancelRestore : BackupIntent
}

/** Backup export and restore. [services] belong to the activity's [StudyViewModel] in the same ViewModelStore. */
class BackupViewModel(private val services: StudyServices) : ViewModel() {
    private fun current() = BackupUiState.from(
        services.backup.state.value,
        services.study.state.value,
        services.practice.state.value,
        services.conversation.state.value,
        services.exams.state.value,
    )

    val state: StateFlow<BackupUiState> = combine(
        services.backup.state.flow,
        services.study.state.flow,
        services.practice.state.flow,
        services.conversation.state.flow,
        services.exams.state.flow,
        BackupUiState::from,
    )
        .stateIn(viewModelScope, SharingStarted.Eagerly, current())

    fun onIntent(intent: BackupIntent) {
        when (intent) {
            is BackupIntent.Export -> services.backup.export(intent.uri)
            is BackupIntent.Preview -> services.backup.preview(intent.uri)
            is BackupIntent.Restore -> services.backup.restore(intent.adoptPlans)
            BackupIntent.Resume -> services.backup.resume()
            BackupIntent.CancelRestore -> services.backup.cancelRestore()
        }
    }
}
