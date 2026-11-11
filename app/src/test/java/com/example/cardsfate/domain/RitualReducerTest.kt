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
        RitualEvent.ContemplationFinished, RitualEvent.GatherFinished, RitualEvent.ShuffleFinished,
        RitualEvent.InterlaceCompleted, RitualEvent.InterlaceCompleted,
    )
    private fun readingState(): RitualState = listOf(
        RitualEvent.ToggleCard(7), RitualEvent.ToggleCard(14), RitualEvent.ToggleCard(28), RitualEvent.SpreadFinished,
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

    @Test fun orderedMilestonesAndTwoInterlacesGateDrawing() {
        val shuffled = advance(RitualEvent.LogoFinished, RitualEvent.AcceptSafety, RitualEvent.SystemsReady,
            RitualEvent.ContemplationFinished, RitualEvent.GatherFinished, RitualEvent.ShuffleFinished)
        assertEquals(RitualPhase.INTERLACE, shuffled.phase)
        assertEquals(shuffled, reducer.reduce(shuffled, RitualEvent.ToggleCard(0)))
        val once = reducer.reduce(shuffled, RitualEvent.InterlaceCompleted)
        assertEquals(1, once.interlaceCount)
        assertEquals(RitualPhase.INTERLACE, once.phase)
        val twice = reducer.reduce(once, RitualEvent.InterlaceCompleted)
        assertEquals(RitualPhase.DRAW, twice.phase)
        assertEquals(2, twice.interlaceCount)
        assertEquals(twice, reducer.reduce(twice, RitualEvent.InterlaceCompleted))
    }

    @Test fun drawTogglesUniquePositionsAndThirdSelectionStartsSpread() {
        val draw = drawState()
        assertEquals(draw, reducer.reduce(draw, RitualEvent.ToggleCard(-1)))
        assertEquals(draw, reducer.reduce(draw, RitualEvent.ToggleCard(78)))
        assertEquals(draw, reducer.reduce(draw, RitualEvent.ConfirmDraw))
        val one = reducer.reduce(draw, RitualEvent.ToggleCard(7))
        assertEquals(listOf(7), one.selectedIndices)
        assertEquals(emptyList<Int>(), reducer.reduce(one, RitualEvent.ToggleCard(7)).selectedIndices)
        val three = listOf(14, 28).fold(one) { s, i -> reducer.reduce(s, RitualEvent.ToggleCard(i)) }
        assertEquals(RitualPhase.SPREAD, three.phase)
        assertEquals(listOf(7, 14, 28), three.selectedIndices)
        assertEquals(3, three.selectedCards.map { it.cardId }.distinct().size)
        assertEquals(three, reducer.reduce(three, RitualEvent.ToggleCard(33)))
        assertTrue(draw.selectedIndices.isEmpty())
    }

    @Test fun deckUsesAllCardsAndIndependentReversalRandomness() {
        val deck = drawState().deck
        assertEquals(78, deck.size)
        assertEquals(standardCardIds().toSet(), deck.map { it.cardId }.toSet())
        assertNotEquals(standardCardIds(), deck.map { it.cardId })
        assertEquals(deck, RitualReducer(random = Random(42)).let { r ->
            listOf(RitualEvent.LogoFinished, RitualEvent.AcceptSafety, RitualEvent.SystemsReady,
                RitualEvent.ContemplationFinished, RitualEvent.GatherFinished, RitualEvent.ShuffleFinished)
                .fold(RitualState(), r::reduce).deck
        })
        val random = object : Random() {
            var booleans = 0
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextBoolean(): Boolean = (booleans++ % 2 == 0)
        }
        val r = RitualReducer(random = random)
        val s = r.reduce(RitualState(phase = RitualPhase.SHUFFLE, safetyAccepted = true), RitualEvent.ShuffleFinished)
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
        assertEquals(blocked, reducer.reduce(blocked, RitualEvent.ToggleCard(4)))
        val resumed = reducer.reduce(blocked, RitualEvent.SetSuspension(null))
        assertEquals(draw, resumed)
        assertEquals(listOf(4), reducer.reduce(resumed, RitualEvent.ToggleCard(4)).selectedIndices)
    }
}
