package com.example.cet6.domain

/** The one place that decides whether a new AI request may start. */
class AppGate(private val ai: AiGateway, private val backup: BackupService) {
    /** A connected model, no other request in flight, and no backup or restore holding the stores. */
    fun canRequestAi(): Boolean {
        val connection = ai.state.value
        val backupState = backup.state.value
        return connection.aiReady && connection.chosenModel.isNotBlank() && !connection.busy &&
            !backupState.busy && !backupState.recoveryRunning
    }
}
