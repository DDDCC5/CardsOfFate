package com.example.cardsfate.domain

import com.example.cardsfate.domain.model.*
import com.example.cardsfate.domain.usecase.RitualReducer
import org.junit.Assert.*
import org.junit.Test

class ExperienceRecoveryTest {
    @Test fun orbitObstructionAndSpaceRetryDoNotStartANewQuestion() {
        val reducer=RitualReducer()
        val original=RitualState(phase=RitualPhase.CONTEMPLATE,safetyAccepted=true,sessionNumber=12)
        assertFalse(ExperiencePolicy.cardCollisionBlocksSession(original.phase))
        val paused=reducer.reduce(original,RitualEvent.SetSuspension(SuspensionReason.SPACE_UNAVAILABLE))
        val retried=if(ExperiencePolicy.spaceRetryRequiresReset(paused.phase)) reducer.reduce(paused,RitualEvent.Reset) else paused
        val recovered=reducer.reduce(retried,RitualEvent.SetSuspension(null))
        assertEquals(original,recovered)
        assertEquals(12L,recovered.sessionNumber)
    }
    @Test fun actualHandWorkspaceCollisionStillBlocksDuringGatherDrawAndReading() {
        for(phase in listOf(RitualPhase.GATHER,RitualPhase.DRAW,RitualPhase.SPREAD,RitualPhase.READING,RitualPhase.SUMMARY))
            assertTrue(phase.name,ExperiencePolicy.cardCollisionBlocksSession(phase))
    }
    @Test fun readingAndCompletedCardsNeverAllowHandDrivenMovement() {
        assertTrue(ExperiencePolicy.cardMovementAvailable(RitualPhase.DRAW))
        for(phase in RitualPhase.entries.filterNot { it==RitualPhase.DRAW })
            assertFalse(phase.name,ExperiencePolicy.cardMovementAvailable(phase))
    }
    @Test fun longPinchOnReadingCardIsNeitherATapNorMovement() {
        val gate=CardPressGate()
        gate.update(false,0);gate.update(false,150)
        assertEquals(CardPressGate.Signal.START,gate.update(true,200))
        assertEquals(CardPressGate.Signal.HOLDING,gate.update(true,500,.1))
        assertEquals(CardPressGate.Signal.HOLD_RELEASE,gate.update(false,600,.1))
        assertFalse(ExperiencePolicy.cardMovementAvailable(RitualPhase.READING))
        assertFalse(ExperiencePolicy.cardMovementAvailable(RitualPhase.SUMMARY))
        assertEquals(CardPressGate.Signal.NONE,gate.update(false,600))
    }
}
