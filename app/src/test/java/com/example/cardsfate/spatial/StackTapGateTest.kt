package com.example.cardsfate.spatial

import com.example.cardsfate.domain.model.StackTapGate
import org.junit.Assert.*
import org.junit.Test

class StackTapGateTest {
    private fun release(gate:StackTapGate,at:Long) {
        gate.update(false,false,at)
        gate.update(false,false,at+180)
    }
    @Test fun heldInputAtEntryDoesNotDrawUntilStablyReleased() {
        val gate=StackTapGate()
        assertFalse(gate.update(true,true,0))
        release(gate,10)
        assertTrue(gate.update(true,true,200))
    }
    @Test fun boundaryJitterCannotTurnOnePhysicalClickIntoTwoCards() {
        val gate=StackTapGate()
        release(gate,0)
        assertTrue(gate.update(true,true,200))
        assertFalse(gate.update(false,false,230))
        assertFalse(gate.update(true,true,245))
        assertFalse(gate.update(false,false,700))
        assertFalse(gate.update(true,true,720))
        release(gate,800)
        assertTrue(gate.update(true,true,1000))
    }
    @Test fun exactlyThreeDeliberatePressesProduceThreeActivations() {
        val gate=StackTapGate()
        var count=0
        repeat(3) { index ->
            val start=index*700L
            release(gate,start)
            if(gate.update(true,true,start+200)) count++
            repeat(10) { frame -> if(gate.update(true,true,start+210+frame*10)) count++ }
        }
        assertEquals(3,count)
    }
    @Test fun retargetingAnAlreadyHeldPinchDoesNotCount() {
        val gate=StackTapGate()
        release(gate,0)
        assertFalse(gate.update(true,false,200))
        assertFalse(gate.update(true,true,250))
        release(gate,500)
        assertTrue(gate.update(true,true,700))
    }
    @Test fun resetAndStaleSamplesCannotReplayAPress() {
        val gate=StackTapGate()
        release(gate,0)
        assertTrue(gate.update(true,true,200))
        assertFalse(gate.update(false,false,190))
        assertFalse(gate.update(true,true,200))
        gate.reset()
        assertFalse(gate.update(true,true,1000))
        release(gate,1100)
        assertTrue(gate.update(true,true,1300))
    }
}
