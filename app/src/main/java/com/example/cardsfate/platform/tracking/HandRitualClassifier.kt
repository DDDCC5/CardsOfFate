package com.example.cardsfate.platform.tracking

import kotlin.math.sqrt
import kotlin.math.abs

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Float) = Vec3(x * scale, y * scale, z * scale)
    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    fun cross(other: Vec3) = Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun length() = sqrt(dot(this))
    fun distanceTo(other: Vec3) = (this - other).length()
}

data class FingerSegment(val mcp: Vec3, val tip: Vec3)
data class HandSkeleton(
    val palm: Vec3,
    val wrist: Vec3,
    val index: FingerSegment,
    val middle: FingerSegment,
    val ring: FingerSegment,
    val little: FingerSegment,
    val thumbTip: Vec3,
)

data class GestureThresholds(
    val interlaceHoldMs: Long = 180,
    val resetHoldMs: Long = 1200,
    val historyPalmHoldMs: Long = 650,
    val cooldownMs: Long = 1800,
    val cycleTimeoutMs: Long = 2500,
    val fingerProximityM: Float = .018f,
    val minimumFingerLengthM: Float = .045f,
    val minimumSpreadM: Float = .075f,
    val interlacePalmMinM: Float = .065f,
    val interlacePalmMaxM: Float = .25f,
    val opposedPalmDotMax: Float = -.45f,
    val crossingDirectionDotMax: Float = .985f,
    val releasePalmDistanceM: Float = .32f,
    val resetPalmDistanceM: Float = .055f,
    val historyPalmUpDotMin: Float = .75f,
    val wristTouchM: Float = .032f,
    val wristReleaseM: Float = .05f,
    val pinchStartM: Float = .025f,
    val pinchReleaseM: Float = .04f,
)

data class HandGestureEvents(
    val interlaceCycleCompleted: Boolean = false,
    val resetCompleted: Boolean = false,
    val historyRequested: Boolean = false,
    val leftPinchStarted: Boolean = false,
    val leftPinchEnded: Boolean = false,
    val rightPinchStarted: Boolean = false,
    val rightPinchEnded: Boolean = false,
)

/** Raw world-space positions in metres. Thresholds need validation against device occlusion. */
class HandRitualClassifier(private val config: GestureThresholds = GestureThresholds()) {
    private var lastTimestamp: Long? = null
    private var leftPinching = false
    private var rightPinching = false
    private var interlaceSince: Long? = null
    private var interlaceArmedAt: Long? = null
    private var resetSince: Long? = null
    private var resetArmedAt: Long? = null
    private var historyUpSince: Long? = null
    private var wristTouching = false
    private var lastActionAt: Long? = null

    fun update(left: HandSkeleton?, right: HandSkeleton?, timestampMs: Long): HandGestureEvents {
        if (lastTimestamp?.let { timestampMs <= it } == true) return HandGestureEvents()
        lastTimestamp = timestampMs
        val oldLeftPinch = leftPinching
        val oldRightPinch = rightPinching
        leftPinching = pinch(left, leftPinching)
        rightPinching = pinch(right, rightPinching)
        var interlace = false
        var reset = false
        var history = false
        val leftNormal = left?.let { palmNormal(it, true) }
        val rightNormal = right?.let { palmNormal(it, false) }
        if (left == null || right == null || leftNormal == null || rightNormal == null) {
            clearTwoHandGestures()
        } else {
            val cooling = lastActionAt?.let { timestampMs - it < config.cooldownMs } == true
            val opposed = leftNormal.dot(rightNormal) <= config.opposedPalmDotMax
            val open = spread(left) && spread(right)
            val palmDistance = left.palm.distanceTo(right.palm)
            val released = open && palmDistance >= config.releasePalmDistanceM
            if (interlaceArmedAt?.let { timestampMs - it > config.cycleTimeoutMs } == true) {
                interlaceArmedAt = null
                interlaceSince = null
            }
            if (resetArmedAt?.let { timestampMs - it > config.cycleTimeoutMs } == true) {
                resetArmedAt = null
                resetSince = null
            }
            if (!cooling && released && resetArmedAt != null) reset = true
            else if (!cooling && released && interlaceArmedAt != null) interlace = true

            if (!cooling && !reset && !interlace) {
                val palmsTogether = opposed && open && palmDistance <= config.resetPalmDistanceM
                if (palmsTogether) {
                    if (resetSince == null) resetSince = timestampMs
                    if (timestampMs - resetSince!! >= config.resetHoldMs) resetArmedAt = timestampMs
                    // A deliberate reset hold owns this pose, preventing a simultaneous interlace.
                    interlaceSince = null
                    interlaceArmedAt = null
                } else resetSince = null
                val interlaced = !palmsTogether && opposed && open &&
                    palmDistance in config.interlacePalmMinM..config.interlacePalmMaxM && crossingFingers(left, right)
                if (interlaced) {
                    if (interlaceSince == null) interlaceSince = timestampMs
                    if (timestampMs - interlaceSince!! >= config.interlaceHoldMs) interlaceArmedAt = timestampMs
                } else interlaceSince = null
            }

            val palmUp = leftNormal.y >= config.historyPalmUpDotMin && spread(left)
            val wristDistance = right.index.tip.distanceTo(left.wrist)
            val wasTouching = wristTouching
            wristTouching = if (wristTouching) wristDistance < config.wristReleaseM else wristDistance <= config.wristTouchM
            if (palmUp) {
                if (historyUpSince == null) historyUpSince = timestampMs
                history = !cooling && !reset && !interlace && timestampMs - historyUpSince!! >= config.historyPalmHoldMs &&
                    wristTouching && !wasTouching
            } else historyUpSince = null
            if (reset || interlace || history) {
                lastActionAt = timestampMs
                interlaceSince = null
                interlaceArmedAt = null
                resetSince = null
                resetArmedAt = null
            }
        }
        return HandGestureEvents(interlace, reset, history,
            leftPinching && !oldLeftPinch, !leftPinching && oldLeftPinch,
            rightPinching && !oldRightPinch, !rightPinching && oldRightPinch)
    }

    /** Outward palm normal; opposite joint winding accounts for left and right anatomy. */
    fun palmNormal(hand: HandSkeleton, isLeft: Boolean): Vec3? {
        if (!valid(hand)) return null
        val index = hand.index.mcp - hand.wrist
        val little = hand.little.mcp - hand.wrist
        val normal = if (isLeft) little.cross(index) else index.cross(little)
        val length = normal.length()
        return if (length.isFinite() && length > .000001f) normal * (1f / length) else null
    }

    private fun valid(hand: HandSkeleton): Boolean =
        (listOf(hand.palm, hand.wrist, hand.thumbTip) + fingers(hand).flatMap { listOf(it.mcp, it.tip) })
            .all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }

    private fun fingers(hand: HandSkeleton) = listOf(hand.index, hand.middle, hand.ring, hand.little)

    private fun spread(hand: HandSkeleton): Boolean = fingers(hand).all {
        it.tip.distanceTo(it.mcp) >= config.minimumFingerLengthM
    } && hand.index.tip.distanceTo(hand.little.tip) >= config.minimumSpreadM

    private fun pinch(hand: HandSkeleton?, previous: Boolean): Boolean {
        if (hand == null || !valid(hand)) return false
        val distance = hand.index.tip.distanceTo(hand.thumbTip)
        return if (previous) distance < config.pinchReleaseM else distance <= config.pinchStartM
    }

    /** Tests closest points inside both 3D MCP-to-tip segments; projected crossings are insufficient. */
    private fun crossingFingers(left: HandSkeleton, right: HandSkeleton): Boolean {
        val crossedLeft = mutableSetOf<Int>()
        val crossedRight = mutableSetOf<Int>()
        fingers(left).forEachIndexed { i, first ->
            fingers(right).forEachIndexed { j, second ->
                val u = first.tip - first.mcp
                val v = second.tip - second.mcp
                val w = first.mcp - second.mcp
                val a = u.dot(u)
                val b = u.dot(v)
                val c = v.dot(v)
                val denominator = a * c - b * b
                if (a > 0f && c > 0f && abs(b) / sqrt(a * c) < config.crossingDirectionDotMax && denominator > 0f) {
                    val d = u.dot(w)
                    val e = v.dot(w)
                    val s = (b * e - c * d) / denominator
                    val t = (a * e - b * d) / denominator
                    if (s in 0.05f..0.95f && t in 0.05f..0.95f &&
                        (first.mcp + u * s).distanceTo(second.mcp + v * t) <= config.fingerProximityM
                    ) {
                        crossedLeft += i
                        crossedRight += j
                    }
                }
            }
        }
        return crossedLeft.size >= 2 && crossedRight.size >= 2
    }

    private fun clearTwoHandGestures() {
        interlaceSince = null
        interlaceArmedAt = null
        resetSince = null
        resetArmedAt = null
        historyUpSince = null
        wristTouching = false
    }
}
