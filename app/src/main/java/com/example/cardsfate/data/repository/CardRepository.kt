package com.example.cardsfate.data.repository

import android.content.res.AssetManager
import com.example.cardsfate.domain.model.DrawnCard
import com.example.cardsfate.domain.model.ReadingLanguage
import com.example.cardsfate.domain.model.*
import org.json.JSONArray
import org.json.JSONObject


interface CardRepository {
    fun cards(): List<CatalogCard>
    fun title(cardId: String, language: ReadingLanguage): String
    fun narration(id: String): NarrationUnit
    fun reading(card: DrawnCard, language: ReadingLanguage, position: ReadingPosition): NarrationUnit
    fun keyword(card: DrawnCard, language: ReadingLanguage): NarrationUnit
    fun summaryConnector(language: ReadingLanguage, key: String): NarrationUnit
    fun summary(cards: List<DrawnCard>, language: ReadingLanguage): ReadingSummary
}

/** Approved bundled text/audio only. Construction reports malformed/missing assets by throwing. */
class AssetCardRepository(assets: AssetManager) : CardRepository {
    private val catalog: List<CatalogCard>
    private val units: Map<String, NarrationUnit>

    init {
        val rawCards = JSONArray(assets.open("data/card-catalog.json").bufferedReader().use { it.readText() })
        catalog = (0 until rawCards.length()).map { i -> rawCards.getJSONObject(i).let {
            CatalogCard(it.getString("id"), it.getString("zh"), it.getString("en"))
        } }
        require(catalog.size == 78 && catalog.map { it.id }.distinct().size == 78) { "Invalid card catalog" }
        val rawUnits = JSONObject(assets.open("data/narration.json").bufferedReader().use { it.readText() }).getJSONArray("units")
        val parsed = (0 until rawUnits.length()).map { i -> rawUnits.getJSONObject(i).let {
            val id = it.getString("id")
            require(id.matches(Regex("[a-z0-9/-]+")) && ".." !in id) { "Invalid narration ID" }
            NarrationUnit(id, it.getString("text"), "audio/narration/$id.ogg")
        } }
        require(parsed.map { it.id }.distinct().size == parsed.size) { "Duplicate narration ID" }
        units = parsed.associateBy { it.id }
    }

    override fun cards(): List<CatalogCard> = catalog.toList()
    override fun title(cardId: String, language: ReadingLanguage): String {
        val card = catalog.firstOrNull { it.id == cardId } ?: error("Unknown card: $cardId")
        return if (language == ReadingLanguage.ZH) card.zh else card.en
    }
    override fun narration(id: String): NarrationUnit = units[id] ?: error("Missing narration: $id")
    override fun reading(card: DrawnCard, language: ReadingLanguage, position: ReadingPosition) =
        narration("reading/${language.key}/${card.cardId}/${card.orientation}/${position.key}")
    override fun keyword(card: DrawnCard, language: ReadingLanguage) =
        narration("keyword/${language.key}/${card.cardId}/${card.orientation}")
    override fun summaryConnector(language: ReadingLanguage, key: String) = narration("summary/${language.key}/$key")

    override fun summary(cards: List<DrawnCard>, language: ReadingLanguage): ReadingSummary {
        require(cards.size == 3) { "A summary requires three cards in past/present/future order" }
        val sequence = buildList {
            add(summaryConnector(language, "intro"))
            cards.forEachIndexed { index, card ->
                add(summaryConnector(language, ReadingPosition.entries[index].key))
                add(keyword(card, language))
            }
            add(summaryConnector(language, "ending"))
        }
        // Display and speech use the exact same complete approved units, never truncated mid-clip.
        val text = sequence.joinToString(if (language == ReadingLanguage.ZH) "" else " ") { it.text }
        require(if (language == ReadingLanguage.ZH) text.codePointCount(0, text.length) <= 100
            else text.trim().split(Regex("\\s+")).size <= 60) { "Approved summary exceeds length budget" }
        return ReadingSummary(text, sequence.map { it.id })
    }
}

private val ReadingLanguage.key get() = if (this == ReadingLanguage.ZH) "zh" else "en"
private val DrawnCard.orientation get() = if (reversed) "reversed" else "upright"
