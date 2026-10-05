package com.mhmh2.englishbite.data

import android.app.Application
import android.os.Build
import com.google.gson.Gson
import com.mhmh2.englishbite.BuildConfig
import retrofit2.HttpException
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Home-grown crash reporting, so a tester's crash doesn't vanish when they just say "it
 * closed". An uncaught exception is written to a small file (app version, device model, Android
 * version, stack trace - nothing about the person) and the Android default handler still runs
 * afterwards. On the next launch the saved reports are posted to the API and deleted. Read them
 * on the server with view_crashes.py. */
object CrashReporter {
    private const val MAX_PENDING = 5

    fun install(app: Application) {
        val dir = File(app.filesDir, "crashes").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
                    .toString().take(6000)
                val report = CrashReport(
                    app_version = BuildConfig.VERSION_CODE,
                    device = "${Build.MANUFACTURER} ${Build.MODEL}",
                    android = Build.VERSION.RELEASE,
                    trace = trace
                )
                File(dir, "crash-${System.currentTimeMillis()}.json").writeText(Gson().toJson(report))
                dir.listFiles()?.sortedBy { it.name }?.dropLast(MAX_PENDING)?.forEach { it.delete() }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Off the main thread. Anything that fails for a network reason is kept for the next launch. */
    suspend fun uploadPending(app: Application) {
        val files = File(app.filesDir, "crashes").listFiles()?.sortedBy { it.name } ?: return
        for (file in files) {
            try {
                val report = Gson().fromJson(file.readText(), CrashReport::class.java)
                ApiClient.ingestApi.reportCrash(report)
                file.delete()
            } catch (e: HttpException) {
                // A 4xx other than "slow down" means the server will never accept this file.
                if (e.code() in 400..499 && e.code() != 429) file.delete() else return
            } catch (e: Exception) {
                return
            }
        }
    }
}
