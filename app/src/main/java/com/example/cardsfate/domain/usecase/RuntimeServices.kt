package com.example.cardsfate.domain.usecase

import com.example.cardsfate.domain.model.*

/** Synchronous local metadata/storage and asynchronous speech, called on the runtime owner's thread. */
interface RuntimeServices {
    fun catalog(): List<CatalogCard>
    fun title(cardId: String, language: ReadingLanguage): String
    fun narration(id: String): NarrationUnit
    fun reading(card: DrawnCard, language: ReadingLanguage, position: ReadingPosition): NarrationUnit
    fun summary(cards: List<DrawnCard>, language: ReadingLanguage): ReadingSummary
    fun loadHistory(): Result<List<HistoryRecord>>
    fun appendHistory(record: HistoryRecord): Result<Unit>
    fun playSpeech(sequenceIds: List<String>, onComplete: () -> Unit, onError: (Throwable) -> Unit)
    fun playEffect(id: String): Result<Unit>
    fun stopSpeech()
    fun release()
}

fun readingLanguageFor(systemLanguage: String): ReadingLanguage =
    if (systemLanguage.substringBefore('-').equals("zh", ignoreCase = true)) ReadingLanguage.ZH else ReadingLanguage.EN

interface LlmInterpreter {
    fun interpret(cards: List<DrawnCard>, language: ReadingLanguage): Result<ReadingSummary>
}
data class TarotSystem(val id: String, val name: String, val cardIds: List<String>)
interface TarotSystemProvider { fun systems(): List<TarotSystem> }
interface PaidReadingGateway {
    fun isEntitled(productId: String): Boolean
    fun requestPurchase(productId: String): Result<Unit>
}
