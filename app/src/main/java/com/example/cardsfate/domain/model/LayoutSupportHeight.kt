package com.example.cardsfate.domain.model

import kotlin.math.sqrt

/** Keep the complete flipping card envelope above nearby support surfaces; no fixed lift cap. */
object LayoutSupportHeight {
    fun minimumLift(headHeight:Double,supportHeight:Double?,half:SpacePoint):Double {
        if(supportHeight==null) return 0.0
        val diagonal=sqrt(half.dot(half))
        return maxOf(0.0,supportHeight+diagonal+.04-(headHeight-.35))
    }
}
