package com.example.cardsfate.domain.model

/** Draw locally above the pile: lift flat, slide/turn, then settle upright. */
object DrawFlightPath {
    private fun ease(value:Double):Double { val t=value.coerceIn(0.0,1.0);return t*t*(3.0-2.0*t) }
    fun rotationProgress(progress:Double)=ease((progress-.4)/.4)
    fun position(start:SpacePoint,end:SpacePoint,progress:Double,height:Double):SpacePoint {
        val t=progress.coerceIn(0.0,1.0)
        val top=maxOf(start.y,end.y)+height
        return when {
            t<=.4 -> SpacePoint(start.x,start.y+(top-start.y)*ease(t/.4),start.z)
            t<.8 -> { val f=ease((t-.4)/.4);SpacePoint(start.x+(end.x-start.x)*f,top,start.z+(end.z-start.z)*f) }
            else -> SpacePoint(end.x,top+(end.y-top)*ease((t-.8)/.2),end.z)
        }
    }
}
