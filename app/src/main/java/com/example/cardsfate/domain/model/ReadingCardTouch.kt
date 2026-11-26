package com.example.cardsfate.domain.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Exact segment/slab test in card-local metres, so a quick fingertip crossing cannot skip a tap. */
object ReadingCardTouch {
    fun hit(previous:SpacePoint?,current:SpacePoint,half:SpacePoint,latched:Boolean):Boolean {
        if(!current.finite() || !half.finite() || minOf(half.x,half.y,half.z)<0) return false
        val extent=SpacePoint(half.x+.015,half.y+.015,if(latched) .045 else .018)
        fun inside(p:SpacePoint)=abs(p.x)<=extent.x && abs(p.y)<=extent.y && abs(p.z)<=extent.z
        if(inside(current)) return true
        val start=previous?.takeIf(SpacePoint::finite) ?: return false
        var low=0.0;var high=1.0
        val origins=listOf(start.x,start.y,start.z)
        val ends=listOf(current.x,current.y,current.z)
        val radii=listOf(extent.x,extent.y,extent.z)
        for(axis in 0..2) {
            val origin=origins[axis];val delta=ends[axis]-origin;val radius=radii[axis]
            if(abs(delta)<1e-12) { if(abs(origin)>radius) return false }
            else {
                val first=(-radius-origin)/delta;val last=(radius-origin)/delta
                low=max(low,min(first,last));high=min(high,max(first,last))
                if(low>high) return false
            }
        }
        return low<=high
    }
}
