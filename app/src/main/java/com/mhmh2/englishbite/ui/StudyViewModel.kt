package com.mhmh2.englishbite.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mhmh2.englishbite.data.ApiClient
import com.mhmh2.englishbite.data.IngestRequest
import com.mhmh2.englishbite.data.VideoResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Error(val message: String) : UiState
    data class Success(val result: VideoResult) : UiState
}

class StudyViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    fun submitUrl(url: String) {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                val result = ApiClient.ingestApi.ingestVideo(IngestRequest(url))
                _uiState.value = UiState.Success(result)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "알 수 없는 오류가 발생했습니다")
            }
        }
    }

    fun reset() {
        _uiState.value = UiState.Idle
    }
}
