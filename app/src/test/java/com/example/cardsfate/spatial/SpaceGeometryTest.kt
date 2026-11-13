package com.example.cardsfate.spatial

import com.example.cardsfate.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class SpaceGeometryTest {
    private val x=SpacePoint(1.0,0.0,0.0)
    private val y=SpacePoint(0.0,1.0,0.0)
    private val z=SpacePoint(0.0,0.0,1.0)
    private fun box(p:SpacePoint,half:SpacePoint,axes:List<SpacePoint> = listOf(x,y,z)) = SpaceBox(p,axes,half)
    private val table=PlaneTriangles(listOf(SpacePoint(-2.0,.78,-2.0),SpacePoint(2.0,.78,-2.0),SpacePoint(2.0,.78,2.0),SpacePoint(-2.0,.78,2.0)),listOf(0,1,2,0,2,3))
    @Test fun thinHorizontalCardAboveTableIsClearButRealCrossingIsBlocked() {
        assertTrue(table.isBoxClear(box(SpacePoint(0.0,.85,0.0),SpacePoint(.05,.001,.086)),.025))
        assertFalse(table.isBoxClear(box(SpacePoint(0.0,.79,0.0),SpacePoint(.05,.001,.086)),.025))
        assertFalse(table.isBoxClear(box(SpacePoint(0.0,.85,0.0),SpacePoint(.05,.086,.001)),.025))
    }
    @Test fun slantedPlaneDoesNotFillItsWorldBoundingBox() {
        val diagonal=PlaneTriangles(listOf(SpacePoint(-1.0,-1.0,-1.0),SpacePoint(1.0,-1.0,1.0),SpacePoint(1.0,1.0,1.0),SpacePoint(-1.0,1.0,-1.0)),listOf(0,1,2,0,2,3))
        assertTrue(diagonal.isBoxClear(box(SpacePoint(-.75,0.0,.75),SpacePoint(.05,.086,.002)),.025))
        assertFalse(diagonal.isBoxClear(box(SpacePoint(.2,0.0,.2),SpacePoint(.05,.086,.002)),.025))
    }
    @Test fun orientedCornersAndClearanceAreChecked() {
        val wall=PlaneTriangles(listOf(SpacePoint(0.0,-2.0,-2.0),SpacePoint(0.0,2.0,-2.0),SpacePoint(0.0,2.0,2.0)),listOf(0,1,2))
        assertTrue(wall.isBoxClear(box(SpacePoint(.10,0.0,0.0),SpacePoint(.05,.086,.002)),.025))
        assertFalse(wall.isBoxClear(box(SpacePoint(.06,0.0,0.0),SpacePoint(.05,.086,.002)),.025))
        assertFalse(wall.isBoxClear(box(SpacePoint(.10,0.0,0.0),SpacePoint(.002,.16,.002),listOf(y,x,z)),.025))
    }
    @Test fun emptyOrInvalidGeometryIsNotAnAllClear() {
        assertFalse(PlaneTriangles(emptyList(),emptyList()).isBoxClear(box(SpacePoint(0.0,0.0,0.0),SpacePoint(.1,.1,.1)),.025))
        assertFalse(table.isBoxClear(box(SpacePoint(Double.NaN,0.0,0.0),SpacePoint(.1,.1,.1)),.025))
    }
    @Test fun lowHeadPoseUsesSupportHeightInsteadOfCappedLift() {
        val half=SpacePoint(.05,.085714,.000405)
        val lift=LayoutSupportHeight.minimumLift(.81,.78,half)
        assertTrue(lift>.24)
        assertTrue(table.isBoxClear(box(SpacePoint(0.0,.81-.35+lift,0.0),half),.025))
        assertFalse(table.isBoxClear(box(SpacePoint(0.0,.81-.30+.24,0.0),half),.025))
    }
    @Test fun sideWorkspaceCanAvoidMonitorWithoutCrossingTable() {
        val monitor=PlaneTriangles(listOf(SpacePoint(-.55,.85,-.7),SpacePoint(-.20,.85,-.7),SpacePoint(-.20,1.3,-.7),SpacePoint(-.55,1.3,-.7)),listOf(0,1,2,0,2,3))
        val half=SpacePoint(.05,.085714,.000405)
        assertFalse(monitor.isBoxClear(box(SpacePoint(-.4,1.0,-.7),half),.025))
        assertTrue((0 until 78).all { i ->
            val candidate=box(SpacePoint(.35-.75/2+i*.75/77,1.0,-.70-i*.0009),half)
            monitor.isBoxClear(candidate,.025) && table.isBoxClear(candidate,.025)
        })
    }
}
