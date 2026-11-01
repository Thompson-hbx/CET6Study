package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.cet6.data.*
import com.example.cet6.ui.components.*

fun latestBands(reviews: List<CoachTurn>): Map<String, Int> = reviews
    .filter { it.kind == TurnKind.WRITING_REVIEW || it.kind == TurnKind.TRANSLATION_REVIEW }
    .groupBy { it.practiceId }
    .mapNotNull { (id, items) -> CoachReview.parse(items.last().content).score?.band?.takeIf { it > 0 }?.let { id to it } }.toMap()
