package com.example.cardsfate.spatial

import com.example.cardsfate.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class RitualFlightPathTest {
    private val start=SpacePoint(-1.0,.9,-.6)
    private val end=SpacePoint(.1,.9,-.4)
    @Test fun flipFinishesBeforeTranslationStarts() {
        assertEquals(start,RitualFlightPath.position(start,end,0.0))
        assertEquals(start,RitualFlightPath.position(start,end,.35))
        assertEquals(1.0,RitualFlightPath.flipProgress(.35),0.0)
        assertEquals(end.x,RitualFlightPath.position(start,end,1.0).x,1e-10)
        assertEquals(end.y,RitualFlightPath.position(start,end,1.0).y,1e-10)
    }
    @Test fun arcClearsDeskObjectThatBlocksStraightFlight() {
        val obstacle=PlaneTriangles(listOf(SpacePoint(-.3,.2,-1.0),SpacePoint(-.3,1.15,-1.0),SpacePoint(-.3,1.15,1.0),SpacePoint(-.3,.2,1.0)),listOf(0,1,2,0,2,3))
        val axes=listOf(SpacePoint(1.0,0.0,0.0),SpacePoint(0.0,1.0,0.0),SpacePoint(0.0,0.0,1.0))
        fun clear(p:SpacePoint)=obstacle.isBoxClear(SpaceBox(p,axes,SpacePoint(.05,.001,.086)),.025)
        assertFalse(clear(SpacePoint(-.3,.9,-.47)))
        assertTrue((0..100).all { clear(RitualFlightPath.position(start,end,it/100.0)) })
    }
    @Test fun raisedTravelContinuesUntilCardHasPassedLateObstacle() {
        val obstacle=PlaneTriangles(listOf(SpacePoint(-.05,.2,-1.0),SpacePoint(-.05,1.30,-1.0),SpacePoint(-.05,1.30,1.0),SpacePoint(-.05,.2,1.0)),listOf(0,1,2,0,2,3))
        val axes=listOf(SpacePoint(1.0,0.0,0.0),SpacePoint(0.0,1.0,0.0),SpacePoint(0.0,0.0,1.0))
        assertTrue((0..120).all { obstacle.isBoxClear(SpaceBox(RitualFlightPath.position(start,end,it/120.0),axes,SpacePoint(.05,.001,.086)),.025) })
    }
}
