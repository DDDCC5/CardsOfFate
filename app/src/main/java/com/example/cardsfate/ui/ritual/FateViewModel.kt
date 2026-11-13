package com.example.cardsfate.ui.ritual

import androidx.lifecycle.ViewModel
import com.example.cardsfate.domain.model.*
import com.example.cardsfate.domain.usecase.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID



class FateViewModel(
    private val services: RuntimeServices,
    language: ReadingLanguage = ReadingLanguage.EN,
    initialRitual: RitualState = RitualState(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newRecordId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel(), AutoCloseable {
    private val mutableState = MutableStateFlow(FateUiState(
        ritual = initialRitual, language = language, catalog = services.catalog().toList(),
    ))
    val state: StateFlow<FateUiState> = mutableState
    private val reducer = RitualReducer(state.value.catalog.map { it.id })
    private var closed = false
    private var paused = false
    private var audioGeneration = 0L
    private data class SpeechTask(val ids: List<String>, val completed: () -> Unit)
    private var speechTask: SpeechTask? = null
    private val pendingRecords = linkedMapOf<String, HistoryRecord>()
    private val completedSessions = mutableSetOf<Long>()

    init { reloadHistory() }

    /** Speech completion and summary events are owned by genuine playback callbacks. */
    fun onEvent(event: RitualEvent) {
        if (closed || event is RitualEvent.NarrationCompleted || event is RitualEvent.FinishReading) return
        if (state.value.historyOpen && event != RitualEvent.Reset) return
        if (event == RitualEvent.SystemsReady && !runtimeReady()) return
        dispatch(event)
    }

    private fun dispatch(event: RitualEvent) {
        if (closed) return
        val before = state.value.ritual
        val after = reducer.reduce(before, event)
        if (after == before) return
        mutableState.value = state.value.copy(ritual = after)
        if (event == RitualEvent.Reset) {
            invalidateSpeech()
            mutableState.value = state.value.copy(historyOpen = false, historyDetail = null,
                audioError = null, narrationText = null)
        }
        updateSuspension()
        if (before.phase != after.phase) {
            transitionEffect(after.phase)?.let(::playEffect)
            if (after.phase == RitualPhase.CONTEMPLATE) {
                val id = "guidance/${languageKey()}/question"
                runCatching { services.narration(id) }.fold(onSuccess = {
                    mutableState.value = state.value.copy(narrationText = it.text)
                    speak(listOf(it.id)) {}
                }, onFailure = ::audioFailure)
            } else if (before.phase == RitualPhase.CONTEMPLATE) {
                invalidateSpeech()
                mutableState.value = state.value.copy(narrationText = null)
            }
        }
        if (event == RitualEvent.DrawFromStack && after.selectedIndices != before.selectedIndices) {
            playEffect("select")
        }
        if (event is RitualEvent.RevealCard && after.revealedCount > before.revealedCount) {
            playReading(event.index, after.sessionNumber)
        }
        if (event is RitualEvent.NarrationCompleted && after.revealedCount == 3 && after.narrationCompleted) {
            finishReading()
        }
    }

    /** Rebind display/speech atomically; completed records retain their original persisted language. */
    fun setLanguage(language: ReadingLanguage) {
        val previous = state.value
        if (closed || previous.language == language) return
        invalidateSpeech()
        var localizationFailure: Throwable? = null
        fun <T> resolve(block: () -> T): T? = runCatching(block).getOrElse {
            if (localizationFailure == null) localizationFailure = it
            null
        }
        fun localize(record: HistoryRecord): HistoryRecord = if (record.language == language) record else
            record.copy(summary = resolve { services.summary(record.cards, language).text }.orEmpty(), language = language)

        // Reload originals so toggling languages never compounds a prior display-only translation.
        val loaded = runCatching { services.loadHistory().getOrThrow() }
        val records = newestHistory(loaded.getOrElse { previous.historyRecords }).map(::localize)
        val detail = previous.historyDetail?.let(::localize)
        val ritual = previous.ritual
        val summary = ritual.result?.let { resolve { services.summary(it.cards, language) } }
        val result = ritual.result?.copy(summary = summary?.text.orEmpty())
        val readingIndex = if (ritual.narrationPlaying) ritual.nextRevealIndex else ritual.revealedCount - 1
        val narration = when {
            ritual.phase == RitualPhase.CONTEMPLATE -> resolve {
                services.narration("guidance/${if (language == ReadingLanguage.ZH) "zh" else "en"}/question")
            }
            ritual.phase == RitualPhase.READING && readingIndex in ritual.selectedCards.indices -> resolve {
                services.reading(ritual.selectedCards[readingIndex], language, ReadingPosition.entries[readingIndex])
            }
            else -> null
        }
        mutableState.value = previous.copy(
            language = language,
            ritual = ritual.copy(result = result),
            historyRecords = records,
            historyDetail = detail,
            narrationText = if(ritual.phase==RitualPhase.SUMMARY) null else narration?.text,
            audioError = localizationFailure?.let(::message),
            saveError = loaded.exceptionOrNull()?.let(::message) ?: previous.saveError,
        )
        if (narration != null) {
            speak(listOf(narration.id)) {
                if (ritual.phase == RitualPhase.READING && ritual.narrationPlaying) {
                    dispatch(RitualEvent.NarrationCompleted(readingIndex, ritual.sessionNumber))
                }
            }
        }
    }

    fun setRuntimeStatus(sceneReady: Boolean, spaceReady: Boolean, handsReady: Boolean,
                         controllerActive: Boolean, gazeAvailable: Boolean, spaceDataReady: Boolean = spaceReady) {
        if (closed) return
        mutableState.value = state.value.copy(sceneReady = sceneReady, spaceReady = spaceReady, spaceDataReady = spaceDataReady,
            handTrackingReady = handsReady, controllerActive = controllerActive, gazeAvailable = gazeAvailable)
        updateSuspension()
        state.value.ritual.let {
            if (it.phase == RitualPhase.READING && it.revealedCount == 3 &&
                it.narrationCompleted && !it.narrationPlaying && it.suspension == null) finishReading()
        }
    }

    private fun runtimeReady() = state.value.let {
        it.sceneReady && it.spaceReady && it.handTrackingReady && !it.controllerActive
    }

    private fun updateSuspension() {
        val ui = state.value
        val active = ui.ritual.phase !in setOf(RitualPhase.LOGO, RitualPhase.SAFETY, RitualPhase.SYSTEMS, RitualPhase.SUMMARY)
        val reason = when {
            !active -> null
            ui.controllerActive -> SuspensionReason.CONTROLLER_ACTIVE
            !ui.sceneReady || !ui.spaceReady -> SuspensionReason.SPACE_UNAVAILABLE
            !ui.handTrackingReady -> SuspensionReason.HAND_TRACKING_LOST
            else -> null
        }
        mutableState.value = ui.copy(ritual = reducer.reduce(ui.ritual, RitualEvent.SetSuspension(reason)))
    }

    fun onUiEvent(event: FateUiEvent) {
        when(event) {
            FateUiEvent.ToggleHistory -> toggleHistory()
            FateUiEvent.BackFromHistory -> {
                val current=state.value
                if(!closed && current.historyOpen && ExperiencePolicy.historyAvailable(current.ritual.phase))
                    mutableState.value=current.copy(historyOpen=current.historyDetail!=null,historyDetail=null)
            }
            is FateUiEvent.OpenRecord -> showHistoryRecord(event.record)
        }
    }

    fun toggleHistory() {
        if (closed || !ExperiencePolicy.historyAvailable(state.value.ritual.phase)) return
        mutableState.value = state.value.copy(historyOpen = !state.value.historyOpen, historyDetail = null)
    }

    fun showHistoryRecord(record: HistoryRecord?) {
        if (closed || !ExperiencePolicy.historyAvailable(state.value.ritual.phase)) return
        runCatching { record?.let(::localizedRecord) }.fold(onSuccess = {
            mutableState.value = state.value.copy(historyOpen = true, historyDetail = it)
        }, onFailure = { mutableState.value = state.value.copy(saveError = message(it)) })
    }

    private fun localizedRecord(record: HistoryRecord) = if (record.language == state.value.language) record else
        record.copy(summary = services.summary(record.cards, state.value.language).text, language = state.value.language)

    private fun reloadHistory() {
        runCatching { services.loadHistory().getOrThrow().let(::newestHistory).map(::localizedRecord) }.fold(
            onSuccess = { mutableState.value = state.value.copy(historyRecords = it, saveError = null) },
            onFailure = { mutableState.value = state.value.copy(saveError = message(it)) },
        )
    }

    private fun playReading(index: Int, session: Long) {
        runCatching { services.reading(state.value.ritual.selectedCards[index], state.value.language, ReadingPosition.entries[index]) }
            .fold(onSuccess = { narration ->
                mutableState.value = state.value.copy(narrationText = narration.text)
                playEffect("flip_to_reading")
                speak(listOf(narration.id)) { dispatch(RitualEvent.NarrationCompleted(index, session)) }
            }, onFailure = ::audioFailure)
    }

    private fun finishReading() {
        val ritual = state.value.ritual
        if (ritual.sessionNumber in completedSessions || ritual.suspension != null) return
        runCatching { services.summary(ritual.selectedCards, state.value.language) }.fold(onSuccess = { summary ->
            dispatch(RitualEvent.FinishReading(nowMillis(), summary.text))
            val result = state.value.ritual.result ?: return@fold
            completedSessions += result.sessionNumber
            val record = HistoryRecord(newRecordId(), result.completedAtEpochMillis, result.cards, result.summary, state.value.language)
            pendingRecords[record.id] = record
            retrySave()
            mutableState.value = state.value.copy(narrationText = null)
        }, onFailure = ::audioFailure)
    }

    fun retrySave() {
        if (closed) return
        if (pendingRecords.isEmpty()) { reloadHistory(); return }
        for ((id, record) in pendingRecords.toMap()) {
            val result = runCatching { services.appendHistory(record).getOrThrow() }
            if (result.isFailure) {
                mutableState.value = state.value.copy(saveError = message(result.exceptionOrNull()!!))
                return
            }
            pendingRecords.remove(id)
        }
        reloadHistory()
    }

    private fun speak(ids: List<String>, completed: () -> Unit) {
        invalidateSpeech()
        speechTask = SpeechTask(ids.toList(), completed)
        startCurrentSpeech()
    }

    private fun startCurrentSpeech() {
        if (paused || closed) return
        val task = speechTask ?: return
        val token = ++audioGeneration
        mutableState.value = state.value.copy(audioError = null)
        runCatching {
            services.playSpeech(task.ids, onComplete = {
                if (!closed && !paused && token == audioGeneration) {
                    audioGeneration++
                    speechTask = null
                    task.completed()
                }
            }, onError = {
                if (!closed && !paused && token == audioGeneration) {
                    audioGeneration++
                    audioFailure(it)
                }
            })
        }.onFailure {
            if (!closed && token == audioGeneration) { audioGeneration++; audioFailure(it) }
        }
    }

    private fun invalidateSpeech() {
        audioGeneration++
        speechTask = null
        services.stopSpeech()
    }

    fun retryAudio() {
        if (closed || paused) return
        if (speechTask != null) startCurrentSpeech()
        else state.value.ritual.let { ritual ->
            if (ritual.phase == RitualPhase.READING && ritual.narrationPlaying) playReading(ritual.nextRevealIndex, ritual.sessionNumber)
            else if (ritual.phase == RitualPhase.READING && ritual.revealedCount == 3 && ritual.narrationCompleted) finishReading()
        }
    }

    fun pauseAudio() {
        if (closed || paused) return
        paused = true
        audioGeneration++
        services.stopSpeech()
    }

    fun resumeAudio() {
        if (closed || !paused) return
        paused = false
        startCurrentSpeech()
    }

    fun playEffect(id: String) {
        if (closed || paused) return
        runCatching { services.playEffect(id).getOrThrow() }.onFailure(::audioFailure)
    }

    fun title(cardId: String): String = services.title(cardId, state.value.language)
    private fun languageKey() = if (state.value.language == ReadingLanguage.ZH) "zh" else "en"
    private fun audioFailure(error: Throwable) { mutableState.value = state.value.copy(audioError = message(error)) }
    private fun message(error: Throwable) = error.message ?: error.javaClass.simpleName
    private fun transitionEffect(phase: RitualPhase): String? = when (phase) {
        RitualPhase.CONTEMPLATE -> "orbit_to_flip"
        RitualPhase.GATHER -> "flip_to_gather"
        RitualPhase.DRAW -> "shuffle_to_draw"
        RitualPhase.SPREAD -> "draw_to_spread"
        RitualPhase.SUMMARY -> "reading_to_summary"
        else -> null
    }

    override fun close() {
        if (closed) return
        closed = true
        invalidateSpeech()
        services.release()
    }

    override fun onCleared() { close() }
}
