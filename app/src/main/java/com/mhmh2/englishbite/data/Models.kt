package com.mhmh2.englishbite.data

data class Sentence(
    val text: String,
    val start: Double,
    val end: Double,
    val ko: String
)

/** "processing" while a fresh video is still being translated server-side, "done" once
 * sentences/sentence_count are populated (either just-finished or served from cache). */
data class IngestResponse(
    val status: String,
    val cached: Boolean? = null,
    val video_id: String,
    val sentence_count: Int? = null,
    val sentences: List<Sentence>? = null
)

data class VideoResult(
    val video_id: String,
    val sentence_count: Int,
    val sentences: List<Sentence>
)

data class IngestRequest(val url: String)

data class CatalogItem(
    val video_id: String,
    val title: String,
    val channel: String,
    val thumbnail: String?,
    val view_count: Long,
    val duration: Int,
    val upload_date: String,
    val timestamp: Long = 0
)
