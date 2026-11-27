package com.example.cardsfate.spatial

import com.example.cardsfate.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ComfortPreviewTest {
    private val axes=listOf(SpacePoint(1.0,0.0,0.0),SpacePoint(0.0,1.0,0.0),SpacePoint(0.0,0.0,1.0))
    private fun table(y:Double)=PlaneTriangles(listOf(SpacePoint(-2.0,y,-2.0),SpacePoint(2.0,y,-2.0),SpacePoint(2.0,y,2.0),SpacePoint(-2.0,y,2.0)),listOf(0,1,2,0,2,3))
    private fun readingClear(center:SpacePoint,plane:PlaneTriangles)=plane.isBoxClear(SpaceBox(SpacePoint(center.x,center.y+.14,center.z),axes,SpacePoint(.035,.06,.0002)),.025)
    @Test fun blockedLowerFanDoesNotHideSafeReadingPreview() {
        val plane=table(.77);val lower=SpacePoint(0.0,.96,-.42)
        // A low, tilted fan has a larger vertical envelope than the preview row.
        assertFalse(plane.isBoxClear(SpaceBox(SpacePoint(0.0,lower.y-.098,lower.z),axes,SpacePoint(.035,.07,.0002)),.025))
        val preview=ComfortPreviewCache<SpacePoint>()
        val result=preview.select(lower){readingClear(it,plane)}
        assertEquals(lower,result.pose);assertFalse(result.fallback)
    }
    @Test fun blockedRequestedRowKeepsLastSafePoseAndRoomChangesRevalidateIt() {
        val preview=ComfortPreviewCache<SpacePoint>();val good=SpacePoint(0.0,1.02,-.42);val bad=SpacePoint(0.0,.70,-.42)
        preview.select(good){readingClear(it,table(.77))}
        val result=preview.select(bad){readingClear(it,table(.77))}
        assertEquals(good,result.pose);assertTrue(result.fallback)
        assertNull(preview.select(bad){readingClear(it,table(.77)) && readingClear(it,table(1.16))}.pose)
    }
    @Test fun trackingLossHidesAllGeometryAndResetNeverKeepsAnotherHeading() {
        val preview=ComfortPreviewCache<SpacePoint>();val p=SpacePoint(0.0,1.02,-.42)
        preview.select(p){true}
        assertNull(preview.select(p){false}.pose)
        preview.reset()
        assertNull(preview.select(SpacePoint(1.0,.7,.2)){false}.pose)
    }
}
