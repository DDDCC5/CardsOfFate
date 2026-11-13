package com.example.cardsfate.domain.model

import kotlin.math.abs

/** View-space UI and interaction offsets never contain the world orbit's obstacle-clearance lift. */
object ViewLayout {
    val mainPanel=SpacePoint(0.0,0.0,-.70)
    val drawGuidance=SpacePoint(0.0,.30,-.70)
    // Both offsets are relative to the fixed pile frame, not the old distant head frame.
    val readingPanel=SpacePoint(0.0,-.28,-.18)
    fun stackCandidates():List<SpacePoint> = listOf(.42,.38,.46).flatMap { depth ->
        listOf(.16,.12,.08,.20).map { down -> SpacePoint(0.0,-down,-depth) }
    }
    fun readingOffset(index:Int)=SpacePoint((index-1)*.15,.12,0.0)
    fun obstructsGuidance(viewPosition:SpacePoint,radius:Double):Boolean {
        val depth=-viewPosition.z
        return depth>0 && depth-radius<=.78 &&
            abs(viewPosition.x)<=depth*.75+radius && abs(viewPosition.y)<=depth*.55+radius
    }
}
