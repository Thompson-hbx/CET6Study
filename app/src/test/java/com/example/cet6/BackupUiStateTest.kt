package com.example.cet6

import com.example.cet6.domain.BackupState
import com.example.cet6.domain.ConversationService
import com.example.cet6.domain.ExamLibraryState
import com.example.cet6.domain.PracticeState
import com.example.cet6.domain.StudyPlanState
import com.example.cet6.ui.BackupUiState
import org.junit.Assert.*
import org.junit.Test

class BackupUiStateTest {
    private fun state(
        backup: BackupState = BackupState(),
        study: StudyPlanState = StudyPlanState(),
        practice: PracticeState = PracticeState(),
        conversation: ConversationService.State = ConversationService.State(),
        exams: ExamLibraryState = ExamLibraryState(),
    ) = BackupUiState.from(backup, study, practice, conversation, exams)

    @Test fun idleAllowsEveryAction() {
        val idle = state()
        assertTrue(idle.canExport)
        assertTrue(idle.canPreview)
        assertTrue(idle.canMerge)
    }

    @Test fun runningAiBlocksExportAndMergeButNotPreview() {
        listOf(
            state(practice = PracticeState(working = true)),
            state(conversation = ConversationService.State(pending = ConversationService.Pending("p", "a", "chat", "q"))),
        ).forEach {
            assertFalse(it.canExport)
            assertFalse(it.canMerge)
            assertTrue(it.canPreview)
        }
    }

    @Test fun savingStudyBlocksOnlyMerge() {
        val saving = state(study = StudyPlanState(saving = true))
        assertTrue(saving.canExport)
        assertFalse(saving.canMerge)
    }

    @Test fun busyExamBankBlocksExportAndMerge() {
        val busy = state(exams = ExamLibraryState(busy = true))
        assertFalse(busy.canExport)
        assertFalse(busy.canMerge)
        assertTrue(busy.canPreview)
    }

    @Test fun pendingRestoreBlocksNewPreviewButAllowsResume() {
        val pending = state(backup = BackupState(restorePending = true))
        assertFalse(pending.canPreview)
        assertTrue(pending.canMerge)
    }

    @Test fun busyBackupBlocksEverything() {
        val busy = state(backup = BackupState(busy = true))
        assertFalse(busy.canExport)
        assertFalse(busy.canPreview)
        assertFalse(busy.canMerge)
    }
}
