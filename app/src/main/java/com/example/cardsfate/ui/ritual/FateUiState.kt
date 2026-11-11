package com.example.cardsfate.ui.ritual

import com.example.cardsfate.domain.model.*

data class FateUiState(
    val ritual: RitualState = RitualState(),
    val language: ReadingLanguage = ReadingLanguage.EN,
    val catalog: List<CatalogCard> = emptyList(),
    val historyRecords: List<HistoryRecord> = emptyList(),
    val historyOpen: Boolean = false,
    val historyDetail: HistoryRecord? = null,
    val saveError: String? = null,
    val audioError: String? = null,
    val gazeAvailable: Boolean = false,
    val sceneReady: Boolean = false,
    val spaceReady: Boolean = false,
    val handTrackingReady: Boolean = false,
    val controllerActive: Boolean = false,
    val narrationText: String? = null,
)

/** Presentation-only overlay events; ritual gestures use the domain RitualEvent hierarchy. */
sealed interface FateUiEvent {
    data object ToggleHistory : FateUiEvent
    data class OpenRecord(val record: HistoryRecord?) : FateUiEvent
}
