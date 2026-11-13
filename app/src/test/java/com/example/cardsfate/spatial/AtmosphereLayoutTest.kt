package com.example.cardsfate.spatial

import com.example.cardsfate.domain.model.AtmosphereLayout
import org.junit.Assert.*
import org.junit.Test

class AtmosphereLayoutTest {
    @Test fun persistentSetUsesNoCandlesAndExpandsBehindTheUser() {
        val items=AtmosphereLayout.items
        assertTrue(items.none { it.asset.contains("candle") })
        assertTrue(items.size>28)
        val rear=items.filter { it.position.z>0 }
        assertTrue(rear.size>=24)
        assertTrue(rear.count { it.asset=="crystal" }>=2)
        assertTrue(rear.any { it.asset=="astrolabe" })
        assertTrue(items.all { it.position.finite() && it.clearance>0 })
    }
}
