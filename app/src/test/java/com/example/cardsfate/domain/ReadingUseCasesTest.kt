package com.example.cardsfate.domain

import com.example.cardsfate.domain.model.*
import com.example.cardsfate.domain.usecase.*
import org.junit.Assert.*
import org.junit.Test

class ReadingUseCasesTest {
    private fun record(id: String, time: Long) = HistoryRecord(id, time,
        listOf(DrawnCard("major-00", false)), "hope", ReadingLanguage.EN)

    @Test fun historyKeepsTenNewestWithDeterministicTieAndIdDeduplication() {
        val original = (1..12).map { record("id-$it", it.toLong()) }
        val result = newestHistory(original + record("id-12", 100) + record("z", 12) + record("a", 12))
        assertEquals(10, result.size)
        assertEquals(listOf("id-12", "a", "z", "id-11", "id-10", "id-9", "id-8", "id-7", "id-6", "id-5"), result.map { it.id })
        assertEquals(100L, result.first().completedAtEpochMillis)
        assertEquals(12, original.size)
        assertTrue(newestHistory(emptyList()).isEmpty())
    }

    @Test fun summaryUsesSuppliedConnectorsAndKeywordsWithLanguageBounds() {
        assertEquals("Past: hope Present: patience Future: courage", assembleSummary(ReadingLanguage.EN,
            listOf("Past:", "Present:", "Future:"), listOf("hope", "patience", "courage")))
        assertEquals("过去：希望；当下：耐心；未来：勇气", assembleSummary(ReadingLanguage.ZH,
            listOf("过去：", "当下：", "未来："), listOf("希望", "耐心", "勇气")))
        val chinese = assembleSummary(ReadingLanguage.ZH, List(3) { "前路：" }, List(3) { "星".repeat(80) })
        assertEquals(100, chinese.codePointCount(0, chinese.length))
        val english = assembleSummary(ReadingLanguage.EN, List(3) { "Towards" }, List(3) { List(40) { "hope" }.joinToString(" ") })
        assertEquals(60, english.split(Regex("\\s+")).size)
        assertEquals("", assembleSummary(ReadingLanguage.EN, emptyList(), emptyList()))
    }
}
