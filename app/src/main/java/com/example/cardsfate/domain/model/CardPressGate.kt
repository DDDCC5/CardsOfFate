package com.example.cardsfate.domain.model

/** A release is either a tap or a hold, never both. Entry and cancellation require a real release. */
class CardPressGate(
    private val holdMs: Long = 220,
    private val movementM: Double = .015,
    private val stableReleaseMs: Long = 140,
) {
    enum class Signal { NONE, START, HOLDING, TAP_RELEASE, HOLD_RELEASE }
    private var lastSample = Long.MIN_VALUE
    private var releasedSince: Long? = null
    private var armed = false
    private var startedAt: Long? = null
    private var moved = false

    fun update(pressed: Boolean, sampleMs: Long, displacementM: Double = 0.0): Signal {
        if (sampleMs <= lastSample || !displacementM.isFinite() || displacementM < 0) return Signal.NONE
        lastSample = sampleMs
        val start = startedAt
        if (start != null) {
            moved = moved || displacementM >= movementM || sampleMs - start >= holdMs
            if (pressed) return if (moved) Signal.HOLDING else Signal.NONE
            startedAt = null
            releasedSince = sampleMs
            armed = false
            return if (moved) Signal.HOLD_RELEASE else Signal.TAP_RELEASE
        }
        if (!pressed) {
            if (releasedSince == null) releasedSince = sampleMs
            if (sampleMs - releasedSince!! >= stableReleaseMs) armed = true
            return Signal.NONE
        }
        releasedSince = null
        if (!armed) return Signal.NONE
        armed = false
        startedAt = sampleMs
        moved = false
        return Signal.START
    }

    fun cancel() {
        armed = false
        startedAt = null
        releasedSince = null
        moved = false
    }
}
