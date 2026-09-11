package com.mhmh2.englishbite.vocab

data class SavedIdiom(
    val videoId: String,
    val videoTitle: String,
    val phrase: String,
    val noteKo: String,
    val savedAt: Long,
    val example: String? = null
)
