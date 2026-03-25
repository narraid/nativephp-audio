package com.theunwindfront.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the audio process alive when the app moves to the
 * background or the screen turns off. Android requires a visible notification for any
 * process that wants to continue running without user interaction.
 *
 * Lifecycle:
 *  - Started by [AudioFunctions.Play] when playback begins.
 *  - Notification text updated by [AudioFunctions.SetMetadata] when metadata arrives.
 *  - Stopped by [AudioFunctions.Stop] when playback ends.
 */
class AudioService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Now Playing"
        val artist = intent?.getStringExtra(EXTRA_ARTIST)
        startForeground(NOTIFICATION_ID, buildNotification(title, artist))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while audio is playing in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, artist: String?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (!artist.isNullOrEmpty()) {
            builder.setContentText(artist)
        }

        return builder.build()
    }

    companion object {
        const val NOTIFICATION_ID = 1338
        const val CHANNEL_ID = "nativephp_audio_playback"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"

        /** Start (or restart) the foreground service. Safe to call when already running. */
        fun start(context: Context, title: String = "Now Playing", artist: String? = null) {
            val intent = Intent(context, AudioService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                artist?.let { putExtra(EXTRA_ARTIST, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the foreground service and dismiss the notification. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AudioService::class.java))
        }

        /** Update the notification text without restarting the service. */
        fun updateNotification(context: Context, title: String, artist: String? = null) {
            start(context, title, artist)
        }
    }
}
