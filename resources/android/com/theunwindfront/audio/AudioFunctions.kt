package com.theunwindfront.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URL

class AudioFunctions {
    companion object {
        private var mediaPlayer: MediaPlayer? = null
        private var mediaSession: MediaSessionCompat? = null

        /** Last artwork bitmap, shared with AudioService for the notification large icon. */
        internal var currentArtwork: Bitmap? = null

        /** URL of the currently loaded track — used in completion/error events. */
        internal var currentUrl: String = ""

        /** Weak reference to the host activity, set on first Play call. */
        private var activityRef: WeakReference<FragmentActivity>? = null

        private fun JSONObject.toMap(): Map<String, Any> {
            val map = mutableMapOf<String, Any>()
            val keys = this.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = this.get(key)
            }
            return map
        }

        fun getOrCreateSession(context: Context): MediaSessionCompat {
            return mediaSession ?: MediaSessionCompat(context, "NativePHPAudio").also {
                it.isActive = true
                mediaSession = it
            }
        }

        fun getSessionToken(context: Context): MediaSessionCompat.Token =
            getOrCreateSession(context).sessionToken

        fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

        /** Current playback position in seconds. */
        private fun positionSeconds(): Double = (mediaPlayer?.currentPosition ?: 0) / 1000.0

        /** Total duration in seconds. */
        private fun durationSeconds(): Double = (mediaPlayer?.duration ?: 0) / 1000.0

        /**
         * Dispatch a Laravel event to PHP via the WebView bridge.
         * Must be called from any thread — marshals to main thread internally.
         */
        internal fun sendEvent(event: String, payload: Map<String, Any>) {
            val activity = activityRef?.get() ?: return
            val json = JSONObject(payload).toString()
            Handler(Looper.getMainLooper()).post {
                NativeActionCoordinator.dispatchEvent(activity, event, json)
            }
        }

        /** Toggle play/pause from the notification action button. */
        fun togglePlayPause() {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                updateSessionState()
                sendEvent("Theunwindfront\\Audio\\Events\\PlaybackPaused", mapOf(
                    "position" to positionSeconds(),
                    "duration" to durationSeconds()
                ))
            } else {
                mediaPlayer?.start()
                updateSessionState()
                sendEvent("Theunwindfront\\Audio\\Events\\PlaybackResumed", mapOf(
                    "position" to positionSeconds(),
                    "duration" to durationSeconds()
                ))
            }
        }

        /** Sync MediaSession PlaybackState with current MediaPlayer state. */
        fun updateSessionState() {
            val session = mediaSession ?: return
            val playing = mediaPlayer?.isPlaying == true
            val state = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    (mediaPlayer?.currentPosition ?: 0).toLong(),
                    if (playing) 1.0f else 0.0f
                )
                .build()
            session.setPlaybackState(state)
        }
    }

    class Play(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            // Store activity for event dispatch from all functions and the service
            activityRef = WeakReference(activity)

            val params = JSONObject(parameters)
            val url = params.optString("url")
            val result = JSONObject()

            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()

                currentUrl = url

                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(activity, Uri.parse(url))
                    prepare()

                    setOnCompletionListener {
                        sendEvent("Theunwindfront\\Audio\\Events\\PlaybackCompleted", mapOf(
                            "url" to currentUrl,
                            "duration" to durationSeconds()
                        ))
                    }

                    setOnErrorListener { _, what, extra ->
                        sendEvent("Theunwindfront\\Audio\\Events\\PlaybackFailed", mapOf(
                            "url" to currentUrl,
                            "error" to "MediaPlayer error: what=$what extra=$extra"
                        ))
                        false
                    }

                    start()
                }

                // Start the foreground service so playback survives backgrounding
                AudioService.start(activity)

                sendEvent("Theunwindfront\\Audio\\Events\\PlaybackStarted", mapOf("url" to url))

                result.put("success", true)
            } catch (e: Exception) {
                sendEvent("Theunwindfront\\Audio\\Events\\PlaybackFailed", mapOf(
                    "url" to url,
                    "error" to (e.message ?: "Unknown error")
                ))
                result.put("success", false)
                result.put("error", e.message)
            }

            return result.toMap()
        }
    }

    class Pause(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            mediaPlayer?.pause()
            updateSessionState()
            AudioService.refreshPlayState(context)
            sendEvent("Theunwindfront\\Audio\\Events\\PlaybackPaused", mapOf(
                "position" to positionSeconds(),
                "duration" to durationSeconds()
            ))
            return mapOf("success" to true)
        }
    }

    class Resume(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            mediaPlayer?.start()
            updateSessionState()
            AudioService.refreshPlayState(context)
            sendEvent("Theunwindfront\\Audio\\Events\\PlaybackResumed", mapOf(
                "position" to positionSeconds(),
                "duration" to durationSeconds()
            ))
            return mapOf("success" to true)
        }
    }

    class Stop(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            // Capture state before releasing the player
            val position = positionSeconds()
            val duration = durationSeconds()
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            AudioService.stop(context)
            sendEvent("Theunwindfront\\Audio\\Events\\PlaybackStopped", mapOf(
                "position" to position,
                "duration" to duration
            ))
            return mapOf("success" to true)
        }
    }

    class Seek(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val params = JSONObject(parameters)
            val seconds = params.optDouble("seconds", 0.0)
            val from = positionSeconds()
            val duration = durationSeconds()
            mediaPlayer?.seekTo((seconds * 1000).toInt())
            sendEvent("Theunwindfront\\Audio\\Events\\PlaybackSeeked", mapOf(
                "from" to from,
                "to" to seconds,
                "duration" to duration
            ))
            return mapOf("success" to true)
        }
    }

    class SetVolume(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val params = JSONObject(parameters)
            val level = params.optDouble("level", 1.0).toFloat()
            mediaPlayer?.setVolume(level, level)
            return mapOf("success" to true)
        }
    }

    class GetDuration(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val duration = mediaPlayer?.duration ?: 0
            return mapOf("duration" to duration / 1000.0)
        }
    }

    class GetCurrentPosition(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val position = mediaPlayer?.currentPosition ?: 0
            return mapOf("position" to position / 1000.0)
        }
    }

    /**
     * Sets track metadata on the MediaSession for display on lock screens,
     * Bluetooth devices, Android Auto, and notification controls.
     *
     * Expected parameters:
     *  - `title`    (String, required) – track title.
     *  - `artist`   (String, optional) – artist name.
     *  - `album`    (String, optional) – album name.
     *  - `artwork`  (String, optional) – HTTP/S URL or absolute file path of the artwork image.
     *  - `duration` (Number, optional) – total duration in seconds.
     */
    class SetMetadata(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val params = JSONObject(parameters)
            val title = params.optString("title").takeIf { it.isNotEmpty() }
                ?: return mapOf("success" to false, "error" to "title is required")

            val session = getOrCreateSession(context)

            val metaBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)

            params.optString("artist").takeIf { it.isNotEmpty() }?.let {
                metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it)
            }
            params.optString("album").takeIf { it.isNotEmpty() }?.let {
                metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it)
            }
            if (params.has("duration")) {
                val durationMs = (params.optDouble("duration", 0.0) * 1000).toLong()
                metaBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            }

            params.optString("artwork").takeIf { it.isNotEmpty() }?.let { artworkSrc ->
                try {
                    val bitmap: Bitmap? = if (artworkSrc.startsWith("http://") || artworkSrc.startsWith("https://")) {
                        val stream = URL(artworkSrc).openStream()
                        BitmapFactory.decodeStream(stream)
                    } else {
                        BitmapFactory.decodeFile(artworkSrc)
                    }
                    bitmap?.let {
                        currentArtwork = it
                        metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                    }
                } catch (_: Exception) { /* artwork is optional — ignore fetch failures */ }
            }

            session.setMetadata(metaBuilder.build())
            updateSessionState()

            // Refresh the foreground service notification with the new track info
            AudioService.updateNotification(
                context,
                title,
                params.optString("artist").takeIf { it.isNotEmpty() }
            )

            return mapOf("success" to true)
        }
    }
}
