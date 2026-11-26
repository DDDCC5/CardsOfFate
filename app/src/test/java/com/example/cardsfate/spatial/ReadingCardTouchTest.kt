package com.example.cardsfate.spatial

import com.example.cardsfate.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ReadingCardTouchTest {
    private val half=SpacePoint(.0375,.06,.0005)
    @Test fun fastTapCrossingTheCardPlaneStillHits() {
        assertTrue(ReadingCardTouch.hit(SpacePoint(0.0,0.0,.06),SpacePoint(0.0,0.0,-.06),half,false))
    }
    @Test fun crossingBesideTheCardDoesNotHit() {
        assertFalse(ReadingCardTouch.hit(SpacePoint(.15,0.0,.06),SpacePoint(.15,0.0,-.06),half,false))
    }
    @Test fun stationaryTipAwayFromTheCardDoesNotHit() {
        assertFalse(ReadingCardTouch.hit(SpacePoint(0.0,0.0,.08),SpacePoint(0.0,0.0,.08),half,false))
        assertFalse(ReadingCardTouch.hit(null,SpacePoint(0.0,0.0,.08),half,false))
    }
    @Test fun sustainedPinchActivatesReadingOnceWithoutWaitingForRelease() {
        val gate=StackTapGate()
        gate.update(false,false,0);gate.update(false,false,200)
        assertTrue(gate.update(true,true,220))
        assertFalse(gate.update(true,true,500));assertFalse(gate.update(true,true,1000))
        gate.update(false,false,1100);gate.update(false,false,1300)
        assertTrue(gate.update(true,true,1320))
    }
    @Test fun alreadyHeldPinchCannotRetargetAndRevealAnotherCard() {
        val gate=StackTapGate();gate.update(false,false,0);gate.update(false,false,200)
        assertFalse(gate.update(true,false,220));assertFalse(gate.update(true,true,500))
    }
}
