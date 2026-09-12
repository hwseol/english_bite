package com.mhmh2.englishbite.vocab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Saved idioms/expressions are the one thing worth coming back to after finishing a video -
 * a plain JSON file in app-private storage is enough for what's realistically a few hundred
 * entries at most; a whole SQLite/Room setup would be more machinery than this needs. */
class SavedIdiomsViewModel(application: Application) : AndroidViewModel(application) {
    private val file: File get() = File(getApplication<Application>().filesDir, "saved_idioms.json")
    private val gson = Gson()

    private val _items = MutableStateFlow<List<SavedIdiom>>(emptyList())
    val items: StateFlow<List<SavedIdiom>> = _items.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = if (file.exists()) {
                runCatching {
                    val type = object : TypeToken<List<SavedIdiom>>() {}.type
                    gson.fromJson<List<SavedIdiom>>(file.readText(), type)
                }.getOrNull()
            } else null
            _items.value = loaded ?: emptyList()
        }
    }

    // Identity is (videoId, sentenceStart) - a sentence, not a phrase, is the thing being
    // bookmarked; whatever idiom (if any) was flagged on that sentence rides along as extra
    // fields on the same entry rather than needing its own separate bookmark action.
    fun isSaved(videoId: String, sentenceStart: Double): Boolean =
        _items.value.any { it.videoId == videoId && it.sentenceStart == sentenceStart }

    fun toggleSave(
        videoId: String,
        videoTitle: String,
        sentenceText: String,
        sentenceKo: String,
        sentenceStart: Double,
        phrase: String? = null,
        noteKo: String? = null,
        example: String? = null
    ) {
        val already = _items.value.any { it.videoId == videoId && it.sentenceStart == sentenceStart }
        _items.value = if (already) {
            _items.value.filterNot { it.videoId == videoId && it.sentenceStart == sentenceStart }
        } else {
            _items.value + SavedIdiom(
                videoId = videoId,
                videoTitle = videoTitle,
                savedAt = System.currentTimeMillis(),
                sentenceText = sentenceText,
                sentenceKo = sentenceKo,
                sentenceStart = sentenceStart,
                phrase = phrase,
                noteKo = noteKo,
                example = example
            )
        }
        persist()
    }

    fun remove(item: SavedIdiom) {
        _items.value = _items.value.filterNot { it == item }
        persist()
    }

    private fun persist() {
        val snapshot = _items.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { file.writeText(gson.toJson(snapshot)) }
        }
    }
}
