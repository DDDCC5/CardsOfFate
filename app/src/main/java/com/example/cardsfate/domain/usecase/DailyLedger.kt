package com.example.cardsfate.domain.usecase

import com.example.cardsfate.domain.model.*
import java.time.LocalDate

/** Pending candidate survives write failures; never replace an unreadable store. */
class DailyLedger(private val load:()->Result<List<DailyRecord>>,private val save:(List<DailyRecord>)->Result<Unit>,private val draw:()->DrawnCard) {
    private val pending=mutableMapOf<String,DailyRecord>()
    fun open(date:String):Result<DailyRecord> = runCatching {
        LocalDate.parse(date)
        val records=load().getOrThrow()
        records.firstOrNull{it.date==date} ?: pending.getOrPut(date){DailyRecord(date,draw())}.also {
            save(retain(records+it)).getOrThrow();pending.remove(date)
        }
    }
    fun complete(record:DailyRecord):Result<DailyRecord> = runCatching {
        val done=record.copy(completed=true)
        val records=load().getOrThrow()
        require(records.any{it.date==record.date && it.card==record.card})
        save(retain(records.filterNot{it.date==record.date}+done)).getOrThrow();done
    }
    private fun retain(records:List<DailyRecord>)=records.distinctBy{it.date}.sortedByDescending{it.date}.take(30)
}
