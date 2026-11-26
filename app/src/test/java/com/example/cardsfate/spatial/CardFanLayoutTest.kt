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
    @Test fun labelsHaveClearanceBelowCardsAndAboveTheFan() {
        for(slot in 0..2) {
            val card=CardFanLayout.slotOffset(slot);val label=CardFanLayout.labelOffset(slot)
            assertEquals(card.x,label.x,0.0);assertEquals(card.z,label.z,0.0)
            assertTrue(card.y-CardFanLayout.CARD_HEIGHT_M/2-label.y>=.069)
        }
        val highestFan=(0..12).maxOf { index ->
            val halfHeight=kotlin.math.abs(kotlin.math.sin(CardFanLayout.roll(index)))*CardFanLayout.CARD_WIDTH_M/2+
                kotlin.math.abs(kotlin.math.cos(CardFanLayout.roll(index)))*CardFanLayout.CARD_HEIGHT_M/2
            CardFanLayout.offset(index).y+halfHeight
        }
        assertTrue(CardFanLayout.labelOffset(1).y-highestFan>.02)
    }
    @Test fun cardCenterMustEnterAnEmptySlot() {
        assertEquals(0,CardFanLayout.emptySlotAt(SpacePoint(-.15,.14,0.0),emptySet()))
        assertNull(CardFanLayout.emptySlotAt(SpacePoint(-.15,.14,0.0),setOf(0)))
        assertNull(CardFanLayout.emptySlotAt(SpacePoint(-.22,.14,0.0),emptySet()))
        assertNull(CardFanLayout.emptySlotAt(SpacePoint(0.0,.14,.08),emptySet()))
        assertNull(CardFanLayout.emptySlotAt(SpacePoint(Double.NaN,0.0,0.0),emptySet()))
    }
}
