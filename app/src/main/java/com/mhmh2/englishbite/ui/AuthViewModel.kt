package com.mhmh2.englishbite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.mhmh2.englishbite.data.ApiClient
import com.mhmh2.englishbite.data.AuthTokenStore
import com.mhmh2.englishbite.data.LoginRequest
import com.mhmh2.englishbite.data.SignupRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class AuthUiState(
    val nickname: String? = null,
    val email: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
) {
    val isLoggedIn: Boolean get() = nickname != null
}

/** Login is optional everywhere in the app - this just tracks whatever's already saved on disk
 * (nothing, if the user never logged in) and lets LoginScreen update it. No other screen needs
 * to know about auth at all beyond the account icon that opens LoginScreen. */
class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenStore = AuthTokenStore(application)

    private val _uiState = MutableStateFlow(
        AuthUiState(nickname = tokenStore.nickname, email = tokenStore.email)
    )
    val uiState: StateFlow<AuthUiState> = _uiState

    fun signup(email: String, nickname: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = ApiClient.authApi.signup(SignupRequest(email, nickname, password))
                tokenStore.save(response)
                _uiState.value = AuthUiState(nickname = response.nickname, email = response.email)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = errorMessage(e))
            }
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = ApiClient.authApi.login(LoginRequest(email, password))
                tokenStore.save(response)
                _uiState.value = AuthUiState(nickname = response.nickname, email = response.email)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = errorMessage(e))
            }
        }
    }

    fun logout() {
        tokenStore.clear()
        _uiState.value = AuthUiState()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // The server returns {"detail": "사람이 읽을 메시지"} on a 400 (duplicate email, wrong
    // password, ...) - surfaced to the user as-is rather than behind a generic error, since
    // that message is already meant to be shown directly.
    private fun errorMessage(e: Exception): String {
        if (e is HttpException) {
            val detail = e.response()?.errorBody()?.string()?.let { body ->
                runCatching { Gson().fromJson(body, Map::class.java)["detail"] as? String }.getOrNull()
            }
            if (detail != null) return detail
        }
        return "연결에 문제가 있어요. 잠시 후 다시 시도해주세요."
    }
}
