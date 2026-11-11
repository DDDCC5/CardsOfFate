package com.example.cardsfate.ui.ritual

import android.content.Context
import com.example.cardsfate.data.repository.*
import com.example.cardsfate.domain.model.*
import com.example.cardsfate.domain.usecase.*
import com.example.cardsfate.platform.audio.AudioPlayer
import java.io.File
import java.util.Locale

/** Process owner: Activity recreation reuses consent and the active ritual; nothing persists consent. */
object FateViewModelFactory : androidx.lifecycle.ViewModelStoreOwner {
    override val viewModelStore = androidx.lifecycle.ViewModelStore()
    fun providerFactory(context: Context) = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FateViewModel::class.java))
            return get(context) as T
        }
    }
    private var processViewModel: FateViewModel? = null

    @Synchronized fun get(context: Context): FateViewModel {
        val language = readingLanguageFor(Locale.getDefault().language)
        processViewModel?.let {
            it.setLanguage(language)
            return it
        }
        val app = context.applicationContext
        val services = LocalRuntimeServices(AssetCardRepository(app.assets),
            AtomicHistoryRepository(File(app.filesDir, "cards-fate-history.json")), AudioPlayer(app))
        return FateViewModel(services, language).also { processViewModel = it }
    }

    /** Call only when releasing the process owner, never from Activity/Compose disposal. */
    @Synchronized fun release() {
        viewModelStore.clear()
        processViewModel?.close()
        processViewModel = null
    }
}

private class LocalRuntimeServices(
    private val cards: CardRepository,
    private val history: HistoryRepository,
    private val audio: AudioPlayer,
) : RuntimeServices {
    override fun catalog() = cards.cards()
    override fun title(cardId: String, language: ReadingLanguage) = cards.title(cardId, language)
    override fun narration(id: String) = cards.narration(id)
    override fun reading(card: DrawnCard, language: ReadingLanguage, position: ReadingPosition) = cards.reading(card, language, position)
    override fun summary(cards: List<DrawnCard>, language: ReadingLanguage) = this.cards.summary(cards, language)
    override fun loadHistory() = history.load()
    override fun appendHistory(record: HistoryRecord) = history.append(record)
    override fun playSpeech(sequenceIds: List<String>, onComplete: () -> Unit, onError: (Throwable) -> Unit) =
        audio.playSummary(sequenceIds, onComplete, onError)
    override fun playEffect(id: String) = audio.playEffect(id)
    override fun stopSpeech() = audio.stopSpeech()
    override fun release() = audio.release()
}
