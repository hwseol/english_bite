package com.mhmh2.englishbite.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mhmh2.englishbite.data.ApiClient
import com.mhmh2.englishbite.data.IngestRequest
import com.mhmh2.englishbite.data.IngestResponse
import com.mhmh2.englishbite.data.VideoResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Error(val message: String) : UiState
    data class Success(val result: VideoResult) : UiState
}

private const val POLL_INTERVAL_MS = 4000L
private const val MAX_CONSECUTIVE_POLL_FAILURES = 5

class StudyViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    fun submitUrl(url: String) {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                val response = ApiClient.ingestApi.ingestVideo(IngestRequest(url))
                if (response.status == "done") {
                    _uiState.value = UiState.Success(response.toVideoResult())
                } else {
                    pollUntilDone(response.video_id)
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "알 수 없는 오류가 발생했습니다")
            }
        }
    }

    /** Each poll is a cheap, near-instant request - unlike one long held-open connection,
     * this survives the screen sleeping, the app briefly backgrounding, or a flaky network
     * hiccup along the way, because the actual translation work keeps running server-side
     * regardless of whether any particular poll succeeds. */
    private suspend fun pollUntilDone(videoId: String) {
        var consecutiveFailures = 0
        while (true) {
            delay(POLL_INTERVAL_MS)
            val response = try {
                ApiClient.ingestApi.getVideo(videoId)
            } catch (e: Exception) {
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                    _uiState.value = UiState.Error(e.message ?: "서버와 연결이 끊겼습니다")
                    return
                }
                continue
            }
            consecutiveFailures = 0
            if (response.status == "done") {
                _uiState.value = UiState.Success(response.toVideoResult())
                return
            }
        }
    }

    fun reset() {
        _uiState.value = UiState.Idle
    }
}

private fun IngestResponse.toVideoResult() = VideoResult(
    video_id = video_id,
    sentence_count = sentence_count ?: 0,
    sentences = sentences.orEmpty()
)
