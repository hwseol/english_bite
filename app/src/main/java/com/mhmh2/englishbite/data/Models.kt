package com.mhmh2.englishbite.data

data class Sentence(
    val text: String,
    val start: Double,
    val end: Double,
    val ko: String
)

data class VideoResult(
    val cached: Boolean,
    val video_id: String,
    val sentence_count: Int,
    val sentences: List<Sentence>
)

data class IngestRequest(val url: String)
