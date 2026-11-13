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
}
