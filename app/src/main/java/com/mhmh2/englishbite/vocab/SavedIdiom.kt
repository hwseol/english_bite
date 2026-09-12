package com.mhmh2.englishbite.vocab

/** One bookmarked moment in a video - identified by (videoId, sentenceStart), not by phrase:
 * a user can bookmark any sentence they want, whether or not it happens to contain a flagged
 * idiom. When it does, that idiom's phrase/note/example ride along on the same entry instead
 * of needing a separate bookmark action - one tap either way, on whatever sentence is on
 * screen. phrase/noteKo/example stay null for a plain sentence bookmark.
 *
 * sentenceKo was added after the first version of this feature shipped, and sentenceText/
 * sentenceStart after that - all nullable/defaulted so earlier-saved entries still deserialize. */
data class SavedIdiom(
    val videoId: String,
    val videoTitle: String,
    val savedAt: Long,
    val sentenceText: String? = null,
    val sentenceKo: String? = null,
    val sentenceStart: Double = 0.0,
    val phrase: String? = null,
    val noteKo: String? = null,
    val example: String? = null
)
