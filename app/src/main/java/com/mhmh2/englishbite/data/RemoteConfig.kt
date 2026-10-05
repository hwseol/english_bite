package com.mhmh2.englishbite.data

import android.content.Context
import com.google.gson.Gson
import com.mhmh2.englishbite.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A tiny JSON file on GitHub Pages (docs/app-config.json) that the release app reads at start
 * and on resume. It exists so two things never again require shipping a new app version:
 *
 *  - `api_base`: where the API lives. Changing servers or moving from the sslip.io hostname to
 *    a real domain is just editing that file - every installed app follows.
 *  - `min_version_code`: builds older than this show an "update required" screen instead of
 *    failing in confusing ways against a server that has moved on.
 *
 * Release builds only (USE_REMOTE_CONFIG); debug builds always talk to the dev server. If the
 * file can't be fetched the last good copy is used, and with none, the address built into the
 * app - so this can only ever add resilience, never be the thing that breaks the app. */
object RemoteConfig {
    private const val CONFIG_URL = "https://hwseol.github.io/english_bite/app-config.json"
    private const val PREFS = "remote_config"
    private const val KEY_JSON = "json"
    private const val MIN_REFETCH_MS = 10 * 60 * 1000L

    data class Config(
        val api_base: String? = null,
        val min_version_code: Int = 0,
        val latest_version_code: Int = 0
    )

    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config

    @Volatile private var apiBase: HttpUrl? = null
    @Volatile private var lastFetchMs = 0L

    fun init(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
            ?.let { apply(it) }
    }

    /** Blocking network call - run it off the main thread. Silent on any failure. */
    fun refresh(context: Context) {
        if (!BuildConfig.USE_REMOTE_CONFIG) return
        val now = System.currentTimeMillis()
        if (now - lastFetchMs < MIN_REFETCH_MS) return
        lastFetchMs = now
        runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build()
            val body = client.newCall(Request.Builder().url(CONFIG_URL).build()).execute().use {
                if (!it.isSuccessful) return@runCatching
                it.body?.string()
            } ?: return@runCatching
            if (apply(body)) {
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_JSON, body).apply()
            }
        }
    }

    private fun apply(json: String): Boolean {
        val cfg = runCatching { Gson().fromJson(json, Config::class.java) }.getOrNull() ?: return false
        // Only ever follow an https address: a typo in the file must not be able to point the
        // app at plain HTTP, where logins would travel unencrypted.
        apiBase = cfg.api_base?.toHttpUrlOrNull()?.takeIf { it.isHttps }
        _config.value = cfg
        return true
    }

    /** The server address from the config file, or null to use the one built into the app. */
    fun currentApiBase(): HttpUrl? = if (BuildConfig.USE_REMOTE_CONFIG) apiBase else null
}
