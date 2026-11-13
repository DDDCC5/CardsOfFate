package com.example.cardsfate.domain.model

import kotlin.math.PI

/** A stationary Y-axis turn: back at PI, artwork upright and facing the user at zero. */
object CardFlipMotion {
    const val DURATION_SECONDS=.65f
    fun yaw(progress:Double):Double {
        val t=progress.coerceIn(0.0,1.0)
        return PI*(1.0-t*t*(3.0-2.0*t))
    }
}
