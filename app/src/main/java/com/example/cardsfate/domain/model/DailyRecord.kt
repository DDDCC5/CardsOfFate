package com.example.cardsfate.domain.model

data class DailyRecord(val date:String,val card:DrawnCard,val completed:Boolean=false)
enum class RitualMode { THREE, DAILY }
data class ComfortPreference(val distance:Double=.42,val height:Double=0.0) {
    init { require(distance in listOf(.42,.52) && height in listOf(-.06,0.0,.06)) }
    val offset get()=SpacePoint(0.0,-.18+height,-distance)
}
