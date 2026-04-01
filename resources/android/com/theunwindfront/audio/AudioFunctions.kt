package com.theunwindfront.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.lifecycle.NativePHPLifecycle
import com.nativephp.mobile.ui.MainActivity
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URL

class AudioFunctions {
    companion object {

        // ── Player State ──────────────────────────────────────────────────────
        private var mediaPlayer: MediaPlayer? = null
        private var mediaSession: MediaSessionCompat? = null
        private var activityRef: WeakReference<FragmentActivity>? = null
        internal var currentUrl: String = ""

        // ── Stored Metadata (single source of truth) ──────────────────────────
        private var metaTitle: String? = null
        private var metaArtist: String? = null
        private var metaAlbum: String? = null
        private var metaDurationMs: Long? = null
        private var metaArtworkSource: String? = null
        internal var currentArtwork: Bitmap? = null

        // ── Audio Focus ───────────────────────────────────────────────────────
        private const val DUCK_FACTOR = 0.2f
        private var audioManager: AudioManager? = null
        private var audioFocusRequest: AudioFocusRequest? = null
        internal var userVolume: Float = 1.0f
        private var pausedByFocusLoss = false

        // ── Progress Timer ────────────────────────────────────────────────────
        private const val DEFAULT_PROGRESS_INTERVAL_MS: Long = 10_000
        private var progressHandler: Handler? = null
        private var progressRunnable: Runnable? = null
        private var progressIntervalMs: Long = 0

        // ── Lifecycle ─────────────────────────────────────────────────────────

        init {
            // When the app returns to foreground: refresh the activity reference so events
            // can be dispatched safely, and restart the progress timer if audio is playing.
            NativePHPLifecycle.on(NativePHPLifecycle.Events.ON_RESUME) { _ ->
                MainActivity.instance?.let { activityRef = WeakReference(it) }
                if (mediaPlayer?.isPlaying == true) {
                    startProgressTimer(DEFAULT_PROGRESS_INTERVAL_MS)
                }
            }
            // When the app backgrounds: stop the progress timer. NativeActionCoordinator
            // calls commitNow() on the fragment manager which throws IllegalStateException
            // after onSaveInstanceState — dispatching events while paused is unsafe.
            NativePHPLifecycle.on(NativePHPLifecycle.Events.ON_PAUSE) { _ ->
                stopProgressTimer()
            }
        }

        // ── Event Helpers ─────────────────────────────────────────────────────

        private const val EVENT_PREFIX = "Theunwindfront\\Audio\\Events\\"

        internal fun sendEvent(name: String, payload: Map<String, Any>) {
            val activity = activityRef?.get() ?: return
            if (activity.isDestroyed || activity.isFinishing) return
            val json = JSONObject(payload).toString()
            Handler(Looper.getMainLooper()).post {
                NativeActionCoordinator.dispatchEvent(activity, EVENT_PREFIX + name, json)
            }
        }

        private fun statePayload(): Map<String, Any> =
            mapOf("position" to positionSeconds(), "duration" to durationSeconds(), "url" to currentUrl)

        // ── Position / Duration ───────────────────────────────────────────────

        private fun positionSeconds(): Double = try {
            (mediaPlayer?.currentPosition ?: 0) / 1000.0
        } catch (_: IllegalStateException) { 0.0 }

        private fun durationSeconds(): Double = try {
            val ms = mediaPlayer?.duration ?: 0
            if (ms < 0) 0.0 else ms / 1000.0
        } catch (_: IllegalStateException) { 0.0 }

        // ── Session Metadata ──────────────────────────────────────────────────

        /**
         * Builds MediaMetadataCompat from stored metadata fields.
         * Includes artwork bitmap if already loaded.
         */
        private fun buildSessionMetadata(): MediaMetadataCompat {
            val builder = MediaMetadataCompat.Builder()
            metaTitle?.let     { builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, it) }
            metaArtist?.let    { builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
            metaAlbum?.let     { builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it) }
            metaDurationMs?.let { builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it) }
            currentArtwork?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            return builder.build()
        }

        /**
         * Loads artwork from URL or local file on a background thread.
         * On success, stores the bitmap and calls [onLoaded] on the main thread.
         */
        private fun loadArtworkAsync(source: String, onLoaded: (Bitmap) -> Unit) {
            Thread {
                try {
                    val bitmap = if (source.startsWith("http://") || source.startsWith("https://")) {
                        BitmapFactory.decodeStream(URL(source).openStream())
                    } else {
                        BitmapFactory.decodeFile(source)
                    }
                    bitmap?.let { bmp ->
                        currentArtwork = bmp
                        Handler(Looper.getMainLooper()).post { onLoaded(bmp) }
                    }
                } catch (_: Exception) { /* artwork is optional */ }
            }.start()
        }

        /**
         * Applies stored metadata to the session and optionally loads artwork async.
         * Call after metadata fields have been updated.
         */
        private fun applySessionMetadata(context: Context) {
            val session = getOrCreateSession(context)
            session.setMetadata(buildSessionMetadata())

            metaArtworkSource?.let { src ->
                loadArtworkAsync(src) {
                    session.setMetadata(buildSessionMetadata())
                    if (mediaPlayer != null) {
                        AudioService.updateNotification(context, metaTitle ?: "Now Playing", metaArtist)
                    }
                }
            }
        }

        // ── MediaSession ──────────────────────────────────────────────────────

        fun getOrCreateSession(context: Context): MediaSessionCompat {
            return mediaSession ?: MediaSessionCompat(context, "NativePHPAudio").also { session ->
                session.isActive = true
                session.setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        mediaPlayer?.start()
                        updateSessionState()
                        activityRef?.get()?.let { AudioService.refreshPlayState(it) }
                        val payload = statePayload()
                        sendEvent("PlaybackResumed",    payload)
                        sendEvent("RemotePlayReceived", payload)
                    }

                    override fun onPause() {
                        mediaPlayer?.pause()
                        updateSessionState()
                        activityRef?.get()?.let { AudioService.refreshPlayState(it) }
                        val payload = statePayload()
                        sendEvent("PlaybackPaused",      payload)
                        sendEvent("RemotePauseReceived", payload)
                    }

                    override fun onSkipToNext() {
                        sendEvent("RemoteNextTrackReceived", statePayload())
                    }

                    override fun onSkipToPrevious() {
                        sendEvent("RemotePreviousTrackReceived", statePayload())
                    }

                    override fun onSeekTo(pos: Long) {
                        val from = positionSeconds()
                        mediaPlayer?.seekTo(pos.toInt())
                        updateSessionState()
                        sendEvent("RemoteSeekReceived", mapOf(
                            "position" to from, "duration" to durationSeconds(),
                            "url" to currentUrl, "seekTo" to pos / 1000.0
                        ))
                    }

                    override fun onStop() {
                        val payload = statePayload()
                        releasePlayer()
                        sendEvent("PlaybackStopped",    payload)
                        sendEvent("RemoteStopReceived", payload)
                    }
                })
                mediaSession = session
            }
        }

        fun getSessionToken(context: Context): MediaSessionCompat.Token =
            getOrCreateSession(context).sessionToken

        fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

        fun togglePlayPause() {
            val payload = statePayload()
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                updateSessionState()
                sendEvent("PlaybackPaused",      payload)
                sendEvent("RemotePauseReceived", payload)
            } else {
                mediaPlayer?.start()
                updateSessionState()
                sendEvent("PlaybackResumed",    payload)
                sendEvent("RemotePlayReceived", payload)
            }
        }

        fun updateSessionState() {
            val session = mediaSession ?: return
            val playing = mediaPlayer?.isPlaying == true
            val state = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    (mediaPlayer?.currentPosition ?: 0).toLong(),
                    if (playing) 1.0f else 0.0f
                )
                .build()
            session.setPlaybackState(state)
        }

        // ── Audio Focus ───────────────────────────────────────────────────────

        private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    pausedByFocusLoss = false
                    if (mediaPlayer?.isPlaying == true) {
                        mediaPlayer?.pause()
                        updateSessionState()
                        activityRef?.get()?.let { AudioService.refreshPlayState(it) }
                        val payload = statePayload()
                        sendEvent("PlaybackPaused",  payload)
                        sendEvent("AudioFocusLost",  payload)
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (mediaPlayer?.isPlaying == true) {
                        pausedByFocusLoss = true
                        mediaPlayer?.pause()
                        updateSessionState()
                        activityRef?.get()?.let { AudioService.refreshPlayState(it) }
                        val payload = statePayload()
                        sendEvent("PlaybackPaused",          payload)
                        sendEvent("AudioFocusLostTransient", payload)
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    mediaPlayer?.setVolume(userVolume * DUCK_FACTOR, userVolume * DUCK_FACTOR)
                    sendEvent("AudioFocusDucked", statePayload())
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    mediaPlayer?.setVolume(userVolume, userVolume)
                    if (pausedByFocusLoss && mediaPlayer != null) {
                        pausedByFocusLoss = false
                        mediaPlayer?.start()
                        updateSessionState()
                        activityRef?.get()?.let { AudioService.refreshPlayState(it) }
                        sendEvent("PlaybackResumed", statePayload())
                    }
                    sendEvent("AudioFocusGained", statePayload())
                }
            }
        }

        fun requestAudioFocus(context: Context) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager = am
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()
                audioFocusRequest = request
                am.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        }

        fun abandonAudioFocus() {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusListener)
            }
            audioManager = null
            audioFocusRequest = null
            pausedByFocusLoss = false
        }

        // ── Progress Timer ────────────────────────────────────────────────────

        fun startProgressTimer(intervalMs: Long) {
            progressIntervalMs = intervalMs
            stopProgressTimer()
            if (intervalMs <= 0) return
            val handler = Handler(Looper.getMainLooper())
            progressHandler = handler
            val runnable = object : Runnable {
                override fun run() {
                    if (mediaPlayer?.isPlaying == true) {
                        sendEvent("PlaybackProgressUpdated", statePayload())
                    }
                    handler.postDelayed(this, intervalMs)
                }
            }
            progressRunnable = runnable
            handler.postDelayed(runnable, intervalMs)
        }

        fun stopProgressTimer() {
            progressRunnable?.let { progressHandler?.removeCallbacks(it) }
            progressHandler = null
            progressRunnable = null
        }

        // ── Cleanup ───────────────────────────────────────────────────────────

        /**
         * Fully releases the MediaPlayer, abandons audio focus, and stops the service.
         */
        private fun releasePlayer() {
            stopProgressTimer()
            try { mediaPlayer?.stop() } catch (_: IllegalStateException) { /* already stopped or in error state */ }
            mediaPlayer?.release()
            mediaPlayer = null
            abandonAudioFocus()
            activityRef?.get()?.let { AudioService.stop(it) }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Bridge Functions
    // ═════════════════════════════════════════════════════════════════════════

    class Load(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            activityRef = WeakReference(activity)

            val params   = JSONObject(parameters)
            val url      = params.optString("url")
            val title    = params.optString("title").takeIf { it.isNotEmpty() }
            val artist   = params.optString("artist").takeIf { it.isNotEmpty() }
            val album    = params.optString("album").takeIf { it.isNotEmpty() }
            val artwork  = params.optString("artwork").takeIf { it.isNotEmpty() }
            val duration = if (params.has("duration")) params.optDouble("duration") else null

            currentUrl = url

            if (title != null) {
                metaTitle         = title
                metaArtist        = artist
                metaAlbum         = album
                metaDurationMs    = duration?.let { (it * 1000).toLong() }
                metaArtworkSource = artwork
            }

            // All MediaPlayer work must happen on the main thread to avoid race conditions
            // when switching tracks quickly — stop/release/create must be atomic.
            Handler(Looper.getMainLooper()).post {
                stopProgressTimer()
                try { mediaPlayer?.stop() } catch (_: IllegalStateException) {}
                mediaPlayer?.release()
                mediaPlayer = null

                if (metaTitle != null) {
                    applySessionMetadata(activity)
                }

                try {
                    mediaPlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        setDataSource(activity, Uri.parse(url))

                        setOnPreparedListener { _ ->
                            // Do NOT call start() — audio is loaded but paused.
                            updateSessionState()
                            val payload = mutableMapOf<String, Any>("url" to url)
                            title?.let    { payload["title"]    = it }
                            artist?.let   { payload["artist"]   = it }
                            album?.let    { payload["album"]    = it }
                            duration?.let { payload["duration"] = it }
                            sendEvent("PlaybackLoaded", payload)
                        }

                        setOnCompletionListener {
                            stopProgressTimer()
                            sendEvent("PlaybackCompleted", mapOf(
                                "url" to currentUrl, "duration" to durationSeconds()
                            ))
                        }

                        setOnErrorListener { _, what, extra ->
                            stopProgressTimer()
                            sendEvent("PlaybackFailed", mapOf(
                                "url" to currentUrl, "error" to "MediaPlayer error: what=$what extra=$extra"
                            ))
                            false
                        }

                        prepareAsync()
                    }
                } catch (e: Exception) {
                    sendEvent("PlaybackFailed", mapOf(
                        "url" to url, "error" to (e.message ?: "Unknown error")
                    ))
                }
            }

            return mapOf("success" to true)
        }
    }

    class Play(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            activityRef = WeakReference(activity)

            val params   = JSONObject(parameters)
            val url      = params.optString("url")
            val title    = params.optString("title").takeIf { it.isNotEmpty() }
            val artist   = params.optString("artist").takeIf { it.isNotEmpty() }
            val album    = params.optString("album").takeIf { it.isNotEmpty() }
            val artwork  = params.optString("artwork").takeIf { it.isNotEmpty() }
            val duration = if (params.has("duration")) params.optDouble("duration") else null

            currentUrl = url

            // Store inline metadata if provided; otherwise preserve metadata from a prior setMetadata call.
            if (title != null) {
                metaTitle         = title
                metaArtist        = artist
                metaAlbum         = album
                metaDurationMs    = duration?.let { (it * 1000).toLong() }
                metaArtworkSource = artwork
            }

            // All MediaPlayer work must happen on the main thread to avoid race conditions
            // when switching tracks quickly — stop/release/create must be atomic.
            Handler(Looper.getMainLooper()).post {
                stopProgressTimer()
                try { mediaPlayer?.stop() } catch (_: IllegalStateException) {}
                mediaPlayer?.release()
                mediaPlayer = null

                // Apply stored metadata to MediaSession (covers both inline and prior setMetadata).
                if (metaTitle != null) {
                    applySessionMetadata(activity)
                }

                try {
                    mediaPlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        setDataSource(activity, Uri.parse(url))

                        setOnPreparedListener { mp ->
                            requestAudioFocus(activity)
                            mp.start()
                            updateSessionState()
                            startProgressTimer(DEFAULT_PROGRESS_INTERVAL_MS)
                            // Use stored metadata for service notification, falling back to defaults.
                            val serviceTitle  = metaTitle ?: "Now Playing"
                            val serviceArtist = metaArtist
                            AudioService.start(activity, serviceTitle, serviceArtist)
                            val payload = mutableMapOf<String, Any>("url" to url)
                            title?.let    { payload["title"]    = it }
                            artist?.let   { payload["artist"]   = it }
                            album?.let    { payload["album"]    = it }
                            duration?.let { payload["duration"] = it }
                            sendEvent("PlaybackStarted", payload)
                        }

                        setOnCompletionListener {
                            stopProgressTimer()
                            sendEvent("PlaybackCompleted", mapOf(
                                "url" to currentUrl, "duration" to durationSeconds()
                            ))
                        }

                        setOnErrorListener { _, what, extra ->
                            stopProgressTimer()
                            sendEvent("PlaybackFailed", mapOf(
                                "url" to currentUrl, "error" to "MediaPlayer error: what=$what extra=$extra"
                            ))
                            false
                        }

                        prepareAsync()
                    }
                } catch (e: Exception) {
                    sendEvent("PlaybackFailed", mapOf(
                        "url" to url, "error" to (e.message ?: "Unknown error")
                    ))
                }
            }

            return mapOf("success" to true)
        }
    }

    class Pause(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            mediaPlayer?.pause()
            updateSessionState()
            startProgressTimer(DEFAULT_PROGRESS_INTERVAL_MS)
            AudioService.refreshPlayState(context)
            sendEvent("PlaybackPaused", statePayload())
            return mapOf("success" to true)
        }
    }

    class Resume(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            requestAudioFocus(context)
            mediaPlayer?.start()
            updateSessionState()
            startProgressTimer(DEFAULT_PROGRESS_INTERVAL_MS)
            AudioService.refreshPlayState(context)
            sendEvent("PlaybackResumed", statePayload())
            return mapOf("success" to true)
        }
    }

    class Stop(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val payload = statePayload()
            releasePlayer()
            sendEvent("PlaybackStopped", payload)
            return mapOf("success" to true)
        }
    }

    class Seek(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val params   = JSONObject(parameters)
            val seconds  = params.optDouble("seconds", 0.0)
            val from     = positionSeconds()
            val duration = durationSeconds()
            mediaPlayer?.seekTo((seconds * 1000).toInt())
            startProgressTimer(DEFAULT_PROGRESS_INTERVAL_MS)
            sendEvent("PlaybackSeeked", mapOf(
                "from" to from, "to" to seconds, "duration" to duration, "url" to currentUrl
            ))
            return mapOf("success" to true)
        }
    }

    class SetVolume(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val level = JSONObject(parameters).optDouble("level", 1.0).toFloat()
            userVolume = level
            mediaPlayer?.setVolume(level, level)
            return mapOf("success" to true)
        }
    }

    class GetDuration(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return mapOf("duration" to durationSeconds())
        }
    }

    class GetCurrentPosition(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return mapOf("position" to positionSeconds())
        }
    }

    /**
     * Returns the full current playback state from the native layer.
     * Used by PHP to reconcile state after a runtime restart (e.g. OS killed PHP in background).
     */
    class GetState(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val state = mutableMapOf<String, Any>(
                "url"        to currentUrl,
                "position"   to positionSeconds(),
                "duration"   to durationSeconds(),
                "isPlaying"  to (mediaPlayer?.isPlaying == true),
                "hasPlayer"  to (mediaPlayer != null),
            )
            metaTitle?.let         { state["title"]    = it }
            metaArtist?.let        { state["artist"]   = it }
            metaAlbum?.let         { state["album"]    = it }
            metaDurationMs?.let    { state["duration"] = it / 1000.0 }
            metaArtworkSource?.let { state["artwork"]  = it }
            return state
        }
    }

    /**
     * Sets track metadata on the MediaSession for lock screens, Bluetooth, Android Auto,
     * and notification controls.
     *
     * Safe to call before Play — metadata is stored and applied when Play starts.
     * Safe to call after Play — notification and lock screen update immediately.
     */
    class SetMetadata(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val params = JSONObject(parameters)
            val title  = params.optString("title").takeIf { it.isNotEmpty() }
                ?: return mapOf("success" to false, "error" to "title is required")

            // Replace all stored metadata (nil fields are intentionally cleared).
            metaTitle         = title
            metaArtist        = params.optString("artist").takeIf { it.isNotEmpty() }
            metaAlbum         = params.optString("album").takeIf { it.isNotEmpty() }
            metaDurationMs    = if (params.has("duration")) (params.optDouble("duration", 0.0) * 1000).toLong() else null
            metaArtworkSource = params.optString("artwork").takeIf { it.isNotEmpty() }

            // Apply to session immediately.
            applySessionMetadata(context)
            if (mediaPlayer != null) updateSessionState()

            // Only update the foreground service notification if playback is active.
            // Starting the service before Play would show a notification with no audio.
            if (mediaPlayer != null) {
                AudioService.updateNotification(context, title, metaArtist)
            }

            return mapOf("success" to true)
        }
    }
}
