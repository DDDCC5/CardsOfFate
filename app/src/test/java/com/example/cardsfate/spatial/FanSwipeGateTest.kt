package com.example.cardsfate.spatial

import com.example.cardsfate.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class FanSwipeGateTest {
    @Test fun lateralOpenPalmChangesOnlyOnePageUntilCooldown() {
        val g=FanSwipeGate();val p=SpacePoint(0.0,0.0,0.0)
        assertEquals(0,g.update(p,true,0,0));assertEquals(1,g.update(SpacePoint(-.13,0.0,0.0),true,0,200))
        assertEquals(0,g.update(p,true,0,300));assertEquals(0,g.update(SpacePoint(.2,0.0,0.0),true,0,500))
        g.update(p,true,0,1200);assertEquals(-1,g.update(SpacePoint(.13,0.0,0.0),true,0,1400))
    }
    @Test fun closingHandChangingOwnerAndDepthMotionCannotBrowse() {
        val g=FanSwipeGate();val p=SpacePoint(0.0,0.0,0.0)
        g.update(p,true,0,0);assertEquals(0,g.update(SpacePoint(-.2,0.0,0.0),false,0,100))
        g.update(p,true,0,200);assertEquals(0,g.update(SpacePoint(-.2,0.0,0.0),true,1,300))
        assertEquals(0,g.update(SpacePoint(-.4,0.0,.1),true,1,400))
    }
}
