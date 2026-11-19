package com.example.cardsfate.spatial

import com.example.cardsfate.domain.model.CardPressGate
import com.example.cardsfate.domain.model.CardPressGate.Signal
import org.junit.Assert.*
import org.junit.Test

class CardPressGateTest {
    private fun arm(g:CardPressGate,at:Long=0) { g.update(false,at);g.update(false,at+150) }
    @Test fun enteringWhilePinchingDoesNotGrab() {
        val g=CardPressGate();assertEquals(Signal.NONE,g.update(true,0));assertEquals(Signal.NONE,g.update(true,500))
        arm(g,600);assertEquals(Signal.START,g.update(true,800))
    }
    @Test fun shortStationaryPinchOnlyEmitsOneTap() {
        val g=CardPressGate();arm(g)
        assertEquals(Signal.START,g.update(true,200));assertEquals(Signal.TAP_RELEASE,g.update(false,300))
        assertEquals(Signal.NONE,g.update(false,300));assertEquals(Signal.NONE,g.update(true,320))
    }
    @Test fun movementAndLongHoldCannotAlsoFlipOnRelease() {
        val g=CardPressGate();arm(g);g.update(true,200)
        assertEquals(Signal.HOLDING,g.update(true,250,.02));assertEquals(Signal.HOLD_RELEASE,g.update(false,260))
        arm(g,400);g.update(true,600);assertEquals(Signal.HOLD_RELEASE,g.update(false,850))
    }
    @Test fun cancellationDoesNotReleaseOrRetargetWhileStillPinching() {
        val g=CardPressGate();arm(g);g.update(true,200);g.cancel()
        assertEquals(Signal.NONE,g.update(true,900));assertEquals(Signal.NONE,g.update(false,950))
        assertEquals(Signal.NONE,g.update(true,1000));arm(g,1100);assertEquals(Signal.START,g.update(true,1300))
    }
    @Test fun repeatedAndStaleSamplesCannotActivate() {
        val g=CardPressGate();arm(g);g.update(true,200)
        assertEquals(Signal.NONE,g.update(false,190));assertEquals(Signal.NONE,g.update(false,200))
        assertEquals(Signal.TAP_RELEASE,g.update(false,300))
    }
}
