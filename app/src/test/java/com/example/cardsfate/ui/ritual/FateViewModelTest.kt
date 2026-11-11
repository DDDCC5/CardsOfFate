package com.example.cardsfate.ui.ritual

import com.example.cardsfate.domain.model.*
import com.example.cardsfate.domain.usecase.*
import org.junit.Assert.*
import org.junit.Test

class FateViewModelTest {
    private class Speech(val ids: List<String>, val complete: () -> Unit, val fail: (Throwable) -> Unit)
    private class LocalServices : RuntimeServices {
        val speeches = mutableListOf<Speech>()
        val stored = mutableListOf<HistoryRecord>()
        var failSave = false
        var released = false
        override fun catalog() = standardCardIds().map { CatalogCard(it, "中文$it", "English $it") }
        override fun title(cardId: String, language: ReadingLanguage) = if (language == ReadingLanguage.ZH) "中文$cardId" else "English $cardId"
        override fun narration(id: String) = NarrationUnit(id, "text:$id", "audio/$id.ogg")
        override fun reading(card: DrawnCard, language: ReadingLanguage, position: ReadingPosition) =
            narration("reading/${if (language == ReadingLanguage.ZH) "zh" else "en"}/${card.cardId}/${if (card.reversed) "reversed" else "upright"}/${position.key}")
        override fun summary(cards: List<DrawnCard>, language: ReadingLanguage) =
            if (language == ReadingLanguage.ZH) ReadingSummary("希望 耐心 勇气", listOf("summary/zh/intro", "summary/zh/ending"))
            else ReadingSummary("hope patience courage", listOf("summary/en/intro", "summary/en/ending"))
        override fun loadHistory() = Result.success(stored.toList())
        override fun appendHistory(record: HistoryRecord): Result<Unit> = if (failSave) Result.failure(IllegalStateException("Disk full")) else {
            stored.removeAll { it.id == record.id }; stored += record; Result.success(Unit)
        }
        override fun playSpeech(sequenceIds: List<String>, onComplete: () -> Unit, onError: (Throwable) -> Unit) {
            speeches += Speech(sequenceIds, onComplete, onError)
        }
        override fun playEffect(id: String) = Result.success(Unit)
        override fun stopSpeech() {}
        override fun release() { released = true }
    }
    private fun vm(services: LocalServices, language: ReadingLanguage = ReadingLanguage.EN): FateViewModel {
        var nextId = 0
        return FateViewModel(services, language, nowMillis = { 1000 }, newRecordId = { "record-${++nextId}" })
    }
    private fun ready(vm: FateViewModel) = vm.setRuntimeStatus(true, true, true, false, false)
    private fun reading(vm: FateViewModel) {
        ready(vm)
        listOf(RitualEvent.LogoFinished, RitualEvent.AcceptSafety, RitualEvent.SystemsReady,
            RitualEvent.ContemplationFinished, RitualEvent.GatherFinished, RitualEvent.ShuffleFinished,
            RitualEvent.InterlaceCompleted, RitualEvent.InterlaceCompleted,
            RitualEvent.ToggleCard(0), RitualEvent.ToggleCard(1), RitualEvent.ToggleCard(2), RitualEvent.SpreadFinished).forEach(vm::onEvent)
    }
    private fun complete(vm: FateViewModel, services: LocalServices) {
        reading(vm)
        repeat(3) { vm.onEvent(RitualEvent.RevealCard(it)); services.speeches.last().complete() }
    }

    @Test fun selectedSystemLanguageAndApprovedGuidanceAreUsed() {
        assertEquals(ReadingLanguage.ZH, readingLanguageFor("zh-Hant"))
        assertEquals(ReadingLanguage.EN, readingLanguageFor("fr"))
        val services = LocalServices()
        val vm = vm(services, ReadingLanguage.ZH)
        ready(vm)
        listOf(RitualEvent.LogoFinished, RitualEvent.AcceptSafety, RitualEvent.SystemsReady).forEach(vm::onEvent)
        assertEquals(78, vm.state.value.catalog.size)
        assertEquals(listOf("guidance/zh/question"), services.speeches.last().ids)
        assertEquals("中文major-00", vm.title("major-00"))
    }

    @Test fun preflightUiRemainsUsableAndActiveRitualWaitsForSafeRuntime() {
        val vm = vm(LocalServices())
        vm.setRuntimeStatus(false, false, false, true, false)
        vm.onEvent(RitualEvent.LogoFinished)
        vm.onEvent(RitualEvent.AcceptSafety)
        assertEquals(RitualPhase.SYSTEMS, vm.state.value.ritual.phase)
        assertNull(vm.state.value.ritual.suspension)
        vm.onEvent(RitualEvent.SystemsReady)
        assertEquals(RitualPhase.SYSTEMS, vm.state.value.ritual.phase)
        ready(vm)
        vm.onEvent(RitualEvent.SystemsReady)
        vm.setRuntimeStatus(true, false, true, false, false)
        assertEquals(SuspensionReason.SPACE_UNAVAILABLE, vm.state.value.ritual.suspension)
        vm.onEvent(RitualEvent.ContemplationFinished)
        assertEquals(RitualPhase.CONTEMPLATE, vm.state.value.ritual.phase)
        ready(vm)
        vm.onEvent(RitualEvent.ContemplationFinished)
        assertEquals(RitualPhase.GATHER, vm.state.value.ritual.phase)
    }

    @Test fun exactSelectedReadingMustReallyCompleteBeforeAutomaticSummaryAndSave() {
        val services = LocalServices()
        val vm = vm(services)
        reading(vm)
        vm.onEvent(RitualEvent.RevealCard(0))
        val card = vm.state.value.ritual.selectedCards[0]
        assertEquals(listOf(services.reading(card, ReadingLanguage.EN, ReadingPosition.PAST).id), services.speeches.last().ids)
        vm.onEvent(RitualEvent.NarrationCompleted(0, 0))
        assertTrue(vm.state.value.ritual.narrationPlaying)
        services.speeches.last().complete()
        repeat(2) { i -> vm.onEvent(RitualEvent.RevealCard(i + 1)); services.speeches.last().complete() }
        assertEquals(RitualPhase.SUMMARY, vm.state.value.ritual.phase)
        assertEquals("hope patience courage", vm.state.value.ritual.result!!.summary)
        assertEquals(1, vm.state.value.historyRecords.size)
        assertEquals(vm.state.value.ritual.selectedCards, services.stored.single().cards)
        assertEquals(listOf("summary/en/intro", "summary/en/ending"), services.speeches.last().ids)
        services.speeches[services.speeches.size - 2].complete()
        assertEquals(1, services.stored.size)
    }

    @Test fun failedSaveRetainsExistingHistoryAndRetriesSameCompletedRecord() {
        val services = LocalServices()
        val previous = HistoryRecord("previous", 10, listOf(DrawnCard("major-00", false)), "prior", ReadingLanguage.EN)
        services.stored += previous
        services.failSave = true
        val vm = vm(services)
        complete(vm, services)
        assertEquals(listOf(previous), vm.state.value.historyRecords)
        assertNotNull(vm.state.value.saveError)
        assertEquals(RitualPhase.SUMMARY, vm.state.value.ritual.phase)
        services.failSave = false
        vm.retrySave()
        assertNull(vm.state.value.saveError)
        assertEquals(listOf("record-1", "previous"), vm.state.value.historyRecords.map { it.id })
        vm.retrySave()
        assertEquals(2, services.stored.size)
    }

    @Test fun pauseRestartsCurrentClipAndIgnoresInterruptedCallback() {
        val services = LocalServices()
        val vm = vm(services)
        reading(vm)
        vm.onEvent(RitualEvent.RevealCard(0))
        val interrupted = services.speeches.last()
        vm.pauseAudio()
        interrupted.complete()
        assertEquals(0, vm.state.value.ritual.nextRevealIndex)
        vm.resumeAudio()
        val restarted = services.speeches.last()
        assertEquals(interrupted.ids, restarted.ids)
        interrupted.complete()
        assertTrue(vm.state.value.ritual.narrationPlaying)
        restarted.complete()
        assertEquals(1, vm.state.value.ritual.nextRevealIndex)
    }

    @Test fun resetPreservesConsentAndDiscardsOldAudioAndHistoryOverlay() {
        val services = LocalServices()
        val vm = vm(services)
        reading(vm)
        vm.onEvent(RitualEvent.RevealCard(0))
        val old = services.speeches.last()
        vm.toggleHistory()
        assertTrue(vm.state.value.historyOpen)
        vm.onEvent(RitualEvent.Reset)
        old.complete()
        assertTrue(vm.state.value.ritual.safetyAccepted)
        assertEquals(RitualPhase.SYSTEMS, vm.state.value.ritual.phase)
        assertFalse(vm.state.value.historyOpen)
        assertNull(vm.state.value.historyDetail)
        assertEquals(0, vm.state.value.ritual.revealedCount)
        assertTrue(services.stored.isEmpty())
    }

    @Test fun finalCompletionDuringTrackingLossSummarizesAfterRuntimeRecovers() {
        val services = LocalServices()
        val vm = vm(services)
        reading(vm)
        repeat(2) { vm.onEvent(RitualEvent.RevealCard(it)); services.speeches.last().complete() }
        vm.onEvent(RitualEvent.RevealCard(2))
        vm.setRuntimeStatus(true, true, false, false, false)
        services.speeches.last().complete()
        assertEquals(RitualPhase.READING, vm.state.value.ritual.phase)
        assertTrue(vm.state.value.historyRecords.isEmpty())
        ready(vm)
        assertEquals(RitualPhase.SUMMARY, vm.state.value.ritual.phase)
        assertEquals(1, vm.state.value.historyRecords.size)
    }

    @Test fun historyIsRelocalizedForDisplayWithoutRewritingStoredLanguage() {
        val services = LocalServices()
        services.stored += HistoryRecord("old", 10, listOf(DrawnCard("major-00", false), DrawnCard("major-01", false), DrawnCard("major-02", true)),
            "原始中文总结", ReadingLanguage.ZH)
        val vm = vm(services, ReadingLanguage.EN)
        assertEquals("hope patience courage", vm.state.value.historyRecords.single().summary)
        assertEquals(ReadingLanguage.EN, vm.state.value.historyRecords.single().language)
        vm.showHistoryRecord(services.stored.single())
        assertEquals("hope patience courage", vm.state.value.historyDetail!!.summary)
        assertEquals("原始中文总结", services.stored.single().summary)
        assertEquals(ReadingLanguage.ZH, services.stored.single().language)
    }

    @Test fun audioFailureCannotUnlockReadingAndCloseDiscardsCallbacks() {
        val services = LocalServices()
        val vm = vm(services)
        reading(vm)
        vm.onEvent(RitualEvent.RevealCard(0))
        services.speeches.last().fail(IllegalStateException("Missing audio"))
        assertNotNull(vm.state.value.audioError)
        assertTrue(vm.state.value.ritual.narrationPlaying)
        vm.retryAudio()
        assertNull(vm.state.value.audioError)
        val playback = services.speeches.last()
        vm.close()
        playback.complete()
        assertTrue(services.released)
        assertEquals(0, vm.state.value.ritual.nextRevealIndex)
    }
    @Test fun languageChangeReplacesGuidanceWhilePreservingConsentAndSession() {
        val services = LocalServices()
        val vm = vm(services)
        ready(vm)
        listOf(RitualEvent.LogoFinished, RitualEvent.AcceptSafety, RitualEvent.SystemsReady).forEach(vm::onEvent)
        val old = services.speeches.last()
        val ritual = vm.state.value.ritual
        vm.setLanguage(ReadingLanguage.ZH)
        assertEquals(ReadingLanguage.ZH, vm.state.value.language)
        assertEquals(ritual, vm.state.value.ritual)
        assertEquals("text:guidance/zh/question", vm.state.value.narrationText)
        assertEquals(listOf("guidance/zh/question"), services.speeches.last().ids)
        old.complete()
        assertEquals(RitualPhase.CONTEMPLATE, vm.state.value.ritual.phase)
        assertEquals("中文major-00", vm.title("major-00"))
    }

    @Test fun languageChangeRestartsInProgressReadingAndRejectsOldLanguageCallback() {
        val services = LocalServices()
        val vm = vm(services)
        reading(vm)
        vm.onEvent(RitualEvent.RevealCard(0))
        val old = services.speeches.last()
        val selected = vm.state.value.ritual.selectedCards
        vm.setLanguage(ReadingLanguage.ZH)
        val replacement = services.speeches.last()
        assertEquals(listOf(services.reading(selected[0], ReadingLanguage.ZH, ReadingPosition.PAST).id), replacement.ids)
        assertEquals(selected, vm.state.value.ritual.selectedCards)
        assertTrue(vm.state.value.ritual.safetyAccepted)
        old.complete()
        assertTrue(vm.state.value.ritual.narrationPlaying)
        assertEquals(0, vm.state.value.ritual.nextRevealIndex)
        replacement.complete()
        assertEquals(1, vm.state.value.ritual.nextRevealIndex)
        assertFalse(vm.state.value.ritual.narrationPlaying)
    }

    @Test fun languageChangeRelocalizesSummaryAndHistoryWithoutSavingAgain() {
        val services = LocalServices()
        val vm = vm(services)
        complete(vm, services)
        val stored = services.stored.single()
        vm.showHistoryRecord(stored)
        vm.setLanguage(ReadingLanguage.ZH)
        assertEquals("希望 耐心 勇气", vm.state.value.ritual.result!!.summary)
        assertEquals("希望 耐心 勇气", vm.state.value.narrationText)
        assertEquals("希望 耐心 勇气", vm.state.value.historyRecords.single().summary)
        assertEquals("希望 耐心 勇气", vm.state.value.historyDetail!!.summary)
        assertEquals(ReadingLanguage.ZH, vm.state.value.historyDetail!!.language)
        assertEquals(listOf("summary/zh/intro", "summary/zh/ending"), services.speeches.last().ids)
        assertEquals(listOf(stored), services.stored)
        assertEquals(ReadingLanguage.EN, stored.language)
        vm.setLanguage(ReadingLanguage.EN)
        assertEquals("hope patience courage", vm.state.value.ritual.result!!.summary)
        assertEquals(stored, vm.state.value.historyRecords.single())
        assertEquals(listOf(stored), services.stored)
    }

    @Test fun languageChangeWhilePausedQueuesOnlyNewLanguageReadingForResume() {
        val services = LocalServices()
        val vm = vm(services)
        reading(vm)
        vm.onEvent(RitualEvent.RevealCard(0))
        val old = services.speeches.last()
        vm.pauseAudio()
        vm.setLanguage(ReadingLanguage.ZH)
        assertEquals(ReadingLanguage.ZH, vm.state.value.language)
        assertTrue(vm.state.value.narrationText!!.startsWith("text:reading/zh/"))
        old.complete()
        assertEquals(0, vm.state.value.ritual.nextRevealIndex)
        vm.resumeAudio()
        val replacement = services.speeches.last()
        assertTrue(replacement.ids.single().startsWith("reading/zh/"))
        replacement.complete()
        assertEquals(1, vm.state.value.ritual.nextRevealIndex)
    }

}
