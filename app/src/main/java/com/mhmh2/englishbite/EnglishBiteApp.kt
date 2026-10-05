package com.mhmh2.englishbite

import android.app.Application
import com.mhmh2.englishbite.data.CrashReporter
import com.mhmh2.englishbite.data.RemoteConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EnglishBiteApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        RemoteConfig.init(this)
        scope.launch {
            // Config first, so saved crash reports go to wherever the API currently lives.
            RemoteConfig.refresh(this@EnglishBiteApp)
            CrashReporter.uploadPending(this@EnglishBiteApp)
        }
    }
}
