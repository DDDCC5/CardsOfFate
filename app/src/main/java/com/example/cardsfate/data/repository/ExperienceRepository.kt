package com.example.cardsfate.data.repository

import android.util.AtomicFile
import com.example.cardsfate.domain.model.*
import com.example.cardsfate.domain.usecase.standardCardIds
import java.io.File
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** A single atomic file serializes both preferences and dated results, without clearing unreadable data. */
class ExperienceRepository(file:File) {
    private val atomic=AtomicFile(file)
    private fun read():JSONObject {
        if(!atomic.baseFile.exists() && !File(atomic.baseFile.path+".bak").exists()) return JSONObject().put("schemaVersion",1)
        return JSONObject(atomic.openRead().bufferedReader().use{it.readText()}).also { require(it.getInt("schemaVersion")==1) }
    }
    private fun write(root:JSONObject) {
        val stream=atomic.startWrite()
        try { stream.write(root.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(stream) }
        catch(e:Exception){atomic.failWrite(stream);throw e}
    }
    @Synchronized fun loadDaily():Result<List<DailyRecord>> = runCatching {
        val a=read().optJSONArray("daily") ?: JSONArray()
        val result=(0 until a.length()).map { i -> a.getJSONObject(i).let {
            val date=it.getString("date");LocalDate.parse(date)
            val id=it.getString("cardId");require(id in standardCardIds())
            DailyRecord(date,DrawnCard(id,it.getBoolean("reversed")),it.getBoolean("completed"))
        } }
        require(result.map{it.date}.distinct().size==result.size);result
    }
    @Synchronized fun saveDaily(records:List<DailyRecord>):Result<Unit> = runCatching {
        val root=read();val array=JSONArray()
        records.sortedByDescending{it.date}.take(30).forEach {
            LocalDate.parse(it.date);require(it.card.cardId in standardCardIds())
            array.put(JSONObject().put("date",it.date).put("cardId",it.card.cardId).put("reversed",it.card.reversed).put("completed",it.completed))
        }
        write(root.put("daily",array))
    }
    @Synchronized fun loadComfort():Result<ComfortPreference> = runCatching {
        val c=read().optJSONObject("comfort") ?: return@runCatching ComfortPreference()
        ComfortPreference(c.getDouble("distance"),c.getDouble("height"))
    }
    @Synchronized fun saveComfort(c:ComfortPreference):Result<Unit> = runCatching {
        write(read().put("comfort",JSONObject().put("distance",c.distance).put("height",c.height)))
    }
}
