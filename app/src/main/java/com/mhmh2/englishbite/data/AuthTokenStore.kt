package com.mhmh2.englishbite.data

import android.content.Context
import android.content.SharedPreferences

/** Session token plus the logged-in user's own email/nickname, in a SharedPreferences file of
 * their own - login is entirely optional everywhere else in the app, so this is the only place
 * that needs to know whether it's present. */
class AuthTokenStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("auth", Context.MODE_PRIVATE)

    val token: String? get() = prefs.getString(KEY_TOKEN, null)
    val email: String? get() = prefs.getString(KEY_EMAIL, null)
    val nickname: String? get() = prefs.getString(KEY_NICKNAME, null)

    /** False only for an account that signed up with Google and never set a password. */
    val hasPassword: Boolean get() = prefs.getBoolean(KEY_HAS_PASSWORD, true)

    fun save(auth: AuthResponse) {
        prefs.edit()
            .putString(KEY_TOKEN, auth.token)
            .putString(KEY_EMAIL, auth.email)
            .putString(KEY_NICKNAME, auth.nickname)
            .putBoolean(KEY_HAS_PASSWORD, auth.has_password != false)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_EMAIL = "email"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_HAS_PASSWORD = "has_password"
    }
}
