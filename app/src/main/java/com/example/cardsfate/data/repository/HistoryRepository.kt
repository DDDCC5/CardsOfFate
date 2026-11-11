package com.example.cardsfate.data.repository

import android.util.AtomicFile
import com.example.cardsfate.domain.model.DrawnCard
import com.example.cardsfate.domain.model.HistoryRecord
import com.example.cardsfate.domain.model.ReadingLanguage
import com.example.cardsfate.domain.usecase.newestHistory
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

interface HistoryRepository {
    fun load(): Result<List<HistoryRecord>>
    fun save(records: List<HistoryRecord>): Result<Unit>
    fun append(record: HistoryRecord): Result<Unit>
}

/** Serialized per instance. Use one application-owned instance for each file. */
class AtomicHistoryRepository(file: File) : HistoryRepository {
    private val atomic = AtomicFile(file)

    @Synchronized override fun load(): Result<List<HistoryRecord>> = runCatching {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) emptyList() else {
            val root = JSONObject(atomic.openRead().bufferedReader().use { it.readText() })
            require(root.getInt("schemaVersion") == 1) { "Unsupported history version" }
            val records = root.getJSONArray("records")
            newestHistory((0 until records.length()).map { index -> decode(records.getJSONObject(index)) })
        }
    }

    @Synchronized override fun save(records: List<HistoryRecord>): Result<Unit> = runCatching {
        // Never replace an unreadable history file with an empty/default list.
        load().getOrThrow()
        val array = JSONArray()
        newestHistory(records).forEach { record ->
            validate(record)
            val cards = JSONArray()
            record.cards.forEach { cards.put(JSONObject().put("cardId", it.cardId).put("reversed", it.reversed)) }
            array.put(JSONObject().put("id", record.id).put("completedAtEpochMillis", record.completedAtEpochMillis)
                .put("cards", cards).put("summary", record.summary).put("language", record.language.name))
        }
        val bytes = JSONObject().put("schemaVersion", 1).put("records", array).toString().toByteArray(Charsets.UTF_8)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    @Synchronized override fun append(record: HistoryRecord): Result<Unit> =
        load().fold(onSuccess = { save(it + record) }, onFailure = { Result.failure(it) })

    private fun decode(value: JSONObject): HistoryRecord {
        val cards = value.getJSONArray("cards")
        return HistoryRecord(value.getString("id"), value.getLong("completedAtEpochMillis"),
            (0 until cards.length()).map { i -> cards.getJSONObject(i).let {
                DrawnCard(it.getString("cardId"), it.getBoolean("reversed"))
            } }, value.getString("summary"), ReadingLanguage.valueOf(value.getString("language"))).also(::validate)
    }
    private fun validate(record: HistoryRecord) {
        require(record.id.isNotBlank() && record.completedAtEpochMillis >= 0 && record.cards.size == 3)
        require(record.cards.map { it.cardId }.distinct().size == 3 && record.cards.all { it.cardId.isNotBlank() })
        require(record.summary.isNotBlank())
    }
}
