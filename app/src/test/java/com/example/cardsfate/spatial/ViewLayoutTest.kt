package com.example.cardsfate.spatial

import com.example.cardsfate.domain.model.*
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class ViewLayoutTest {
    @Test fun stackCandidatesStayCenteredBelowCurrentViewWithinReach() {
        val candidates=ViewLayout.stackCandidates()
        assertTrue(candidates.isNotEmpty())
        for(p in candidates) {
            assertEquals(0.0,p.x,0.0)
            assertTrue(p.y in -.20..-.08)
            assertTrue(p.z in -.46..-.38)
            assertTrue(sqrt(p.dot(p))<.56)
        }
        assertEquals(SpacePoint(0.0,-.16,-.42),candidates.first())
    }
    @Test fun panelIsCloserThanOrbitAndReadingDoesNotInheritOrbitLift() {
        assertEquals(SpacePoint(0.0,0.0,-.70),ViewLayout.mainPanel)
        assertEquals(SpacePoint(0.0,.12,0.0),ViewLayout.readingOffset(1))
        assertEquals(-.15,ViewLayout.readingOffset(0).x,1e-10)
        assertEquals(.15,ViewLayout.readingOffset(2).x,1e-10)
        assertEquals(0.0,ViewLayout.readingPanel.x,0.0)
        assertTrue(ViewLayout.readingPanel.y<0.0)
    }
    @Test fun foregroundCardsCannotCoverGuidanceButDistantOrPeripheralCardsRemain() {
        assertTrue(ViewLayout.obstructsGuidance(SpacePoint(0.0,0.0,-.5),.085))
        assertFalse(ViewLayout.obstructsGuidance(SpacePoint(0.0,0.0,-1.1),.085))
        assertFalse(ViewLayout.obstructsGuidance(SpacePoint(1.0,0.0,-.5),.085))
        assertFalse(ViewLayout.obstructsGuidance(SpacePoint(0.0,0.0,.5),.085))
    }
    @Test fun drawnRowStaysAbovePileWithoutTravelingBehindGuidance() {
        val offsets=(0..2).map(ViewLayout::readingOffset)
        assertTrue(offsets.all { it.y>.10 && it.z==0.0 })
        assertEquals(3,offsets.map { it.x }.distinct().size)
        assertTrue(ViewLayout.readingPanel.y<0.0)
        assertEquals(0.0,ViewLayout.readingPanel.x,0.0)
    }

}
