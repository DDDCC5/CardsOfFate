package com.example.cardsfate.domain.model

import kotlin.math.*

data class AtmosphereItem(val asset:String,val position:SpacePoint,val clearance:Float,val scale:Float=1f)

/** Reuse the approved resources; positive Z is behind the fixed ritual front. */
object AtmosphereLayout {
    val items:List<AtmosphereItem> = listOf(
        AtmosphereItem("crystal",SpacePoint(-1.15,.08,-.85),.20f,.9f),
        AtmosphereItem("crystal",SpacePoint(-.85,.18,1.10),.20f,.8f),
        AtmosphereItem("crystal",SpacePoint(.85,.10,1.15),.20f,.8f),
        AtmosphereItem("astrolabe",SpacePoint(1.15,.15,-.90),.23f),
        AtmosphereItem("astrolabe",SpacePoint(0.0,.28,1.45),.27f,1.15f),
    ) + (0 until 36).map { index ->
        val angle=index*2.0*PI/36.0
        AtmosphereItem("rune_${index%4}",SpacePoint(cos(angle)*1.55,.02+sin(index*.8)*.12,sin(angle)*1.55),.06f)
    } + (0 until 12).map { index ->
        val angle=PI*(.20+.60*index/11.0)
        AtmosphereItem("rune_${index%4}",SpacePoint(cos(angle)*1.28,.38+sin(index*1.4)*.14,sin(angle)*1.28),.06f,.65f)
    }
}
