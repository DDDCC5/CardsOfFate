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
            is RitualEvent.StartDaily -> if(state.phase==RitualPhase.SYSTEMS && state.safetyAccepted) state.copy(
                mode=RitualMode.DAILY,dailyCard=event.card,
                phase=if(event.completed) RitualPhase.SUMMARY else RitualPhase.CONTEMPLATE,
                deck=listOf(event.card),selectedIndices=listOf(0),selectedSlots=listOf(1),
                revealedCount=if(event.completed) 1 else 0,
            ) else state
            RitualEvent.SystemsReady -> if (state.safetyAccepted) next(state, RitualPhase.SYSTEMS, RitualPhase.CONTEMPLATE) else state
            RitualEvent.ContemplationFinished -> if (state.phase == RitualPhase.CONTEMPLATE) state.copy(
                phase = if(state.mode==RitualMode.DAILY) RitualPhase.READING else RitualPhase.GATHER,
                deck = if(state.mode==RitualMode.DAILY) listOf(requireNotNull(state.dailyCard)) else cardIds.shuffled(random).map { DrawnCard(it, random.nextBoolean()) },
            ) else state
            RitualEvent.GatherFinished -> next(state, RitualPhase.GATHER, RitualPhase.DRAW)
            RitualEvent.DrawFromStack -> {
                val deckIndex = state.deck.indices.firstOrNull { it !in state.selectedIndices }
                val slot = (0..2).firstOrNull { state.deckIndexForSlot(it) == null }
                if (deckIndex != null && slot != null) selectCard(state, deckIndex, slot) else state
            }
            is RitualEvent.SelectCard -> selectCard(state, event.deckIndex, event.slot)
            is RitualEvent.ChangeDrawPage -> if (state.phase == RitualPhase.DRAW) state.copy(
                drawPage = Math.floorMod(state.drawPage.toLong() + event.delta.toLong(), 6L).toInt(),
            ) else state
            RitualEvent.SpreadFinished -> next(state, RitualPhase.SPREAD, RitualPhase.READING)
            is RitualEvent.RevealCard -> if (state.phase == RitualPhase.READING &&
                !state.narrationPlaying && state.revealedCount < state.targetCount &&
                event.index == state.nextRevealIndex && state.revealedCount == event.index
            ) state.copy(revealedCount = event.index + 1, narrationPlaying = true, narrationCompleted = false) else state
            is RitualEvent.NarrationCompleted -> if (state.phase == RitualPhase.READING &&
                state.narrationPlaying && event.index == state.nextRevealIndex && event.sessionNumber == state.sessionNumber
            ) state.copy(
                narrationPlaying = false,
                narrationCompleted = true,
                nextRevealIndex = minOf(state.targetCount-1, state.nextRevealIndex + 1),
            ) else state
            is RitualEvent.FinishReading -> if (state.phase == RitualPhase.READING &&
                state.revealedCount == state.targetCount && state.narrationCompleted && !state.narrationPlaying
            ) state.copy(
                phase = RitualPhase.SUMMARY,
                result = RitualResult(state.sessionNumber, state.selectedCards, event.completedAtEpochMillis, event.summary),
            ) else state
            else -> state
        }
    }

    private fun selectCard(state: RitualState, deckIndex: Int, slot: Int): RitualState {
        if (state.phase != RitualPhase.DRAW || state.deck.size != 78 ||
            state.selectedIndices.size >= 3 || deckIndex !in 0..77 || slot !in 0..2 ||
            deckIndex in state.selectedIndices || state.deckIndexForSlot(slot) != null
        ) return state
        val slots = state.selectedIndices.map { state.slotForDeckIndex(it)!! } + slot
        val selected = state.selectedIndices + deckIndex
        return state.copy(
            selectedIndices = selected,
            selectedSlots = slots,
            phase = if (selected.size == 3) RitualPhase.SPREAD else RitualPhase.DRAW,
        )
    }

    private fun next(state: RitualState, from: RitualPhase, to: RitualPhase): RitualState =
        if (state.phase == from) state.copy(phase = to) else state

}

fun standardCardIds(): List<String> = (0..21).map { "major-${it.toString().padStart(2, '0')}" } +
    listOf("wands", "cups", "swords", "pentacles").flatMap { suit ->
        (1..14).map { "$suit-${it.toString().padStart(2, '0')}" }
    }
