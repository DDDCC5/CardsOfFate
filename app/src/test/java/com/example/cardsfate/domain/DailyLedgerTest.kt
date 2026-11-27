package com.example.cardsfate.domain

import com.example.cardsfate.domain.model.*
import com.example.cardsfate.domain.usecase.*
import org.junit.Assert.*
import org.junit.Test

class DailyLedgerTest {
    @Test fun failedSaveRetainsCandidateAndReopenKeepsIdentity() {
        var disk=emptyList<DailyRecord>();var fail=true;var draws=0
        val ledger=DailyLedger({Result.success(disk)},{ if(fail) Result.failure(IllegalStateException("full")) else {disk=it;Result.success(Unit)} },{draws++;DrawnCard("major-00",true)})
        assertTrue(ledger.open("2026-11-27").isFailure)
        fail=false
        val a=ledger.open("2026-11-27").getOrThrow()
        assertEquals(1,draws);assertEquals(a,ledger.open("2026-11-27").getOrThrow())
        assertEquals(a,DailyLedger({Result.success(disk)},{Result.success(Unit)},{error("must not redraw")}).open("2026-11-27").getOrThrow())
    }
    @Test fun completeUsesFrozenDateAndRetainsThirtyDates() {
        var disk=(1..30).map { DailyRecord("2026-10-${it.toString().padStart(2,'0')}",DrawnCard("major-01",false),true) }
        val ledger=DailyLedger({Result.success(disk)},{disk=it;Result.success(Unit)},{DrawnCard("major-02",false)})
        val a=ledger.open("2026-11-27").getOrThrow()
        ledger.complete(a).getOrThrow()
        assertEquals(30,disk.size);assertTrue(disk.first{it.date==a.date}.completed)
        assertFalse(disk.any{it.date=="2026-10-01"})
    }
    @Test fun singleCardCompletesAfterOneNarrationAndResetKeepsConsent() {
        val reducer=RitualReducer()
        var state=RitualState(phase=RitualPhase.SYSTEMS,safetyAccepted=true)
        state=reducer.reduce(state,RitualEvent.StartDaily(DrawnCard("major-14",false),false))
        state=reducer.reduce(state,RitualEvent.ContemplationFinished)
        assertEquals(1,state.targetCount);assertEquals(1,state.selectedCards.size)
        state=reducer.reduce(state,RitualEvent.RevealCard(0));assertTrue(state.narrationPlaying)
        state=reducer.reduce(state,RitualEvent.NarrationCompleted(0,state.sessionNumber))
        assertTrue(state.narrationCompleted);assertEquals(1,state.revealedCount)
        state=reducer.reduce(state,RitualEvent.Reset)
        assertEquals(RitualPhase.SYSTEMS,state.phase);assertTrue(state.safetyAccepted)
    }
}
