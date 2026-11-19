package com.example.cardsfate.domain

import com.example.cardsfate.domain.model.*
import com.example.cardsfate.domain.usecase.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class RitualReducerTest {
    private val reducer = RitualReducer(random = Random(42))
    private fun advance(vararg events: RitualEvent): RitualState = events.fold(RitualState(), reducer::reduce)
    private fun drawState(): RitualState = advance(
        RitualEvent.LogoFinished, RitualEvent.AcceptSafety, RitualEvent.SystemsReady,
        RitualEvent.ContemplationFinished, RitualEvent.GatherFinished,
    )
    private fun readingState(): RitualState = listOf(
        RitualEvent.DrawFromStack, RitualEvent.DrawFromStack, RitualEvent.DrawFromStack, RitualEvent.SpreadFinished,
    ).fold(drawState(), reducer::reduce)

    @Test fun safetyCannotBeSkippedAndConsentSurvivesOnlyWarmReset() {
        val fresh = RitualState()
        assertEquals(fresh, reducer.reduce(fresh, RitualEvent.SystemsReady))
        val safety = reducer.reduce(fresh, RitualEvent.LogoFinished)
        assertEquals(RitualPhase.SAFETY, safety.phase)
        assertEquals(safety, reducer.reduce(safety, RitualEvent.SystemsReady))
        val accepted = reducer.reduce(safety, RitualEvent.AcceptSafety)
        assertTrue(accepted.safetyAccepted)
        assertEquals(RitualPhase.SYSTEMS, accepted.phase)
        val reset = reducer.reduce(accepted, RitualEvent.Reset)
        assertEquals(RitualPhase.SYSTEMS, reset.phase)
        assertTrue(reset.safetyAccepted)
        assertFalse(RitualState().safetyAccepted)
    }

    @Test fun automaticGatherDirectlyEnablesDrawingAndEarlyTapsAreIgnored() {
        val contemplating=advance(RitualEvent.LogoFinished,RitualEvent.AcceptSafety,RitualEvent.SystemsReady)
        assertEquals(contemplating,reducer.reduce(contemplating,RitualEvent.DrawFromStack))
        val gathering=reducer.reduce(contemplating,RitualEvent.ContemplationFinished)
        assertEquals(RitualPhase.GATHER,gathering.phase)
        assertEquals(78,gathering.deck.size)
        assertEquals(gathering,reducer.reduce(gathering,RitualEvent.DrawFromStack))
        val draw=reducer.reduce(gathering,RitualEvent.GatherFinished)
        assertEquals(RitualPhase.DRAW,draw.phase)
        assertTrue(draw.selectedCards.isEmpty())
    }

    @Test fun eachStackTapDrawsOneUniqueCardAndThirdTapStartsSpread() {
        val draw=drawState()
        val one=reducer.reduce(draw,RitualEvent.DrawFromStack)
        assertEquals(listOf(0),one.selectedIndices)
        assertEquals(RitualPhase.DRAW,one.phase)
        val two=reducer.reduce(one,RitualEvent.DrawFromStack)
        assertEquals(listOf(0,1),two.selectedIndices)
        assertEquals(RitualPhase.DRAW,two.phase)
        val three=reducer.reduce(two,RitualEvent.DrawFromStack)
        assertEquals(listOf(0,1,2),three.selectedIndices)
        assertEquals(3,three.selectedCards.map { it.cardId }.distinct().size)
        assertEquals(RitualPhase.SPREAD,three.phase)
        assertEquals(three,reducer.reduce(three,RitualEvent.DrawFromStack))
        assertEquals(RitualPhase.READING,reducer.reduce(three,RitualEvent.SpreadFinished).phase)
        assertTrue(draw.selectedIndices.isEmpty())
    }

    @Test fun deckUsesAllCardsAndIndependentReversalRandomness() {
        val deck = drawState().deck
        assertEquals(78, deck.size)
        assertEquals(standardCardIds().toSet(), deck.map { it.cardId }.toSet())
        assertNotEquals(standardCardIds(), deck.map { it.cardId })
        assertEquals(deck, RitualReducer(random = Random(42)).let { r ->
            listOf(RitualEvent.LogoFinished, RitualEvent.AcceptSafety, RitualEvent.SystemsReady,
                RitualEvent.ContemplationFinished, RitualEvent.GatherFinished)
                .fold(RitualState(), r::reduce).deck
        })
        val random = object : Random() {
            var booleans = 0
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextBoolean(): Boolean = (booleans++ % 2 == 0)
        }
        val r = RitualReducer(random = random)
        val s = r.reduce(RitualState(phase = RitualPhase.CONTEMPLATE, safetyAccepted = true), RitualEvent.ContemplationFinished)
        assertEquals(78, random.booleans)
        assertEquals(39, s.deck.count { it.reversed })
    }

    @Test fun revealsAreSequentialAndMustWaitForMatchingNarrationCompletion() {
        var state = readingState()
        assertEquals(RitualPhase.READING, state.phase)
        assertEquals(state, reducer.reduce(state, RitualEvent.RevealCard(1)))
        assertEquals(state, reducer.reduce(state, RitualEvent.FinishReading(100, "premature")))
        for (i in 0..2) {
            state = reducer.reduce(state, RitualEvent.RevealCard(i))
            assertTrue(state.narrationPlaying)
            assertEquals(i + 1, state.revealedCount)
            assertEquals(state, reducer.reduce(state, RitualEvent.RevealCard(i + 1)))
            assertEquals(state, reducer.reduce(state, RitualEvent.NarrationCompleted((i + 1) % 3, state.sessionNumber)))
            assertEquals(state, reducer.reduce(state, RitualEvent.FinishReading(100, "premature")))
            state = reducer.reduce(state, RitualEvent.NarrationCompleted(i, state.sessionNumber))
            assertFalse(state.narrationPlaying)
            assertTrue(state.narrationCompleted)
            assertEquals(minOf(i + 1, 2), state.nextRevealIndex)
        }
        val finished = reducer.reduce(state, RitualEvent.FinishReading(100, "A balanced path"))
        assertEquals(RitualPhase.SUMMARY, finished.phase)
        assertEquals(state.selectedCards, finished.result!!.cards)
        assertEquals(100L, finished.result!!.completedAtEpochMillis)
        assertEquals(finished, reducer.reduce(finished, RitualEvent.FinishReading(101, "replace")))
        val reset = reducer.reduce(finished, RitualEvent.Reset)
        assertEquals(1L, reset.sessionNumber)
        assertTrue(reset.deck.isEmpty())
        assertTrue(reset.selectedIndices.isEmpty())
        assertNull(reset.result)
        assertEquals(0, reset.revealedCount)
    }

    @Test fun narrationFromPreviousSessionCannotUnlockCurrentCard() {
        val state = reducer.reduce(readingState().copy(sessionNumber = 1), RitualEvent.RevealCard(0))
        assertEquals(state, reducer.reduce(state, RitualEvent.NarrationCompleted(0, 0)))
        val completed = reducer.reduce(state, RitualEvent.NarrationCompleted(0, 1))
        assertFalse(completed.narrationPlaying)
        assertEquals(1, completed.nextRevealIndex)
    }

    @Test fun completionDuringSuspensionIsRetainedWithoutAdvancingPhase() {
        val speaking = reducer.reduce(readingState(), RitualEvent.RevealCard(0))
        val suspended = reducer.reduce(speaking, RitualEvent.SetSuspension(SuspensionReason.HAND_TRACKING_LOST))
        val completed = reducer.reduce(suspended, RitualEvent.NarrationCompleted(0, 0))
        assertEquals(RitualPhase.READING, completed.phase)
        assertEquals(SuspensionReason.HAND_TRACKING_LOST, completed.suspension)
        assertFalse(completed.narrationPlaying)
        assertEquals(completed, reducer.reduce(completed, RitualEvent.RevealCard(1)))
    }

    @Test fun suspensionOverlaysPhaseAndBlocksProgressUntilResumed() {
        val draw = drawState()
        val blocked = reducer.reduce(draw, RitualEvent.SetSuspension(SuspensionReason.CONTROLLER_ACTIVE))
        assertEquals(RitualPhase.DRAW, blocked.phase)
        assertEquals(blocked, reducer.reduce(blocked, RitualEvent.DrawFromStack))
        val resumed = reducer.reduce(blocked, RitualEvent.SetSuspension(null))
        assertEquals(draw, resumed)
        assertEquals(listOf(0), reducer.reduce(resumed, RitualEvent.DrawFromStack).selectedIndices)
    }
    @Test fun arbitrarySelectionsKeepSelectionOrderButReadInSlotOrder() {
        val draw = drawState()
        val right = reducer.reduce(draw, RitualEvent.SelectCard(77, 2))
        val left = reducer.reduce(right, RitualEvent.SelectCard(35, 0))
        val full = reducer.reduce(left, RitualEvent.SelectCard(12, 1))
        assertEquals(listOf(77, 35, 12), full.selectedIndices)
        assertEquals(listOf(2, 0, 1), full.selectedSlots)
        assertEquals(listOf(draw.deck[35], draw.deck[12], draw.deck[77]), full.selectedCards)
        assertEquals(2, full.slotForDeckIndex(77))
        assertEquals(35, full.deckIndexForSlot(0))
        assertNull(full.slotForDeckIndex(0))
        assertEquals(RitualPhase.SPREAD, full.phase)
        assertEquals(full, reducer.reduce(full, RitualEvent.SelectCard(1, 0)))
    }

    @Test fun everyDeckIndexCanBeSelectedAcrossAllSixGroups() {
        val draw = drawState()
        for (index in 0 until 78) {
            val selected = reducer.reduce(draw, RitualEvent.SelectCard(index, index % 3))
            assertEquals(listOf(index), selected.selectedIndices)
            assertEquals(listOf(draw.deck[index]), selected.selectedCards)
        }
    }

    @Test fun selectionRejectsInvalidDuplicateOccupiedAndWrongPhaseInputs() {
        val draw = drawState()
        for (event in listOf(RitualEvent.SelectCard(-1, 0), RitualEvent.SelectCard(78, 0),
            RitualEvent.SelectCard(1, -1), RitualEvent.SelectCard(1, 3))) {
            assertEquals(draw, reducer.reduce(draw, event))
        }
        val one = reducer.reduce(draw, RitualEvent.SelectCard(50, 1))
        assertEquals(one, reducer.reduce(one, RitualEvent.SelectCard(50, 0)))
        assertEquals(one, reducer.reduce(one, RitualEvent.SelectCard(51, 1)))
        for (phase in RitualPhase.entries.filter { it != RitualPhase.DRAW }) {
            val state = draw.copy(phase = phase)
            assertEquals(state, reducer.reduce(state, RitualEvent.SelectCard(1, 0)))
            assertEquals(state, reducer.reduce(state, RitualEvent.ChangeDrawPage(1)))
        }
        val shortDeck = draw.copy(deck = draw.deck.take(77))
        assertEquals(shortDeck, reducer.reduce(shortDeck, RitualEvent.SelectCard(1, 0)))
    }

    @Test fun legacySelectionsAndStackAdapterUseFirstUnusedCardAndEmptySlot() {
        val draw = drawState()
        val legacy = draw.copy(selectedIndices = listOf(77, 30))
        assertEquals(0, legacy.slotForDeckIndex(77))
        assertEquals(30, legacy.deckIndexForSlot(1))
        assertEquals(listOf(draw.deck[77], draw.deck[30]), legacy.selectedCards)
        val completed = reducer.reduce(legacy, RitualEvent.DrawFromStack)
        assertEquals(listOf(77, 30, 0), completed.selectedIndices)
        assertEquals(listOf(0, 1, 2), completed.selectedSlots)
        val right = reducer.reduce(draw, RitualEvent.SelectCard(0, 2))
        val next = reducer.reduce(right, RitualEvent.DrawFromStack)
        assertEquals(listOf(0, 1), next.selectedIndices)
        assertEquals(listOf(2, 0), next.selectedSlots)
        assertNull(right.deckIndexForSlot(0))
    }

    @Test fun pagingWrapsLargeDeltasAndResetClearsSelectionAndPageDuringSuspension() {
        val draw = drawState()
        assertEquals(5, reducer.reduce(draw, RitualEvent.ChangeDrawPage(-1)).drawPage)
        assertEquals(1, reducer.reduce(draw.copy(drawPage = 5), RitualEvent.ChangeDrawPage(2)).drawPage)
        for (delta in listOf(0, 6001, -6001, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(Math.floorMod(5L + delta.toLong(), 6L).toInt(),
                reducer.reduce(draw.copy(drawPage = 5), RitualEvent.ChangeDrawPage(delta)).drawPage)
        }
        val selected = reducer.reduce(draw.copy(drawPage = 4), RitualEvent.SelectCard(77, 2))
        for (reason in SuspensionReason.entries) {
            val blocked = selected.copy(suspension = reason)
            assertEquals(blocked, reducer.reduce(blocked, RitualEvent.ChangeDrawPage(1)))
            assertEquals(blocked, reducer.reduce(blocked, RitualEvent.SelectCard(1, 0)))
            val reset = reducer.reduce(blocked, RitualEvent.Reset)
            assertTrue(reset.selectedIndices.isEmpty())
            assertTrue(reset.selectedSlots.isEmpty())
            assertEquals(0, reset.drawPage)
            assertEquals(reason, reset.suspension)
            assertTrue(reset.safetyAccepted)
            assertEquals(RitualPhase.SYSTEMS, reset.phase)
        }
    }

}
