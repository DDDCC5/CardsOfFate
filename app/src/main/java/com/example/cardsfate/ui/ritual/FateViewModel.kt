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
    private val today:()->String = { java.time.LocalDate.now().toString() },
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

    private val daily=DailyLedger(services::loadDaily,services::saveDaily) {
        DrawnCard(state.value.catalog.random().id,kotlin.random.Random.nextBoolean())
    }
    private var pendingDailyDate:String?=null
    private var pendingDailyCompletion=false
    init {
        reloadHistory()
        services.loadComfort().fold(onSuccess={ mutableState.value=state.value.copy(comfort=it,comfortDraft=it) },
            onFailure={mutableState.value=state.value.copy(comfortError=message(it))})
    }

    fun startDaily() {
        if(closed || state.value.ritual.phase!=RitualPhase.SYSTEMS || state.value.historyOpen || state.value.comfortOpen || !runtimeReady()) return
        val date=pendingDailyDate ?: today().also { pendingDailyDate=it }
        daily.open(date).fold(onSuccess={ record ->
            pendingDailyDate=null
            mutableState.value=state.value.copy(dailyRecord=record,dailyLoadError=null,focusedReadingIndex=if(record.completed) 0 else null)
            dispatch(RitualEvent.StartDaily(record.card,record.completed))
            if(record.completed) dailyNarration()?.let { mutableState.value=state.value.copy(readingDisplayText=it.text) }
        },onFailure={mutableState.value=state.value.copy(dailyLoadError=message(it))})
    }
    private fun dailyNarration(language:ReadingLanguage=state.value.language):NarrationUnit? {
        val card=state.value.dailyRecord?.card ?: return null
        return services.narration("daily/${if(language==ReadingLanguage.ZH) "zh" else "en"}/${card.cardId}/${if(card.reversed) "reversed" else "upright"}")
    }
    fun returnToStart() {
        if(closed) return
        pendingDailyCompletion=false;pendingDailyDate=null
        dispatch(RitualEvent.Reset)
    }
    fun openComfort() {
        if(closed || state.value.ritual.phase!=RitualPhase.SYSTEMS || state.value.historyOpen) return
        mutableState.value=state.value.copy(comfortOpen=true,comfortDraft=state.value.comfort,comfortValidation=null)
    }
    fun setComfort(draft:ComfortPreference) {
        if(state.value.comfortOpen) mutableState.value=state.value.copy(comfortDraft=draft,comfortValidation=null)
    }
    fun recenterComfort() { if(state.value.comfortOpen) mutableState.value=state.value.copy(recenterRequest=state.value.recenterRequest+1,comfortValidation=null) }
    fun setComfortValidation(valid:Boolean) { if(state.value.comfortOpen && state.value.comfortValidation!=valid) mutableState.value=state.value.copy(comfortValidation=valid) }
    fun cancelComfort() { mutableState.value=state.value.copy(comfortOpen=false,comfortDraft=state.value.comfort,comfortValidation=null) }
    fun confirmComfort() {
        if(!state.value.comfortOpen || state.value.comfortValidation!=true || !runtimeReady()) return
        services.saveComfort(state.value.comfortDraft).fold(onSuccess={mutableState.value=state.value.copy(comfort=state.value.comfortDraft,comfortOpen=false,comfortError=null,comfortCommit=state.value.comfortCommit+1)},
            onFailure={mutableState.value=state.value.copy(comfortError=message(it))})
    }
    fun pauseNarration() {
        if(closed || !state.value.ritual.narrationPlaying) return
        mutableState.value=state.value.copy(speechPaused=true);services.pauseSpeech()
    }
    fun resumeNarration() {
        if(closed || !state.value.speechPaused) return
        mutableState.value=state.value.copy(speechPaused=false)
        if(!paused && !services.resumeSpeech()) startCurrentSpeech()
    }
    fun skipNarration() {
        if(closed || !state.value.ritual.narrationPlaying) return
        val task=speechTask ?: return
        invalidateSpeech();task.completed()
    }

    /** Speech completion and summary events are owned by genuine playback callbacks. */
    fun onEvent(event: RitualEvent) {
        if (closed || event is RitualEvent.NarrationCompleted || event is RitualEvent.FinishReading || event is RitualEvent.StartDaily) return
        if (state.value.historyOpen && event != RitualEvent.Reset) return
        if (event == RitualEvent.SystemsReady && (!runtimeReady() || state.value.comfortOpen)) return
        if (event is RitualEvent.ChangeDrawPage && state.value.isHoldingCard) return
        dispatch(event)
    }

    fun setHoldingCard(holding: Boolean) {
        if (closed || state.value.isHoldingCard == holding) return
        mutableState.value = state.value.copy(isHoldingCard = holding)
    }

    /** Inspecting a revealed card never owns or alters sequential speech completion. */
    fun keywordForSlot(index:Int):String {
        val ui=state.value
        if(index !in 0 until ui.ritual.revealedCount) return ""
        val card=ui.ritual.selectedCards.getOrNull(index) ?: return ""
        val lang=if(ui.language==ReadingLanguage.ZH) "zh" else "en"
        val direction=if(card.reversed) "reversed" else "upright"
        return runCatching { services.narration("keyword/$lang/${card.cardId}/$direction").text }.getOrDefault("")
    }

    fun focusRevealedCard(index: Int) {
        val ui = state.value
        val ritual = ui.ritual
        if (closed || ritual.phase !in setOf(RitualPhase.READING, RitualPhase.SUMMARY) ||
            index !in 0 until ritual.revealedCount) return
        val card = ritual.selectedCards.getOrNull(index) ?: return
        runCatching { if(ritual.mode==RitualMode.DAILY) requireNotNull(dailyNarration()) else services.reading(card, ui.language, ReadingPosition.entries[index]) }
            .onSuccess { reading ->
                mutableState.value = state.value.copy(focusedReadingIndex = index, readingDisplayText = reading.text)
            }
    }

    private fun dispatch(event: RitualEvent) {
        if (closed) return
        val before = state.value.ritual
        val after = reducer.reduce(before, event)
        if (after == before) return
        mutableState.value = state.value.copy(ritual = after)
        if (event == RitualEvent.Reset) {
            pendingDailyCompletion=false;pendingDailyDate=null
            invalidateSpeech()
            mutableState.value = state.value.copy(historyOpen = false, historyDetail = null,
                audioError = null, narrationText = null, isHoldingCard = false, speechPaused=false, comfortOpen=false, dailyLoadError=null,
                focusedReadingIndex = null, readingDisplayText = null)
        }
        updateSuspension()
        if (before.phase != after.phase) {
            transitionEffect(after.phase)?.let(::playEffect)
            if (after.phase == RitualPhase.CONTEMPLATE) {
                val id = if(after.mode==RitualMode.DAILY) "daily-guidance/${languageKey()}/question" else "guidance/${languageKey()}/question"
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
            mutableState.value = state.value.copy(focusedReadingIndex = event.index, readingDisplayText = null)
            playReading(event.index, after.sessionNumber, focusDisplay = true)
        }
        if (event is RitualEvent.NarrationCompleted && after.revealedCount == after.targetCount && after.narrationCompleted) {
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
        val summary = ritual.result?.let { resolve { if(ritual.mode==RitualMode.DAILY) ReadingSummary(requireNotNull(dailyNarration(language)).text,emptyList()) else services.summary(it.cards, language) } }
        val result = ritual.result?.copy(summary = summary?.text.orEmpty())
        val readingIndex = if (ritual.narrationPlaying) ritual.nextRevealIndex else ritual.revealedCount - 1
        val focusedReading = previous.focusedReadingIndex?.takeIf {
            ritual.phase in setOf(RitualPhase.READING, RitualPhase.SUMMARY) && it in 0 until ritual.revealedCount
        }?.let { index -> resolve {
            if(ritual.mode==RitualMode.DAILY) services.narration("daily/${if(language==ReadingLanguage.ZH) "zh" else "en"}/${ritual.selectedCards[index].cardId}/${if(ritual.selectedCards[index].reversed) "reversed" else "upright"}") else services.reading(ritual.selectedCards[index], language, ReadingPosition.entries[index])
        } }
        val narration = when {
            ritual.phase == RitualPhase.CONTEMPLATE -> resolve {
                services.narration("${if(ritual.mode==RitualMode.DAILY) "daily-guidance" else "guidance"}/${if (language == ReadingLanguage.ZH) "zh" else "en"}/question")
            }
            ritual.phase == RitualPhase.READING && ritual.narrationPlaying && readingIndex in ritual.selectedCards.indices -> resolve {
                if(ritual.mode==RitualMode.DAILY) services.narration("daily/${if(language==ReadingLanguage.ZH) "zh" else "en"}/${ritual.selectedCards[readingIndex].cardId}/${if(ritual.selectedCards[readingIndex].reversed) "reversed" else "upright"}") else services.reading(ritual.selectedCards[readingIndex], language, ReadingPosition.entries[readingIndex])
            }
            else -> null
        }
        mutableState.value = previous.copy(
            language = language,
            ritual = ritual.copy(result = result),
            historyRecords = records,
            historyDetail = detail,
            narrationText = if(ritual.phase==RitualPhase.SUMMARY) null else narration?.text,
            readingDisplayText = focusedReading?.text,
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
            handTrackingReady = handsReady, controllerActive = controllerActive, gazeAvailable = gazeAvailable,
            comfortValidation=if(!sceneReady || !spaceReady) null else state.value.comfortValidation)
        updateSuspension()
        state.value.ritual.let {
            if (it.phase == RitualPhase.READING && it.revealedCount == it.targetCount &&
                it.narrationCompleted && !it.narrationPlaying && it.suspension == null && !pendingDailyCompletion) finishReading()
        }
    }

    private fun runtimeReady() = state.value.let {
        it.sceneReady && it.spaceReady && it.handTrackingReady && !it.controllerActive
    }

    private fun updateSuspension() {
        val ui = state.value
        val active = ui.ritual.phase !in setOf(RitualPhase.LOGO, RitualPhase.SAFETY, RitualPhase.SYSTEMS)
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

    private fun playReading(index: Int, session: Long, focusDisplay: Boolean = false) {
        runCatching { if(state.value.ritual.mode==RitualMode.DAILY) requireNotNull(dailyNarration()) else services.reading(state.value.ritual.selectedCards[index], state.value.language, ReadingPosition.entries[index]) }
            .fold(onSuccess = { narration ->
                mutableState.value = state.value.copy(
                    narrationText = narration.text,
                    readingDisplayText = if (focusDisplay || state.value.focusedReadingIndex == index) narration.text
                        else state.value.readingDisplayText,
                )
                playEffect("flip_to_reading")
                speak(listOf(narration.id)) { dispatch(RitualEvent.NarrationCompleted(index, session)) }
            }, onFailure = ::audioFailure)
    }

    private fun finishReading() {
        val ritual = state.value.ritual
        if (ritual.sessionNumber in completedSessions || ritual.suspension != null) return
        if(ritual.mode==RitualMode.DAILY) {
            val record=state.value.dailyRecord ?: return
            daily.complete(record).fold(onSuccess={
                pendingDailyCompletion=false
                mutableState.value=state.value.copy(dailyRecord=it,saveError=null)
                dispatch(RitualEvent.FinishReading(nowMillis(),dailyNarration()?.text.orEmpty()))
            },onFailure={pendingDailyCompletion=true;mutableState.value=state.value.copy(saveError=message(it))})
            return
        }
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
        if(pendingDailyCompletion) { finishReading();return }
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
        if (paused || closed || state.value.speechPaused) return
        val task = speechTask ?: return
        val token = ++audioGeneration
        mutableState.value = state.value.copy(audioError = null)
        runCatching {
            services.playSpeech(task.ids, onComplete = {
                if (!closed && !paused && !state.value.speechPaused && token == audioGeneration) {
                    audioGeneration++
                    speechTask = null
                    task.completed()
                }
            }, onError = {
                if (!closed && !paused && !state.value.speechPaused && token == audioGeneration) {
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
        mutableState.value=state.value.copy(speechPaused=false)
        services.stopSpeech()
    }

    fun retryAudio() {
        if (closed || paused) return
        if (speechTask != null) startCurrentSpeech()
        else state.value.ritual.let { ritual ->
            if (ritual.phase == RitualPhase.READING && ritual.narrationPlaying) playReading(ritual.nextRevealIndex, ritual.sessionNumber)
            else if (ritual.phase == RitualPhase.READING && ritual.revealedCount == ritual.targetCount && ritual.narrationCompleted) finishReading()
        }
    }

    fun pauseAudio() {
        if (closed || paused) return
        paused = true
        services.pauseSpeech()
    }

    fun resumeAudio() {
        if (closed || !paused) return
        paused = false
        if(!state.value.speechPaused && !services.resumeSpeech()) startCurrentSpeech()
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
