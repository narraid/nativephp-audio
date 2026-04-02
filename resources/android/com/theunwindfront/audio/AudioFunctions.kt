package com.narraid.audio

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
        private var metaMetadata: Map<String, Any>? = null

        // ── Background Event Queue ────────────────────────────────────────────
        private var isInBackground = false
        private val pendingEvents: MutableList<Map<String, Any>> = mutableListOf()

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

        // ── Playlist State ────────────────────────────────────────────────────
        private val playlist: MutableList<Map<String, Any>> = mutableListOf()
        private var playlistIndex: Int = -1
        private var repeatMode: String = "none"
        private var shuffleMode: Boolean = false
        private val shuffledOrder: MutableList<Int> = mutableListOf()

        // ── Application Context (for background playback) ─────────────────────
        private var appContext: Context? = null

        // ── Lifecycle ─────────────────────────────────────────────────────────

        init {
            // When the app returns to foreground: clear the background flag so events are
            // dispatched again, refresh the activity reference, and restart the progress timer.
            NativePHPLifecycle.on(NativePHPLifecycle.Events.ON_RESUME) { _ ->
                isInBackground = false
                MainActivity.instance?.let { activityRef = WeakReference(it) }
                if (mediaPlayer?.isPlaying == true) {
                    startProgressTimer(DEFAULT_PROGRESS_INTERVAL_MS)
                }
            }
            // When the app backgrounds: set the background flag and stop the progress timer.
            // NativeActionCoordinator calls commitNow() on the fragment manager which throws
            // IllegalStateException after onSaveInstanceState — dispatching events while
            // paused is unsafe, so events are queued instead.
            NativePHPLifecycle.on(NativePHPLifecycle.Events.ON_PAUSE) { _ ->
                isInBackground = true
                // stopProgressTimer()
            }
        }

        // ── Event Helpers ─────────────────────────────────────────────────────

        private const val EVENT_PREFIX = "Narraid\\Audio\\Events\\"

        internal fun sendEvent(name: String, payload: Map<String, Any>) {
            if (isInBackground) {
                pendingEvents.add(mapOf("event" to name, "payload" to payload))
                return
            }
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
                        if (playlist.isNotEmpty()) {
                            val nextIndex = if (repeatMode == "all")
                                (playlistIndex + 1) % playlist.size
                            else
                                minOf(playlistIndex + 1, playlist.size - 1)
                            playTrackAt(nextIndex)
                        }
                        sendEvent("RemoteNextTrackReceived", statePayload())
                    }

                    override fun onSkipToPrevious() {
                        if (playlist.isNotEmpty()) {
                            playTrackAt(maxOf(0, playlistIndex - 1))
                        }
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

        // ── Playlist Navigation ───────────────────────────────────────────────

        private fun effectiveTrackIndex(logicalIndex: Int): Int =
            if (shuffledOrder.isNotEmpty()) shuffledOrder[logicalIndex] else logicalIndex

        internal fun playTrackAt(index: Int) {
            if (index < 0 || index >= playlist.size) return
            val context: Context = activityRef?.get()?.takeIf { !it.isDestroyed && !it.isFinishing }
                ?: appContext ?: return

            playlistIndex = index
            val track  = playlist[effectiveTrackIndex(index)]
            val url    = track["url"] as? String ?: return
            val title  = track["title"]  as? String
            val artist = track["artist"] as? String
            val album  = track["album"]  as? String
            val artwork = track["artwork"] as? String
            val duration = (track["duration"] as? Number)?.toDouble()
            @Suppress("UNCHECKED_CAST")
            val metadata = track["metadata"] as? Map<String, Any>

            currentUrl = url
            if (title != null) {
                metaTitle         = title
                metaArtist        = artist
                metaAlbum         = album
                metaDurationMs    = duration?.let { (it * 1000).toLong() }
                metaArtworkSource = artwork
                metaMetadata      = metadata
            }

            Handler(Looper.getMainLooper()).post {
                stopProgressTimer()
                try { mediaPlayer?.stop() } catch (_: IllegalStateException) {}
                mediaPlayer?.release()
                mediaPlayer = null

                if (metaTitle != null) applySessionMetadata(context)

                try {
                    mediaPlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        setDataSource(context, Uri.parse(url))

                        setOnPreparedListener { mp ->
                            requestAudioFocus(context)
                            mp.start()
                            updateSessionState()
                            startProgressTimer(DEFAULT_PROGRESS_INTERVAL_MS)
                            AudioService.start(context, metaTitle ?: "Now Playing", metaArtist)

                            val trackChangedPayload = mutableMapOf<String, Any>(
                                "index" to index, "total" to playlist.size, "url" to url
                            )
                            title?.let    { trackChangedPayload["title"]    = it }
                            artist?.let   { trackChangedPayload["artist"]   = it }
                            album?.let    { trackChangedPayload["album"]    = it }
                            duration?.let { trackChangedPayload["duration"] = it }
                            metadata?.let { trackChangedPayload["metadata"] = it }
                            sendEvent("PlaylistTrackChanged", trackChangedPayload)

                            val startedPayload = mutableMapOf<String, Any>("url" to url)
                            title?.let    { startedPayload["title"]    = it }
                            artist?.let   { startedPayload["artist"]   = it }
                            album?.let    { startedPayload["album"]    = it }
                            duration?.let { startedPayload["duration"] = it }
                            metadata?.let { startedPayload["metadata"] = it }
                            sendEvent("PlaybackStarted", startedPayload)
                        }

                        setOnCompletionListener {
                            stopProgressTimer()
                            sendEvent("PlaybackCompleted", mapOf("url" to currentUrl, "duration" to durationSeconds()))
                            advancePlaylist()
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
                    sendEvent("PlaybackFailed", mapOf("url" to url, "error" to (e.message ?: "Unknown error")))
                }
            }
        }

        private fun advancePlaylist() {
            if (playlist.isEmpty()) return
            when (repeatMode) {
                "one" -> playTrackAt(playlistIndex)
                "all" -> playTrackAt((playlistIndex + 1) % playlist.size)
                else  -> {
                    val next = playlistIndex + 1
                    if (next < playlist.size) {
                        playTrackAt(next)
                    } else {
                        sendEvent("PlaylistEnded", mapOf("total" to playlist.size))
                    }
                }
            }
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
            appContext  = activity.applicationContext

            playlist.clear()
            playlistIndex = -1

            val params    = JSONObject(parameters)
            val url       = params.optString("url")
            val title     = params.optString("title").takeIf { it.isNotEmpty() }
            val artist    = params.optString("artist").takeIf { it.isNotEmpty() }
            val album     = params.optString("album").takeIf { it.isNotEmpty() }
            val artwork   = params.optString("artwork").takeIf { it.isNotEmpty() }
            val duration  = if (params.has("duration")) params.optDouble("duration") else null
            val metadata  = params.optJSONObject("metadata")?.let { obj ->
                obj.keys().asSequence().associateWith { key -> obj.get(key) as Any }
            }

            currentUrl = url

            if (title != null) {
                metaTitle         = title
                metaArtist        = artist
                metaAlbum         = album
                metaDurationMs    = duration?.let { (it * 1000).toLong() }
                metaArtworkSource = artwork
                metaMetadata      = metadata
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
                            metadata?.let { payload["metadata"] = it }
                            sendEvent("PlaybackLoaded", payload)
                        }

                        setOnCompletionListener {
                            stopProgressTimer()
                            sendEvent("PlaybackCompleted", mapOf(
                                "url" to currentUrl, "duration" to durationSeconds()
                            ))
                            advancePlaylist()
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
            appContext  = activity.applicationContext

            playlist.clear()
            playlistIndex = -1

            val params    = JSONObject(parameters)
            val url       = params.optString("url")
            val title     = params.optString("title").takeIf { it.isNotEmpty() }
            val artist    = params.optString("artist").takeIf { it.isNotEmpty() }
            val album     = params.optString("album").takeIf { it.isNotEmpty() }
            val artwork   = params.optString("artwork").takeIf { it.isNotEmpty() }
            val duration  = if (params.has("duration")) params.optDouble("duration") else null
            val metadata  = params.optJSONObject("metadata")?.let { obj ->
                obj.keys().asSequence().associateWith { key -> obj.get(key) as Any }
            }

            currentUrl = url

            // Store inline metadata if provided; otherwise preserve metadata from a prior setMetadata call.
            if (title != null) {
                metaTitle         = title
                metaArtist        = artist
                metaAlbum         = album
                metaDurationMs    = duration?.let { (it * 1000).toLong() }
                metaArtworkSource = artwork
                metaMetadata      = metadata
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
                            metadata?.let { payload["metadata"] = it }
                            sendEvent("PlaybackStarted", payload)
                        }

                        setOnCompletionListener {
                            stopProgressTimer()
                            sendEvent("PlaybackCompleted", mapOf(
                                "url" to currentUrl, "duration" to durationSeconds()
                            ))
                            advancePlaylist()
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
            metaMetadata?.let { state["metadata"]  = it }

            return state
        }
    }

    /**
     * Returns all events that were queued while the app was in the background,
     * then clears the queue. Call this when the app returns to the foreground
     * to replay missed events through Livewire.
     *
     * Each entry in the returned list has the shape:
     *   { "event": "EventName", "payload": { ... } }
     */
    class DrainEvents(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val events = pendingEvents.toList()
            pendingEvents.clear()
            return mapOf("success" to true, "events" to events)
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

    /**
     * Sets the playlist queue natively so tracks auto-advance in the background.
     * Each item must have a "url" key; title/artist/album/artwork/duration/metadata are optional.
     * Pass autoPlay: false to load the queue without starting playback immediately.
     * Pass startIndex to begin at a specific track (default: 0).
     */
    class SetPlaylist(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            activityRef = WeakReference(activity)
            appContext  = activity.applicationContext

            val params    = JSONObject(parameters)
            val itemsJson = params.optJSONArray("items")
                ?: return mapOf("success" to false, "error" to "items array is required")

            if (itemsJson.length() == 0) {
                return mapOf("success" to false, "error" to "items array must not be empty")
            }

            playlist.clear()
            for (i in 0 until itemsJson.length()) {
                val item  = itemsJson.getJSONObject(i)
                val track = mutableMapOf<String, Any>()
                item.keys().forEach { key -> track[key] = item.get(key) }
                playlist.add(track)
            }
            playlistIndex = -1

            if (shuffleMode) {
                shuffledOrder.clear()
                shuffledOrder.addAll((0 until playlist.size).toMutableList().also { it.shuffle() })
            } else {
                shuffledOrder.clear()
            }

            val autoPlay   = params.optBoolean("autoPlay", true)
            val startIndex = params.optInt("startIndex", 0)

            sendEvent("PlaylistSet", mapOf("total" to playlist.size))

            if (autoPlay) playTrackAt(startIndex)

            return mapOf("success" to true)
        }
    }

    /**
     * Skips to the next track in the active playlist.
     */
    class NextTrack(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            activityRef = WeakReference(activity)
            if (playlist.isEmpty()) return mapOf("success" to false, "error" to "No playlist is active")
            val nextIndex = if (repeatMode == "all")
                (playlistIndex + 1) % playlist.size
            else
                minOf(playlistIndex + 1, playlist.size - 1)
            playTrackAt(nextIndex)
            return mapOf("success" to true)
        }
    }

    /**
     * Skips to the previous track in the active playlist.
     */
    class PreviousTrack(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            activityRef = WeakReference(activity)
            if (playlist.isEmpty()) return mapOf("success" to false, "error" to "No playlist is active")
            playTrackAt(maxOf(0, playlistIndex - 1))
            return mapOf("success" to true)
        }
    }

    /**
     * Returns the current playlist queue and playback state.
     */
    class GetPlaylist(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return mapOf(
                "success"     to true,
                "items"       to playlist.toList(),
                "index"       to playlistIndex,
                "total"       to playlist.size,
                "repeatMode"  to repeatMode,
                "shuffleMode" to shuffleMode,
            )
        }
    }

    /**
     * Sets the repeat mode: "none" (default), "one" (repeat current), "all" (repeat playlist).
     */
    class SetRepeatMode(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val mode = JSONObject(parameters).optString("mode", "none")
            if (mode !in listOf("none", "one", "all")) {
                return mapOf("success" to false, "error" to "mode must be 'none', 'one', or 'all'")
            }
            repeatMode = mode
            return mapOf("success" to true)
        }
    }

    /**
     * Enables or disables shuffle mode. When enabled, a new random play order is generated.
     */
    class SetShuffleMode(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val shuffle = JSONObject(parameters).optBoolean("shuffle", false)
            shuffleMode = shuffle
            if (shuffle && playlist.isNotEmpty()) {
                shuffledOrder.clear()
                shuffledOrder.addAll((0 until playlist.size).toMutableList().also { it.shuffle() })
            } else {
                shuffledOrder.clear()
            }
            return mapOf("success" to true)
        }
    }
}
