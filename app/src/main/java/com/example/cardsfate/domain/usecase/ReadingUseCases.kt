package com.example.cardsfate.domain.usecase

import com.example.cardsfate.domain.model.*

/** Newest duplicate wins. Equal timestamps use ID order, independent of storage iteration order. */
fun newestHistory(records: List<HistoryRecord>): List<HistoryRecord> = records
    .sortedWith(compareByDescending<HistoryRecord> { it.completedAtEpochMillis }.thenBy { it.id })
    .distinctBy { it.id }
    .take(10)

/** Local template assembly only; values come from the selected language's approved metadata. */
fun assembleSummary(language: ReadingLanguage, connectors: List<String>, keywords: List<String>): String {
    val clauses = connectors.zip(keywords).map { (connector, keyword) ->
        val left = connector.trim()
        val right = keyword.trim()
        if (language == ReadingLanguage.ZH) left + right
        else listOf(left, right).filter { it.isNotEmpty() }.joinToString(" ")
    }.filter { it.isNotBlank() }
    return if (language == ReadingLanguage.ZH) {
        val text = clauses.joinToString("；")
        text.substring(0, text.offsetByCodePoints(0, minOf(100, text.codePointCount(0, text.length))))
    } else clauses.joinToString(" ").trim().split(Regex("\\s+")).take(60).joinToString(" ")
}
