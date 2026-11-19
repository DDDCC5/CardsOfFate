package com.example.cardsfate.domain.model

import kotlin.math.abs

/** Open-palm lateral browsing; gripping and nonlateral motion must reset the gesture. */
class FanSwipeGate {
    private var origin:SpacePoint?=null
    private var handKey=-1
    private var since=0L
    private var cooldownUntil=0L
    private var lastSample=Long.MIN_VALUE
    fun update(palm:SpacePoint?,open:Boolean,hand:Int,sampleMs:Long):Int {
        if(sampleMs<=lastSample) return 0
        lastSample=sampleMs
        if(!open || palm==null || !palm.finite()) { origin=null;return 0 }
        if(sampleMs<cooldownUntil) { origin=null;return 0 }
        val start=origin
        if(start==null || hand!=handKey || sampleMs-since>900) {
            origin=palm;handKey=hand;since=sampleMs;return 0
        }
        val d=palm-start
        if(abs(d.y)>.06 || abs(d.z)>.06) { origin=palm;since=sampleMs;return 0 }
        if(abs(d.x)<.12) return 0
        origin=null;cooldownUntil=sampleMs+900
        return if(d.x<0) 1 else -1
    }
    fun reset() { origin=null }
}
