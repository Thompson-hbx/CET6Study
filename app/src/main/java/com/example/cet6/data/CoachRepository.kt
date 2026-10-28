package com.example.cet6.data

import androidx.room.withTransaction

class CoachRepository(private val db: StudyDatabase) {
    val history = db.dao().coachHistory()
    val reviews = db.dao().reviewTurns()
    val turnCounts = db.dao().turnCounts()
    fun turns(practiceId: String) = db.dao().turns(practiceId)
    suspend fun draft(kind: CoachKind) = db.dao().coachDraft(kind.name)
    suspend fun get(id: String) = db.dao().coachById(id)
    suspend fun save(entry: CoachPractice) {
        CoachPrompts.validate(entry)
        db.dao().saveCoach(entry)
    }
    suspend fun saveTurn(turn: CoachTurn) {
        CoachTurns.validate(turn)
        db.withTransaction {
            val entry = requireNotNull(db.dao().coachById(turn.practiceId)) { "对应练习不存在" }
            db.dao().saveTurn(turn.copy(locatedQuote = ReadingLocation.extract(entry, turn)))
        }
    }

    suspend fun saveExchange(question: CoachTurn?, reply: CoachTurn) {
        CoachTurns.validate(reply)
        require(reply.role == CoachTurn.ASSISTANT) { "回复角色无效" }
        question?.let {
            CoachTurns.validate(it)
            require(it.role == CoachTurn.USER && it.kind == TurnKind.CHAT && it.id != reply.id &&
                it.practiceId == reply.practiceId && it.anchor == reply.anchor) { "问答关联无效" }
        }
        db.withTransaction {
            val entry = requireNotNull(db.dao().coachById(reply.practiceId)) { "对应练习不存在，问答未保存" }
            db.dao().addTurns(listOfNotNull(question?.copy(locatedQuote = ""),
                reply.copy(locatedQuote = ReadingLocation.extract(entry, reply))))
        }
    }

    /** Apply one intent to the current database state; callers may hold an old UI snapshot. */
    suspend fun updateDecision(turnId: String, issueId: Int, decision: ReviewDecision) = db.withTransaction {
        val turn = requireNotNull(db.dao().turnById(turnId)) { "批改记录不存在" }
        require(turn.role == CoachTurn.ASSISTANT &&
            turn.kind in setOf(TurnKind.WRITING_REVIEW, TurnKind.TRANSLATION_REVIEW)) { "不是可批改的记录" }
        val review = CoachReview.parse(turn.content)
        require(review.structured) { "批改结构未通过校验，原回复已保留" }
        require(review.issues.any { it.id == issueId }) { "批改建议不存在" }
        val current = ReviewDecisions.decodeForUpdate(turn.state, review.issues.map { it.id }.toSet())
        val next = when (decision) {
            ReviewDecision.ACCEPT -> current.accept(issueId)
            ReviewDecision.DISMISS -> current.dismiss(issueId)
            ReviewDecision.RESET -> current.reset(issueId)
        }.encode()
        require(next.length <= CoachTurns.MAX_STATE) { "批改状态过长" }
        check(db.dao().updateTurnState(turnId, next) == 1) { "批改状态未保存" }
    }
}
