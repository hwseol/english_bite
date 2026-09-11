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

    fun isSaved(videoId: String, phrase: String): Boolean =
        _items.value.any { it.videoId == videoId && it.phrase == phrase }

    fun toggleSave(videoId: String, videoTitle: String, phrase: String, noteKo: String) {
        val already = _items.value.any { it.videoId == videoId && it.phrase == phrase }
        _items.value = if (already) {
            _items.value.filterNot { it.videoId == videoId && it.phrase == phrase }
        } else {
            _items.value + SavedIdiom(videoId, videoTitle, phrase, noteKo, System.currentTimeMillis())
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
