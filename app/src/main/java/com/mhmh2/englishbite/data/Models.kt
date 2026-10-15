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
    val idioms: List<Idiom>? = null,
    // Set only for a part of a long lecture: the real YouTube video to play and the slice of it
    // (in seconds) this entry covers. video_id is then the entry id ("<youtube id>-pNN").
    val youtube_id: String? = null,
    val start_second: Double? = null,
    val end_second: Double? = null
)

data class VideoResult(
    val video_id: String,
    val sentence_count: Int,
    val sentences: List<Sentence>,
    val idioms: List<Idiom> = emptyList(),
    val youtube_id: String? = null,
    val start_second: Double? = null,
    val end_second: Double? = null
)

data class IngestRequest(val url: String)

data class SignupRequest(val email: String, val nickname: String, val password: String)
data class LoginRequest(val email: String, val password: String)
data class CrashReport(val app_version: Int, val device: String, val android: String, val trace: String)
data class ChangePasswordRequest(val email: String, val old_password: String, val new_password: String)
data class AuthResponse(val token: String, val email: String, val nickname: String)
data class UserProfile(val email: String, val nickname: String)

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
    val category: String? = null,
    // Lecture series (e.g. CS50 AI): a long video published as several parts. Only present on
    // those entries; the server hides them from builds older than 7.
    val series: String? = null,
    val series_order: Int = 0,
    val part_index: Int = 0,
    val part_count: Int = 0
)
