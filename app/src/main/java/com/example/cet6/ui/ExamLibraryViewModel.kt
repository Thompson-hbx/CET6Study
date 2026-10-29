package com.example.cet6.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.example.cet6.domain.ExamLibrary
import com.example.cet6.domain.ExamLibraryState
import kotlinx.coroutines.flow.StateFlow

sealed interface ExamLibraryIntent {
    data class Preview(val uri: Uri) : ExamLibraryIntent
    data object ConfirmImport : ExamLibraryIntent
    data class Export(val uri: Uri) : ExamLibraryIntent
}

/** Personal exam bank management. [exams] belongs to the activity's [StudyViewModel] in the same ViewModelStore. */
class ExamLibraryViewModel(private val exams: ExamLibrary) : ViewModel() {
    val state: StateFlow<ExamLibraryState> = exams.state.flow

    fun onIntent(intent: ExamLibraryIntent) {
        when (intent) {
            is ExamLibraryIntent.Preview -> exams.preview(intent.uri)
            ExamLibraryIntent.ConfirmImport -> exams.confirmImport()
            is ExamLibraryIntent.Export -> exams.export(intent.uri)
        }
    }
}
