package com.mhmh2.englishbite.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mhmh2.englishbite.data.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

data class AdminSyncUiState(
    val savedToken: String = "",
    val isRunning: Boolean = false,
    val log: List<String> = emptyList(),
)

class AdminSyncViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(AdminSyncUiState())
    val state: StateFlow<AdminSyncUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            AdminTokenStore.tokenFlow(getApplication()).collect { token ->
                _state.value = _state.value.copy(savedToken = token ?: "")
            }
        }
    }

    fun saveToken(token: String) {
        viewModelScope.launch { AdminTokenStore.saveToken(getApplication(), token) }
    }

    private fun appendLog(line: String) {
        _state.value = _state.value.copy(log = _state.value.log + line)
    }

    fun runSync() {
        val token = _state.value.savedToken
        if (token.isBlank()) {
            appendLog("먼저 관리자 토큰을 입력해주세요")
            return
        }
        if (_state.value.isRunning) return

        _state.value = _state.value.copy(isRunning = true, log = emptyList())
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val videos = ChannelScanner.scanAll(onProgress = { appendLog(it) })
                appendLog("총 ${videos.size}개 영상 발견, 처리 시작")

                for (video in videos) {
                    try {
                        val alreadyKnown = try {
                            ApiClient.ingestApi.getVideo(video.videoId)
                            true
                        } catch (e: Exception) {
                            false // 404 (or transient error) - treat as "not yet known", try uploading
                        }
                        if (alreadyKnown) {
                            appendLog("[skip] ${video.title} (이미 처리됨/진행 중)")
                            continue
                        }

                        appendLog("[오디오 추출] ${video.title}")
                        val audioFile = AudioExtractor.downloadAudio(getApplication(), video.videoId)

                        appendLog("[업로드] ${video.title}")
                        val textType = "text/plain".toMediaTypeOrNull()
                        AdminApiClient.adminApi.ingest(
                            adminToken = token,
                            videoId = video.videoId.toRequestBody(textType),
                            title = video.title.toRequestBody(textType),
                            channel = video.channel.toRequestBody(textType),
                            thumbnail = video.thumbnailUrl?.toRequestBody(textType),
                            viewCount = video.viewCount.toString().toRequestBody(textType),
                            duration = video.durationSeconds.toString().toRequestBody(textType),
                            uploadDate = video.uploadDate.toRequestBody(textType),
                            timestamp = video.timestamp.toString().toRequestBody(textType),
                            audio = MultipartBody.Part.createFormData(
                                "audio", audioFile.name, audioFile.asRequestBody("audio/*".toMediaTypeOrNull())
                            ),
                        )
                        audioFile.delete()
                        appendLog("[완료] ${video.title} - 서버에서 처리 중")
                    } catch (e: Exception) {
                        appendLog("[실패] ${video.title}: ${e.message}")
                    }
                }
                appendLog("모든 영상 처리 완료")
            } catch (e: Exception) {
                appendLog("동기화 실패: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(isRunning = false)
                }
            }
        }
    }
}
