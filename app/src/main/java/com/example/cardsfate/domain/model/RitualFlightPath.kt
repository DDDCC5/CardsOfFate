package com.example.cardsfate.domain.model

/** Automatic animation only: turn in place, rise, travel above objects, then descend. */
object RitualFlightPath {
    const val FLIP_END = .35
    fun flipProgress(progress: Double) = (progress / FLIP_END).coerceIn(0.0,1.0)
    private fun ease(t:Double):Double {val v=t.coerceIn(0.0,1.0);return v*v*(3-2*v)}
    fun position(start: SpacePoint, end: SpacePoint, progress: Double, height: Double=.5): SpacePoint {
        if(progress<=FLIP_END) return start
        val top=maxOf(start.y,end.y)+height
        return when {
            progress<.50 -> SpacePoint(start.x,start.y+(top-start.y)*ease((progress-FLIP_END)/.15),start.z)
            progress<.85 -> {val t=ease((progress-.50)/.35);SpacePoint(start.x+(end.x-start.x)*t,top,start.z+(end.z-start.z)*t)}
            else -> SpacePoint(end.x,top+(end.y-top)*ease((progress-.85)/.15),end.z)
        }
    }
}
