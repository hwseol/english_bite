package com.mhmh2.englishbite.vocab

data class SavedIdiom(
    val videoId: String,
    val videoTitle: String,
    val phrase: String,
    val noteKo: String,
    val savedAt: Long,
    val example: String? = null,
    // The actual sentence the phrase was flagged in, and where it starts (seconds) - lets the
    // vocab list jump straight back to that moment in the source video instead of just naming
    // it. Nullable/defaulted so idioms saved before this field existed still deserialize fine.
    val sentenceText: String? = null,
    val sentenceStart: Double = 0.0
)
