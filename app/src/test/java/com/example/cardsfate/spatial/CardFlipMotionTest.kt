package com.example.cardsfate.spatial

import com.example.cardsfate.domain.model.CardFlipMotion
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class CardFlipMotionTest {
    @Test fun cardTurnsFromBackToFrontWithoutRollingArtworkUpsideDown() {
        assertEquals(PI,CardFlipMotion.yaw(0.0),1e-10)
        assertEquals(0.0,CardFlipMotion.yaw(1.0),1e-10)
        assertEquals(PI/2,CardFlipMotion.yaw(.5),1e-10)
        for(i in 0..100) {
            val angle=CardFlipMotion.yaw(i/100.0)
            val frontX=sin(angle)
            val frontZ=cos(angle)
            assertTrue(angle in 0.0..PI)
            assertEquals(1.0,frontX*frontX+frontZ*frontZ,1e-10)
        }
        assertEquals(1.0,cos(CardFlipMotion.yaw(1.0)),1e-10)
    }
    @Test fun progressIsClampedToTheCompleteFlip() {
        assertEquals(PI,CardFlipMotion.yaw(-1.0),1e-10)
        assertEquals(0.0,CardFlipMotion.yaw(2.0),1e-10)
    }
}
