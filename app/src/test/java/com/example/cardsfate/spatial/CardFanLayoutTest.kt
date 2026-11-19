package com.example.cardsfate.spatial

import com.example.cardsfate.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class CardFanLayoutTest {
    @Test fun sixPagesCoverEveryCardExactlyOnce() {
        val indices=(0..5).flatMap { CardFanLayout.indices(it).toList() }
        assertEquals((0..77).toList(),indices);assertEquals(78,indices.distinct().size)
    }
    @Test fun completeFanIsReachableAndSymmetric() {
        val left=CardFanLayout.offset(0);val right=CardFanLayout.offset(12)
        assertEquals(-left.x,right.x,1e-9)
        assertEquals(.56,right.x-left.x+CardFanLayout.CARD_WIDTH_M*kotlin.math.cos(kotlin.math.PI/6)+CardFanLayout.CARD_HEIGHT_M*kotlin.math.sin(kotlin.math.PI/6),1e-9)
        assertEquals(-CardFanLayout.roll(0),CardFanLayout.roll(12),1e-9)
    }
    @Test fun cardCenterMustEnterAnEmptySlot() {
        assertEquals(0,CardFanLayout.emptySlotAt(SpacePoint(-.15,.14,0.0),emptySet()))
        assertNull(CardFanLayout.emptySlotAt(SpacePoint(-.15,.14,0.0),setOf(0)))
        assertNull(CardFanLayout.emptySlotAt(SpacePoint(-.22,.14,0.0),emptySet()))
        assertNull(CardFanLayout.emptySlotAt(SpacePoint(0.0,.14,.08),emptySet()))
        assertNull(CardFanLayout.emptySlotAt(SpacePoint(Double.NaN,0.0,0.0),emptySet()))
    }
}
