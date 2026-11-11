package com.example.cardsfate.domain.model

enum class RitualPhase { LOGO, SAFETY, SYSTEMS, CONTEMPLATE, GATHER, SHUFFLE, INTERLACE, DRAW, SPREAD, READING, SUMMARY }
enum class SuspensionReason { CONTROLLER_ACTIVE, HAND_TRACKING_LOST, SPACE_UNAVAILABLE }
enum class ReadingLanguage { ZH, EN }

data class DrawnCard(val cardId: String, val reversed: Boolean)
data class RitualResult(val sessionNumber: Long, val cards: List<DrawnCard>, val completedAtEpochMillis: Long, val summary: String)

/** Keep this state in the process owner; do not restore safetyAccepted after process death. */
data class RitualState(
    val phase: RitualPhase = RitualPhase.LOGO,
    val safetyAccepted: Boolean = false,
    val sessionNumber: Long = 0,
    val deck: List<DrawnCard> = emptyList(),
    val selectedIndices: List<Int> = emptyList(),
    val interlaceCount: Int = 0,
    val nextRevealIndex: Int = 0,
    val revealedCount: Int = 0,
    val narrationPlaying: Boolean = false,
    val narrationCompleted: Boolean = false,
    val suspension: SuspensionReason? = null,
    val result: RitualResult? = null,
) {
    val selectedCards: List<DrawnCard> get() = selectedIndices.map { deck[it] }
}

sealed interface RitualEvent {
    data object LogoFinished : RitualEvent
    data object AcceptSafety : RitualEvent
    data object SystemsReady : RitualEvent
    data object ContemplationFinished : RitualEvent
    data object GatherFinished : RitualEvent
    data object ShuffleFinished : RitualEvent
    data object InterlaceCompleted : RitualEvent
    data class ToggleCard(val deckIndex: Int) : RitualEvent
    data object ConfirmDraw : RitualEvent
    data object SpreadFinished : RitualEvent
    data class RevealCard(val index: Int) : RitualEvent
    data class NarrationCompleted(val index: Int, val sessionNumber: Long) : RitualEvent
    data class FinishReading(val completedAtEpochMillis: Long, val summary: String) : RitualEvent
    data class SetSuspension(val reason: SuspensionReason?) : RitualEvent
    data object Reset : RitualEvent
}

data class HistoryRecord(
    val id: String,
    val completedAtEpochMillis: Long,
    val cards: List<DrawnCard>,
    val summary: String,
    val language: ReadingLanguage,
)
