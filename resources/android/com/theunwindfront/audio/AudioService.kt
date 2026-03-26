package com.theunwindfront.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

/**
 * Foreground service that keeps the audio process alive when the app moves to the
 * background or the screen turns off.
 *
 * The notification shows:
 *  - Track title and artist name
 *  - Album artwork as the large icon (background on expanded view)
 *  - A play/pause action button that toggles playback without opening the app
 *  - A content intent so tapping the notification brings the app back to the foreground
 *
 * Lifecycle:
 *  - Started by [AudioFunctions.Play] when playback begins.
 *  - Notification updated by [AudioFunctions.SetMetadata] when metadata arrives.
 *  - Play/pause button toggled by [AudioFunctions.Pause] / [AudioFunctions.Resume].
 *  - Stopped by [AudioFunctions.Stop] when playback ends.
 */
class AudioService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAY_PAUSE -> {
                AudioFunctions.togglePlayPause()
                refreshNotification()
            }
            ACTION_NEXT -> {
                AudioFunctions.getOrCreateSession(this).controller?.transportControls?.skipToNext()
                refreshNotification()
            }
            ACTION_PREVIOUS -> {
                AudioFunctions.getOrCreateSession(this).controller?.transportControls?.skipToPrevious()
                refreshNotification()
            }
            ACTION_REFRESH_STATE -> {
                // Called after Pause/Resume from PHP side — just rebuild the notification
                refreshNotification()
            }
            else -> {
                // New play or metadata update — update stored title/artist and (re)start foreground
                intent?.getStringExtra(EXTRA_TITLE)?.let { currentTitle = it }
                intent?.getStringExtra(EXTRA_ARTIST).let { currentArtist = it }
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------

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

    private fun buildNotification(): Notification {
        val isPlaying = AudioFunctions.isPlaying()

        // --- Content intent: tap notification → bring app to foreground ---
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0,
            launchIntent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // --- Previous action button ---
        val prevIntent = Intent(this, AudioService::class.java).apply { action = ACTION_PREVIOUS }
        val prevPendingIntent = PendingIntent.getService(
            this, 2, prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // --- Play/pause action button ---
        val toggleIntent = Intent(this, AudioService::class.java).apply {
            action = ACTION_TOGGLE_PLAY_PAUSE
        }
        val togglePendingIntent = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIcon  = if (isPlaying) android.R.drawable.ic_media_pause
                             else android.R.drawable.ic_media_play
        val playPauseLabel = if (isPlaying) "Pause" else "Play"

        // --- Next action button ---
        val nextIntent = Intent(this, AudioService::class.java).apply { action = ACTION_NEXT }
        val nextPendingIntent = PendingIntent.getService(
            this, 3, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // --- MediaStyle with session token (enables lock-screen transport controls) ---
        val sessionToken = AudioFunctions.getSessionToken(this)
        val style = MediaStyle()
            .setMediaSession(sessionToken)
            .setShowActionsInCompactView(1) // show play/pause in compact view (index 1 = middle button)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentTitle)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(style)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent)
            .addAction(playPauseIcon, playPauseLabel, togglePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)

        if (!currentArtist.isNullOrEmpty()) {
            builder.setContentText(currentArtist)
        }

        // Artwork: shown as large icon and as the notification background on expanded view
        AudioFunctions.currentArtwork?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    /** Rebuilds and posts the notification in-place, without restarting the service. */
    private fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    // -------------------------------------------------------------------------

    companion object {
        const val NOTIFICATION_ID = 1338
        const val CHANNEL_ID = "nativephp_audio_playback"
        const val EXTRA_TITLE  = "title"
        const val EXTRA_ARTIST = "artist"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.theunwindfront.audio.ACTION_TOGGLE_PLAY_PAUSE"
        const val ACTION_NEXT              = "com.theunwindfront.audio.ACTION_NEXT"
        const val ACTION_PREVIOUS          = "com.theunwindfront.audio.ACTION_PREVIOUS"
        const val ACTION_REFRESH_STATE     = "com.theunwindfront.audio.ACTION_REFRESH_STATE"

        /** Last-known track title for notification rebuilds triggered by the toggle button. */
        var currentTitle: String  = "Now Playing"
        var currentArtist: String? = null

        /** Start (or restart) the foreground service with updated title/artist. */
        fun start(context: Context, title: String = "Now Playing", artist: String? = null) {
            currentTitle  = title
            currentArtist = artist
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

        /** Refresh the play/pause icon after a Pause or Resume call from the PHP side. */
        fun refreshPlayState(context: Context) {
            val intent = Intent(context, AudioService::class.java).apply {
                action = ACTION_REFRESH_STATE
            }
            context.startService(intent)
        }

        /** Update notification text when new metadata arrives. */
        fun updateNotification(context: Context, title: String, artist: String? = null) {
            start(context, title, artist)
        }
    }
}
