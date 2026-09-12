package com.mhmh2.englishbite.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mhmh2.englishbite.data.ApiClient
import com.mhmh2.englishbite.data.CatalogItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class CatalogSort { POPULAR, LATEST }

sealed interface CatalogState {
    data object Loading : CatalogState
    data class Error(val message: String) : CatalogState
    data class Loaded(val items: List<CatalogItem>) : CatalogState
}

class CatalogViewModel : ViewModel() {
    private val _state = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val state: StateFlow<CatalogState> = _state

    private val _channelFilter = MutableStateFlow<String?>(null)
    val channelFilter: StateFlow<String?> = _channelFilter

    private val _categoryFilter = MutableStateFlow<String?>(null)
    val categoryFilter: StateFlow<String?> = _categoryFilter

    private val _sort = MutableStateFlow(CatalogSort.LATEST)
    val sort: StateFlow<CatalogSort> = _sort

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private var allItems: List<CatalogItem> = emptyList()

    init {
        refresh()
    }

    /** [silent] is used for the automatic refresh on every app resume (see MainActivity) - the
     * catalog keeps growing as the backend finishes processing more videos, but a user who just
     * switched back from another app shouldn't see the list they were already looking at
     * replaced by a loading spinner, or - worse - an error screen over one transient network
     * hiccup. Only the very first load (init below) blanks the screen while it fetches. */
    fun refresh(silent: Boolean = false) {
        if (!silent) _state.value = CatalogState.Loading
        viewModelScope.launch {
            try {
                allItems = ApiClient.ingestApi.getCatalog()
                applyFilterAndSort()
            } catch (e: Exception) {
                if (!silent) _state.value = CatalogState.Error(e.message ?: "목록을 불러오지 못했습니다")
            }
        }
    }

    fun setChannelFilter(channel: String?) {
        _channelFilter.value = channel
        applyFilterAndSort()
    }

    fun setCategoryFilter(category: String?) {
        _categoryFilter.value = category
        applyFilterAndSort()
    }

    fun setSort(sort: CatalogSort) {
        _sort.value = sort
        applyFilterAndSort()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyFilterAndSort()
    }

    private fun applyFilterAndSort() {
        var items = allItems
        _channelFilter.value?.let { channel -> items = items.filter { it.channel == channel } }
        _categoryFilter.value?.let { category -> items = items.filter { it.category == category } }
        _searchQuery.value.trim().takeIf { it.isNotEmpty() }?.let { query ->
            items = items.filter { it.title.contains(query, ignoreCase = true) }
        }
        items = when (_sort.value) {
            CatalogSort.POPULAR -> items.sortedByDescending { it.view_count }
            CatalogSort.LATEST -> items.sortedByDescending { it.timestamp }
        }
        _state.value = CatalogState.Loaded(items)
    }
}
