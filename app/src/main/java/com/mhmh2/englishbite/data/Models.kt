package com.mhmh2.englishbite.data

data class Word(
    val text: String,
    val start: Double,
    val end: Double
)

data class Sentence(
    val text: String,
    val start: Double,
    val end: Double,
    val ko: String,
    // Gson doesn't apply Kotlin default values for a field missing from the JSON (it builds
    // the object via reflection, bypassing the constructor) - a cache entry from before
    // per-word timing existed leaves this genuinely null, not an empty list. Must stay
    // nullable and read through .orEmpty() everywhere, or that null reaches isEmpty()/etc.
    // and crashes.
    val words: List<Word>? = null
)

/** sentence_index is 1-based and refers to position in the video's `sentences` list -
 * at most 1-2 per ~8-sentence chunk the server scanned, often none at all. */
data class Idiom(
    val sentence_index: Int,
    val phrase: String,
    val note_ko: String,
    val example: String? = null
)

/** "processing" while a fresh video is still being translated server-side, "done" once
 * sentences/sentence_count are populated (either just-finished or served from cache). */
data class IngestResponse(
    val status: String,
    val cached: Boolean? = null,
    val video_id: String,
    val sentence_count: Int? = null,
    val sentences: List<Sentence>? = null,
    val idioms: List<Idiom>? = null
)

data class VideoResult(
    val video_id: String,
    val sentence_count: Int,
    val sentences: List<Sentence>,
    val idioms: List<Idiom> = emptyList()
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
    val timestamp: Long = 0,
    // Nullable: entries collected before category classification existed have no such key in
    // their server-side JSON at all, and Gson leaves a missing field as null regardless of a
    // Kotlin default (it builds this via reflection, bypassing the constructor).
    val category: String? = null
)
