package com.mhmh2.englishbite.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.mhmh2.englishbite.data.ApiClient
import com.mhmh2.englishbite.data.AuthResponse
import com.mhmh2.englishbite.data.AuthTokenStore
import com.mhmh2.englishbite.data.ChangePasswordRequest
import com.mhmh2.englishbite.data.DeleteMeRequest
import com.mhmh2.englishbite.data.EmailCodeRequest
import com.mhmh2.englishbite.data.GoogleLoginRequest
import com.mhmh2.englishbite.data.GoogleSignIn
import com.mhmh2.englishbite.data.GoogleSignInException
import com.mhmh2.englishbite.data.LoginRequest
import com.mhmh2.englishbite.data.ResetConfirmRequest
import com.mhmh2.englishbite.data.SignupRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class AuthUiState(
    val nickname: String? = null,
    val email: String? = null,
    /** False only for an account made with Google that never set a password. */
    val hasPassword: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** The address a verification code was just mailed to (sign-up or password reset). */
    val codeSentTo: String? = null,
    /** When "다시 받기" is allowed again (the server allows one mail a minute per address). */
    val resendAtMillis: Long = 0,
    /** The server can send verification mails (assumed true until it says otherwise). */
    val emailCodeAvailable: Boolean = true,
    /** The build has a Google client ID AND the server is set up for it. */
    val googleAvailable: Boolean = false
) {
    val isLoggedIn: Boolean get() = nickname != null
}

/** Login is optional everywhere in the app - this just tracks whatever's already saved on disk
 * (nothing, if the user never logged in) and lets LoginScreen update it. No other screen needs
 * to know about auth at all beyond the account icon that opens LoginScreen. */
class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenStore = AuthTokenStore(application)

    private val _uiState = MutableStateFlow(
        AuthUiState(nickname = tokenStore.nickname, email = tokenStore.email, hasPassword = tokenStore.hasPassword)
    )
    val uiState: StateFlow<AuthUiState> = _uiState

    init {
        // What this server can do (mail codes, Google); if it can't be asked, only offer the basics.
        viewModelScope.launch {
            runCatching { ApiClient.authApi.config() }.onSuccess { config ->
                _uiState.value = _uiState.value.copy(
                    emailCodeAvailable = config.email_code,
                    googleAvailable = config.google && GoogleSignIn.available
                )
            }
        }
    }

    private fun signedIn(response: AuthResponse, notice: String? = null) {
        tokenStore.save(response)
        _uiState.value = _uiState.value.copy(
            nickname = response.nickname,
            email = response.email,
            hasPassword = response.has_password != false,
            isLoading = false,
            error = null,
            notice = notice,
            codeSentTo = null,
            resendAtMillis = 0
        )
    }

    private fun signedOut() {
        tokenStore.clear()
        _uiState.value = _uiState.value.copy(
            nickname = null, email = null, hasPassword = true, isLoading = false,
            error = null, notice = null, codeSentTo = null, resendAtMillis = 0
        )
    }

    private fun failed(e: Exception) {
        _uiState.value = _uiState.value.copy(isLoading = false, error = errorMessage(e))
    }

    private fun loading() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null, notice = null)
    }

    /** Mails a 6-digit code to [email]; purpose is "signup" or "reset". */
    fun sendCode(email: String, purpose: String) {
        viewModelScope.launch {
            loading()
            try {
                val address = email.trim()
                ApiClient.authApi.sendCode(EmailCodeRequest(address, purpose))
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    codeSentTo = address.lowercase(),
                    resendAtMillis = System.currentTimeMillis() + 60_000,
                    notice = "인증번호를 보냈어요. 메일함을 확인해 주세요. (스팸함도 확인해 주세요)"
                )
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    /** Back to the first step (wrong address typed, or switching between sign-up and login). */
    fun resetCodeStep() {
        _uiState.value = _uiState.value.copy(codeSentTo = null, resendAtMillis = 0, error = null, notice = null)
    }

    fun signup(email: String, nickname: String, password: String, code: String) {
        viewModelScope.launch {
            loading()
            try {
                signedIn(ApiClient.authApi.signup(SignupRequest(email, nickname, password, code.trim())))
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            loading()
            try {
                signedIn(ApiClient.authApi.login(LoginRequest(email, password)))
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    /** New password with the mailed code; signs in on success. */
    fun resetPassword(email: String, code: String, password: String) {
        viewModelScope.launch {
            loading()
            try {
                signedIn(
                    ApiClient.authApi.resetConfirm(ResetConfirmRequest(email, code.trim(), password)),
                    notice = "비밀번호를 새로 설정했어요."
                )
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    /** [activityContext] must be an Activity: the Google account sheet opens on top of it. */
    fun googleSignIn(activityContext: Context) {
        viewModelScope.launch {
            loading()
            try {
                val idToken = GoogleSignIn.idToken(activityContext)
                if (idToken == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false)  // closed the sheet
                } else {
                    signedIn(ApiClient.authApi.google(GoogleLoginRequest(idToken)))
                }
            } catch (e: GoogleSignInException) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    /** Permanent account deletion; the server re-checks the password, so a wrong one just
     * surfaces its message and leaves the user logged in. A Google account without a password
     * is deleted on its session alone. */
    fun deleteAccount(password: String) {
        val state = _uiState.value
        val email = state.email ?: return
        viewModelScope.launch {
            loading()
            try {
                if (state.hasPassword) {
                    ApiClient.authApi.deleteAccount(LoginRequest(email, password))
                } else {
                    val token = tokenStore.token ?: throw IllegalStateException("no session")
                    ApiClient.authApi.deleteMe(token, DeleteMeRequest(null))
                }
                signedOut()
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    /** The server drops every session on a password change and returns a fresh token, so this
     * device stays logged in while any other device has to log in again. */
    fun changePassword(oldPassword: String, newPassword: String) {
        val email = _uiState.value.email ?: return
        viewModelScope.launch {
            loading()
            try {
                signedIn(
                    ApiClient.authApi.changePassword(ChangePasswordRequest(email, oldPassword, newPassword)),
                    notice = "비밀번호를 변경했어요."
                )
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    fun logout() = signedOut()

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null, notice = null)
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
