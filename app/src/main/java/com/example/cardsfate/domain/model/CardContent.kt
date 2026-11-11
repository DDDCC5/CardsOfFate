package com.example.cardsfate.domain.model

enum class ReadingPosition(val key: String) { PAST("past"), PRESENT("present"), FUTURE("future") }
data class CatalogCard(val id: String, val zh: String, val en: String)
data class NarrationUnit(val id: String, val text: String, val assetPath: String)
data class ReadingSummary(val text: String, val sequenceIds: List<String>)

