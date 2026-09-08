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

    private val _sort = MutableStateFlow(CatalogSort.LATEST)
    val sort: StateFlow<CatalogSort> = _sort

    private var allItems: List<CatalogItem> = emptyList()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = CatalogState.Loading
        viewModelScope.launch {
            try {
                allItems = ApiClient.ingestApi.getCatalog()
                applyFilterAndSort()
            } catch (e: Exception) {
                _state.value = CatalogState.Error(e.message ?: "목록을 불러오지 못했습니다")
            }
        }
    }

    fun setChannelFilter(channel: String?) {
        _channelFilter.value = channel
        applyFilterAndSort()
    }

    fun setSort(sort: CatalogSort) {
        _sort.value = sort
        applyFilterAndSort()
    }

    private fun applyFilterAndSort() {
        var items = allItems
        _channelFilter.value?.let { channel -> items = items.filter { it.channel == channel } }
        items = when (_sort.value) {
            CatalogSort.POPULAR -> items.sortedByDescending { it.view_count }
            CatalogSort.LATEST -> items.sortedByDescending { it.timestamp }
        }
        _state.value = CatalogState.Loaded(items)
    }
}
