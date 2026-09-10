package com.mhmh2.englishbite.admin

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.adminDataStore by preferencesDataStore(name = "admin_prefs")
private val ADMIN_TOKEN_KEY = stringPreferencesKey("admin_token")

/** Stores the admin token entered once in AdminSyncScreen, so only whoever knows the server's
 * ADMIN_TOKEN (set via server.py's env var) can trigger a scan+upload from this app. */
object AdminTokenStore {
    fun tokenFlow(context: Context): Flow<String?> =
        context.adminDataStore.data.map { it[ADMIN_TOKEN_KEY] }

    suspend fun saveToken(context: Context, token: String) {
        context.adminDataStore.edit { it[ADMIN_TOKEN_KEY] = token }
    }
}
