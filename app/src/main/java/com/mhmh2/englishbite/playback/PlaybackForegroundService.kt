package com.mhmh2.englishbite.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mhmh2.englishbite.MainActivity
import com.mhmh2.englishbite.R

private const val CHANNEL_ID = "playback"
private const val NOTIFICATION_ID = 1
const val EXTRA_VIDEO_TITLE = "video_title"

/** Exists purely so Android doesn't tear down video playback the moment the screen turns off
 * or the app leaves the foreground - a foreground service (with the mandatory visible
 * notification) is what tells the OS "audio is genuinely still playing, don't suspend this
 * process." StudyScreen starts this when a video begins playing and stops it when leaving the
 * screen; it doesn't drive playback itself; that's still the YouTubePlayer instance in
 * StudyScreen; also enableBackgroundPlayback(true) there. */
class PlaybackForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_VIDEO_TITLE) ?: getString(R.string.app_name)
        ensureChannel()

        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this@PlaybackForegroundService, MainActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        )
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "재생 중", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context, videoTitle: String) {
            val intent = Intent(context, PlaybackForegroundService::class.java)
                .putExtra(EXTRA_VIDEO_TITLE, videoTitle)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackForegroundService::class.java))
        }
    }
}
