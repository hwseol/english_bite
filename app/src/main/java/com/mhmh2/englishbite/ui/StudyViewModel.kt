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
import retrofit2.HttpException
import java.io.IOException

sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Error(val message: String) : UiState
    data class Success(val result: VideoResult) : UiState
}

private const val POLL_INTERVAL_MS = 4000L

class StudyViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    fun submitUrl(url: String) {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            while (true) {
                try {
                    val response = ApiClient.ingestApi.ingestVideo(IngestRequest(url))
                    if (response.status == "done") {
                        _uiState.value = UiState.Success(response.toVideoResult())
                    } else {
                        pollUntilDone(response.video_id)
                    }
                    return@launch
                } catch (e: IOException) {
                    delay(POLL_INTERVAL_MS) // network hiccup right at submit time - retry
                } catch (e: HttpException) {
                    _uiState.value = UiState.Error(e.message() ?: "알 수 없는 오류가 발생했습니다")
                    return@launch
                }
            }
        }
    }

    /** Each poll is a cheap, near-instant request - unlike one long held-open connection,
     * this survives the screen sleeping, the app briefly backgrounding, or a flaky network
     * hiccup along the way, because the actual translation work keeps running server-side
     * regardless of whether any particular poll succeeds. A dropped wifi connection, DNS
     * hiccup, etc. is exactly the kind of transient condition this is meant to ride out, so
     * network errors never give up on their own - only a real server-reported failure (the
     * translation itself errored) does. */
    private suspend fun pollUntilDone(videoId: String) {
        while (true) {
            delay(POLL_INTERVAL_MS)
            val response = try {
                ApiClient.ingestApi.getVideo(videoId)
            } catch (e: IOException) {
                continue // network hiccup - the server-side job is unaffected, keep trying
            } catch (e: HttpException) {
                if (e.code() == 502) {
                    _uiState.value = UiState.Error("번역 처리 중 오류가 발생했습니다: ${e.message()}")
                    return
                }
                continue
            }
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
