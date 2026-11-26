package com.example.cardsfate.domain.model

import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Authoritative meter-valued local layout. Stage transforms are applied only by the scene owner. */
object CardFanLayout {
    const val PAGE_SIZE = 13
    const val PAGE_COUNT = 6
    const val WIDTH_M = .56
    const val CARD_WIDTH_M = .075
    const val CARD_HEIGHT_M = .12
    fun indices(page: Int): IntRange {
        require(page in 0 until PAGE_COUNT)
        return page * PAGE_SIZE until (page + 1) * PAGE_SIZE
    }
    fun offset(indexOnPage: Int): SpacePoint {
        require(indexOnPage in 0 until PAGE_SIZE)
        val center = indexOnPage - 6
        // 56 cm refers to the complete fan, including the outer card body.
        return SpacePoint(center * ((WIDTH_M-(CARD_WIDTH_M*cos(PI/6.0)+CARD_HEIGHT_M*sin(PI/6.0)))/12.0), -.08-abs(center)*.003, center*.0008)
    }
    fun roll(indexOnPage: Int): Double {
        require(indexOnPage in 0 until PAGE_SIZE)
        return -(indexOnPage-6)*PI/36.0
    }
    fun slotOffset(slot: Int): SpacePoint {
        require(slot in 0..2)
        return SpacePoint((slot-1)*.15,.14,0.0)
    }
    /** Label anchor is 13cm below the card centre (at least 7cm below its bottom edge). */
    fun labelOffset(slot:Int):SpacePoint {
        val row=slotOffset(slot)
        return SpacePoint(row.x,row.y-.13,row.z)
    }
    fun emptySlotAt(center: SpacePoint, occupied: Set<Int>): Int? {
        if (!center.finite()) return null
        return (0..2).filterNot(occupied::contains).firstOrNull { slot ->
            val delta=center-slotOffset(slot)
            abs(delta.x)<=.045 && abs(delta.y)<=.065 && abs(delta.z)<=.06
        }
    }
}
