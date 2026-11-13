package com.example.cardsfate.spatial

import com.example.cardsfate.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class DrawFlightPathTest {
    private val start=SpacePoint(0.0,.83,-.42)
    private val end=SpacePoint(.15,.95,-.42)
    @Test fun cardLiftsFlatBeforeTurningAndKeepsItsDestinationAboveThePile() {
        assertEquals(start,DrawFlightPath.position(start,end,0.0,.04))
        assertEquals(end,DrawFlightPath.position(start,end,1.0,.04))
        for(i in 0..100) {
            val t=i/100.0
            val p=DrawFlightPath.position(start,end,t,.04)
            if(t<=.4) {
                assertEquals(0.0,DrawFlightPath.rotationProgress(t),1e-10)
                assertEquals(start.x,p.x,1e-10)
            }
            assertTrue(p.y>=start.y && p.y<=start.y+.21)
            assertEquals(start.z,p.z,1e-10)
        }
        assertEquals(1.0,DrawFlightPath.rotationProgress(1.0),1e-10)
    }
    @Test fun turnEnvelopeStaysAboveSupportingTable() {
        for(i in 0..100) {
            val t=i/100.0
            val p=DrawFlightPath.position(start,end,t,.04)
            val halfHeight=if(DrawFlightPath.rotationProgress(t)==0.0) .0002 else .084
            assertTrue(p.y-halfHeight-.025>.80)
        }
    }
}
