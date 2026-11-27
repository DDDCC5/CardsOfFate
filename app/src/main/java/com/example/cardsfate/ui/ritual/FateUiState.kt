package com.example.cardsfate.ui.ritual

import com.example.cardsfate.domain.model.*

data class FateUiState(
    val speechPaused:Boolean = false,
    val comfort:ComfortPreference = ComfortPreference(),
    val comfortDraft:ComfortPreference = ComfortPreference(),
    val comfortOpen:Boolean = false,
    val comfortValidation:Boolean? = null,
    val comfortPreview:ComfortPreviewKind = ComfortPreviewKind.UNAVAILABLE,
    val comfortBlock:WorkspaceBlock = WorkspaceBlock.NONE,
    val recenterRequest:Long = 0,
    val comfortCommit:Long = 0,
    val comfortError:String? = null,
    val dailyRecord:DailyRecord? = null,
    val dailyLoadError:String? = null,
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
    val spaceDataReady: Boolean = false,
    val handTrackingReady: Boolean = false,
    val controllerActive: Boolean = false,
    val narrationText: String? = null,
    val isHoldingCard: Boolean = false,
    val focusedReadingIndex: Int? = null,
    val readingDisplayText: String? = null,
)

/** Presentation-only overlay events; ritual gestures use the domain RitualEvent hierarchy. */
sealed interface FateUiEvent {
    data object ToggleHistory : FateUiEvent
    data object BackFromHistory : FateUiEvent
    data class OpenRecord(val record: HistoryRecord?) : FateUiEvent
}
