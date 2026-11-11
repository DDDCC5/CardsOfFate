package com.example.cardsfate.platform.tracking

import org.junit.Assert.*
import org.junit.Test

class HandRitualClassifierTest {
    private fun point(x: Float, z: Float, y: Float = 0f) = Vec3(x, y, z)
    private fun left() = HandSkeleton(point(0f, -.05f), point(0f, -.1f),
        FingerSegment(point(.045f, 0f), point(.045f, .12f)),
        FingerSegment(point(.015f, 0f), point(.015f, .12f)),
        FingerSegment(point(-.015f, 0f), point(-.015f, .12f)),
        FingerSegment(point(-.045f, 0f), point(-.045f, .12f)), point(.12f, 0f))
    private fun right() = HandSkeleton(point(0f, .17f, .01f), point(0f, .22f, .01f),
        FingerSegment(point(-.045f, .12f, .01f), point(.075f, 0f, .01f)),
        FingerSegment(point(-.015f, .12f, .01f), point(.025f, 0f, .01f)),
        FingerSegment(point(.015f, .12f, .01f), point(-.025f, 0f, .01f)),
        FingerSegment(point(.045f, .12f, .01f), point(-.075f, 0f, .01f)), point(.15f, .12f, .01f))
    private fun HandSkeleton.move(delta: Vec3): HandSkeleton {
        fun FingerSegment.move() = FingerSegment(mcp + delta, tip + delta)
        return copy(palm = palm + delta, wrist = wrist + delta, index = index.move(), middle = middle.move(),
            ring = ring.move(), little = little.move(), thumbTip = thumbTip + delta)
    }
    private fun separate(classifier: HandRitualClassifier, time: Long) = classifier.update(left(), right().move(Vec3(.6f, 0f, 0f)), time)

    @Test fun normalUsesJointGeometryAndHandedness() {
        val classifier = HandRitualClassifier()
        val leftNormal = classifier.palmNormal(left(), true)!!
        val rightNormal = classifier.palmNormal(right(), false)!!
        assertEquals(0f, leftNormal.x, .0001f)
        assertEquals(1f, leftNormal.y, .0001f)
        assertEquals(0f, leftNormal.z, .0001f)
        assertEquals(0f, rightNormal.x, .0001f)
        assertEquals(-1f, rightNormal.y, .0001f)
        assertEquals(0f, rightNormal.z, .0001f)
        val degenerate = left().copy(little = left().index)
        assertNull(classifier.palmNormal(degenerate, true))
    }

    @Test fun heldThreeDimensionalInterlaceThenSeparationEmitsOneCycle() {
        val c = HandRitualClassifier()
        assertFalse(c.update(left(), right(), 0).interlaceCycleCompleted)
        assertFalse(c.update(left(), right(), 200).interlaceCycleCompleted)
        assertTrue(separate(c, 300).interlaceCycleCompleted)
        assertFalse(separate(c, 400).interlaceCycleCompleted)
        c.update(left(), right(), 500)
        c.update(left(), right(), 750)
        assertFalse(separate(c, 900).interlaceCycleCompleted)
        c.update(left(), right(), 2300)
        c.update(left(), right(), 2500)
        assertTrue(separate(c, 2600).interlaceCycleCompleted)
    }

    @Test fun briefInterlaceAndProjectedCrossingsAtDifferentDepthDoNotCount() {
        val brief = HandRitualClassifier()
        brief.update(left(), right(), 0)
        assertFalse(separate(brief, 100).interlaceCycleCompleted)
        val separatedDepth = HandRitualClassifier()
        val elevated = right().move(Vec3(0f, .06f, 0f))
        separatedDepth.update(left(), elevated, 0)
        separatedDepth.update(left(), elevated, 400)
        assertFalse(separate(separatedDepth, 500).interlaceCycleCompleted)
    }

    @Test fun foldedFingersAndSameFacingPalmsDoNotCount() {
        val c = HandRitualClassifier()
        val closed = left().let { hand -> hand.copy(index = hand.index.copy(tip = hand.index.mcp),
            middle = hand.middle.copy(tip = hand.middle.mcp), ring = hand.ring.copy(tip = hand.ring.mcp),
            little = hand.little.copy(tip = hand.little.mcp)) }
        c.update(closed, right(), 0)
        c.update(closed, right(), 300)
        assertFalse(separate(c, 400).interlaceCycleCompleted)
        val sameFacing = right().let { it.copy(index = it.little, little = it.index) }
        c.update(left(), sameFacing, 2000)
        c.update(left(), sameFacing, 2400)
        assertFalse(separate(c, 2500).interlaceCycleCompleted)
    }

    @Test fun resetRequiresDeliberatePalmHoldFollowedBySpread() {
        val c = HandRitualClassifier()
        val together = right().move(Vec3(0f, .02f, -.22f))
        c.update(left(), together, 0)
        c.update(left(), together, 1100)
        assertFalse(separate(c, 1150).resetCompleted)
        c.update(left(), together, 2000)
        assertFalse(c.update(left(), together, 3300).resetCompleted)
        val events = separate(c, 3400)
        assertTrue(events.resetCompleted)
        assertFalse(events.interlaceCycleCompleted)
        assertFalse(separate(c, 3600).resetCompleted)
    }

    @Test fun leftPalmMustBeHeldUpBeforeFreshRightIndexWristTouch() {
        val c = HandRitualClassifier()
        val touch = right().copy(index = right().index.copy(tip = left().wrist))
        c.update(left(), right(), 0)
        assertFalse(c.update(left(), touch, 300).historyRequested)
        assertFalse(c.update(left(), touch, 700).historyRequested)
        c.update(left(), right(), 750)
        assertTrue(c.update(left(), touch, 800).historyRequested)
        assertFalse(c.update(left(), touch, 900).historyRequested)
        c.update(left(), right(), 1100)
        assertFalse(c.update(left(), touch, 1200).historyRequested)
        c.update(left(), right(), 2700)
        assertTrue(c.update(left(), touch, 2800).historyRequested)
    }

    @Test fun downwardPalmAndOppositeHandWristTouchCannotOpenHistory() {
        val c = HandRitualClassifier()
        val down = left().let { it.copy(index = it.little, little = it.index) }
        val touch = right().copy(index = right().index.copy(tip = down.wrist))
        c.update(down, right(), 0)
        c.update(down, right(), 800)
        assertFalse(c.update(down, touch, 900).historyRequested)
        val wrongHand = left().copy(index = left().index.copy(tip = right().wrist))
        c.update(left(), right(), 2000)
        c.update(left(), right(), 2800)
        assertFalse(c.update(wrongHand, right(), 2900).historyRequested)
    }

    @Test fun trackingLossAndOutOfOrderFramesCannotCompletePendingCycles() {
        val c = HandRitualClassifier()
        c.update(left(), right(), 100)
        c.update(left(), right(), 300)
        c.update(null, right(), 350)
        assertFalse(separate(c, 400).interlaceCycleCompleted)
        c.update(left(), right(), 1000)
        c.update(left(), right(), 1200)
        assertFalse(separate(c, 1100).interlaceCycleCompleted)
        assertTrue(separate(c, 1300).interlaceCycleCompleted)
        assertFalse(separate(c, 1300).interlaceCycleCompleted)
    }

    @Test fun invalidJointCoordinatesCannotArmReset() {
        val c = HandRitualClassifier()
        val invalid = left().copy(index = left().index.copy(tip = Vec3(Float.POSITIVE_INFINITY, 0f, 0f)))
        val together = right().move(Vec3(0f, .02f, -.22f))
        c.update(invalid, together, 0)
        c.update(invalid, together, 1300)
        assertFalse(separate(c, 1400).resetCompleted)
    }

    @Test fun lostHandCancelsResetAndPalmUpHistoryHold() {
        val c = HandRitualClassifier()
        val together = right().move(Vec3(0f, .02f, -.22f))
        c.update(left(), together, 0)
        c.update(left(), together, 1300)
        c.update(left(), null, 1350)
        assertFalse(separate(c, 1400).resetCompleted)
        c.update(left(), right(), 2000)
        c.update(null, right(), 2700)
        val touch = right().copy(index = right().index.copy(tip = left().wrist))
        assertFalse(c.update(left(), touch, 2800).historyRequested)
    }

    @Test fun singleHandPinchUsesStartReleaseHysteresisAndEdgeEvents() {
        val c = HandRitualClassifier()
        val open = left()
        val pinch = open.copy(thumbTip = open.index.tip + Vec3(.01f, 0f, 0f))
        val between = open.copy(thumbTip = open.index.tip + Vec3(.03f, 0f, 0f))
        assertTrue(c.update(pinch, null, 0).leftPinchStarted)
        assertFalse(c.update(pinch, null, 1).leftPinchStarted)
        assertFalse(c.update(between, null, 2).leftPinchEnded)
        assertTrue(c.update(open, null, 3).leftPinchEnded)
        assertFalse(c.update(open, null, 4).leftPinchEnded)
        assertTrue(c.update(pinch, null, 5).leftPinchStarted)
        assertTrue(c.update(null, null, 6).leftPinchEnded)
    }
}
