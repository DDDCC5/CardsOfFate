package com.example.cardsfate.domain.model

import kotlin.math.abs

/** Meter-valued geometry, independent of Android and native Entity handles. */
data class SpacePoint(val x: Double, val y: Double, val z: Double) {
    operator fun minus(p: SpacePoint) = SpacePoint(x-p.x,y-p.y,z-p.z)
    fun dot(p: SpacePoint) = x*p.x+y*p.y+z*p.z
    fun cross(p: SpacePoint) = SpacePoint(y*p.z-z*p.y,z*p.x-x*p.z,x*p.y-y*p.x)
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
}
data class SpaceBox(val center: SpacePoint, val axes: List<SpacePoint>, val half: SpacePoint)

/** Narrow phase uses plane triangles, so empty corners of a tilted plane's AABB remain empty. */
class PlaneTriangles(vertices: List<SpacePoint>, indices: List<Int>) {
    private val points=vertices.toList()
    private val triangles=if(indices.size%3==0) indices.chunked(3).mapNotNull { face ->
        if(face.any { it !in points.indices }) null else face.map { points[it] }.takeIf {
            it.all(SpacePoint::finite) && (it[1]-it[0]).cross(it[2]-it[0]).let { n -> n.dot(n)>1e-18 }
        }
    } else emptyList()
    val valid get() = triangles.isNotEmpty()
    private val low=SpacePoint(points.minOfOrNull{it.x}?:0.0,points.minOfOrNull{it.y}?:0.0,points.minOfOrNull{it.z}?:0.0)
    private val high=SpacePoint(points.maxOfOrNull{it.x}?:0.0,points.maxOfOrNull{it.y}?:0.0,points.maxOfOrNull{it.z}?:0.0)

    fun isBoxClear(box: SpaceBox, margin: Double): Boolean {
        val a=box.axes
        if(!valid || !box.center.finite() || !box.half.finite() || margin<0 || !margin.isFinite() ||
            minOf(box.half.x,box.half.y,box.half.z)<0 || a.size!=3 || a.any{!it.finite() || abs(it.dot(it)-1.0)>.002} ||
            abs(a[0].dot(a[1]))>.002 || abs(a[0].dot(a[2]))>.002 || abs(a[1].dot(a[2]))>.002) return false
        val h=SpacePoint(box.half.x+margin,box.half.y+margin,box.half.z+margin)
        val extent=SpacePoint(abs(a[0].x)*h.x+abs(a[1].x)*h.y+abs(a[2].x)*h.z,
            abs(a[0].y)*h.x+abs(a[1].y)*h.y+abs(a[2].y)*h.z,
            abs(a[0].z)*h.x+abs(a[1].z)*h.y+abs(a[2].z)*h.z)
        val c=box.center
        if(c.x+extent.x<low.x || c.x-extent.x>high.x || c.y+extent.y<low.y ||
            c.y-extent.y>high.y || c.z+extent.z<low.z || c.z-extent.z>high.z) return true
        val basis=listOf(SpacePoint(1.0,0.0,0.0),SpacePoint(0.0,1.0,0.0),SpacePoint(0.0,0.0,1.0))
        return triangles.none { triangle ->
            val v=triangle.map { p -> val d=p-c; SpacePoint(d.dot(a[0]),d.dot(a[1]),d.dot(a[2])) }
            val edges=listOf(v[1]-v[0],v[2]-v[1],v[0]-v[2])
            val axes=basis + listOf(edges[0].cross(edges[1])) + edges.flatMap { e -> basis.map(e::cross) }
            axes.none { axis ->
                if(axis.dot(axis)<1e-20) false else {
                    val radius=h.x*abs(axis.x)+h.y*abs(axis.y)+h.z*abs(axis.z)
                    val projections=v.map(axis::dot)
                    projections.min()>radius+1e-10 || projections.max()< -radius-1e-10
                }
            }
        }
    }
}
