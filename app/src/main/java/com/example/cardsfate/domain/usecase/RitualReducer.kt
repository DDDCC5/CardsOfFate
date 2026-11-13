package com.example.cardsfate.domain.usecase

import com.example.cardsfate.domain.model.*
import kotlin.random.Random

/** Input adapters dispatch completed gestures; this class never classifies raw tracking samples. */
class RitualReducer(cardIds: List<String> = standardCardIds(), private val random: Random = Random.Default) {
    private val cardIds = cardIds.toList().also {
        require(it.size == 78 && it.distinct().size == 78 && it.none(String::isBlank)) {
            "A ritual requires 78 unique card IDs"
        }
    }

    fun reduce(state: RitualState, event: RitualEvent): RitualState {
        if (event is RitualEvent.SetSuspension) return state.copy(suspension = event.reason)
        if (event == RitualEvent.Reset) return RitualState(
            phase = if (state.safetyAccepted) RitualPhase.SYSTEMS else RitualPhase.LOGO,
            safetyAccepted = state.safetyAccepted,
            sessionNumber = state.sessionNumber + 1,
            suspension = state.suspension,
        )
        if (state.suspension != null && event !is RitualEvent.NarrationCompleted) return state
        return when (event) {
            RitualEvent.LogoFinished -> if (state.phase == RitualPhase.LOGO) state.copy(
                phase = if (state.safetyAccepted) RitualPhase.SYSTEMS else RitualPhase.SAFETY,
            ) else state
            RitualEvent.AcceptSafety -> if (state.phase == RitualPhase.SAFETY) state.copy(
                phase = RitualPhase.SYSTEMS, safetyAccepted = true,
            ) else state
            RitualEvent.SystemsReady -> if (state.safetyAccepted) next(state, RitualPhase.SYSTEMS, RitualPhase.CONTEMPLATE) else state
            RitualEvent.ContemplationFinished -> if (state.phase == RitualPhase.CONTEMPLATE) state.copy(
                phase = RitualPhase.GATHER,
                deck = cardIds.shuffled(random).map { DrawnCard(it, random.nextBoolean()) },
            ) else state
            RitualEvent.GatherFinished -> next(state, RitualPhase.GATHER, RitualPhase.DRAW)
            RitualEvent.DrawFromStack -> if (state.phase == RitualPhase.DRAW &&
                state.deck.size == 78 && state.selectedIndices.size < 3
            ) {
                val selected = state.selectedIndices + state.selectedIndices.size
                state.copy(selectedIndices = selected, phase = if (selected.size == 3) RitualPhase.SPREAD else RitualPhase.DRAW)
            } else state
            RitualEvent.SpreadFinished -> next(state, RitualPhase.SPREAD, RitualPhase.READING)
            is RitualEvent.RevealCard -> if (state.phase == RitualPhase.READING &&
                !state.narrationPlaying && state.revealedCount < 3 &&
                event.index == state.nextRevealIndex && state.revealedCount == event.index
            ) state.copy(revealedCount = event.index + 1, narrationPlaying = true, narrationCompleted = false) else state
            is RitualEvent.NarrationCompleted -> if (state.phase == RitualPhase.READING &&
                state.narrationPlaying && event.index == state.nextRevealIndex && event.sessionNumber == state.sessionNumber
            ) state.copy(
                narrationPlaying = false,
                narrationCompleted = true,
                nextRevealIndex = minOf(2, state.nextRevealIndex + 1),
            ) else state
            is RitualEvent.FinishReading -> if (state.phase == RitualPhase.READING &&
                state.revealedCount == 3 && state.narrationCompleted && !state.narrationPlaying
            ) state.copy(
                phase = RitualPhase.SUMMARY,
                result = RitualResult(state.sessionNumber, state.selectedCards, event.completedAtEpochMillis, event.summary),
            ) else state
            else -> state
        }
    }

    private fun next(state: RitualState, from: RitualPhase, to: RitualPhase): RitualState =
        if (state.phase == from) state.copy(phase = to) else state

}

fun standardCardIds(): List<String> = (0..21).map { "major-${it.toString().padStart(2, '0')}" } +
    listOf("wands", "cups", "swords", "pentacles").flatMap { suit ->
        (1..14).map { "$suit-${it.toString().padStart(2, '0')}" }
    }
