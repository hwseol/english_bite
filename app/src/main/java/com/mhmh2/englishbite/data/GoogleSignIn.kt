package com.mhmh2.englishbite.data

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.mhmh2.englishbite.BuildConfig

/** A failure worth showing the person as it is (already in Korean). */
class GoogleSignInException(message: String) : Exception(message)

/**
 * "구글로 계속하기": the system's Google account sheet (Credential Manager) gives an ID token for our
 * web client, which the server then checks with Google (POST /auth/google). The button is hidden
 * when the build has no client ID.
 */
object GoogleSignIn {
    val available: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotEmpty()

    /** The ID token, or null if the person closed the sheet. Throws [GoogleSignInException] otherwise. */
    suspend fun idToken(activityContext: Context): String? {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build())
            .build()
        val credential = try {
            CredentialManager.create(activityContext).getCredential(activityContext, request).credential
        } catch (e: GetCredentialCancellationException) {
            return null
        } catch (e: NoCredentialException) {
            throw GoogleSignInException("이 폰에서 쓸 수 있는 구글 계정이 없어요." + detail(e))
        } catch (e: GetCredentialException) {
            throw GoogleSignInException("구글 계정을 가져오지 못했어요." + detail(e))
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw GoogleSignInException("구글 로그인을 하지 못했어요.")
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    // The reason, for bug reports ("[28444] Developer console is not set up correctly" = the Android
    // OAuth client's package / SHA-1 does not match, or the ID is not a web client).
    private fun detail(e: GetCredentialException): String {
        Log.w("GoogleSignIn", "getCredential failed", e)
        return "\n(${e.type.substringAfterLast('.')}: ${e.errorMessage ?: ""})"
    }
}
