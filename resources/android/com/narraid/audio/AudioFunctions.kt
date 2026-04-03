package com.narraid.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
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

        // ── Application Context (fallback for background playback) ────────────
        private var appContext: Context? = null

        // ── Stored Metadata ───────────────────────────────────────────────────
        private var metaTitle: String? = null
        private var metaArtist: String? = null
        private var metaAlbum: String? = null
        private var metaDurationMs: Long? = null
        private var metaArtworkSource: String? = null
        private var metaClip: String? = null
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

        // ── Playback Settings ─────────────────────────────────────────────────
        private var playbackRate: Float = 1.0f
        private var preferredProgressIntervalMs: Long = DEFAULT_PROGRESS_INTERVAL_MS

        // ── Progress Timer ────────────────────────────────────────────────────
        private const val DEFAULT_PROGRESS_INTERVAL_MS: Long = 10_000
        private var progressHandler: Handler? = null
        private var progressRunnable: Runnable? = null

        // ── Playlist State ────────────────────────────────────────────────────
        private val playlist: MutableList<Map<String, Any>> = mutableListOf()
        private var playlistIndex: Int = -1
        private var repeatMode: String = "none"
        private var shuffleMode: Boolean = false
        private val shuffledOrder: MutableList<Int> = mutableListOf()
        private var isBuffering: Boolean = false

        // ── Sleep Timer ───────────────────────────────────────────────────────
        private var sleepTimerHandler: Handler? = null
        private var sleepTimerRunnable: Runnable? = null

        // ── Lifecycle ─────────────────────────────────────────────────────────

        init {
            NativePHPLifecycle.on(NativePHPLifecycle.Events.ON_RESUME) { _ ->
                isInBackground = false
                MainActivity.instance?.let { activityRef = WeakReference(it) }
                if (mediaPlayer?.isPlaying == true) {
                    startProgressTimer(preferredProgressIntervalMs)
                }

                sendEvent("PlayerForegrounded")
            }
            NativePHPLifecycle.on(NativePHPLifecycle.Events.ON_PAUSE) { _ ->
                isInBackground = true

                sendEvent("PlayerBackgrounded")
                // Progress timer intentionally kept running in background so the service
                // notification stays current. Events are queued, not dispatched.
            }
        }

        // ── Event Helpers ─────────────────────────────────────────────────────

        private const val EVENT_PREFIX = "Narraid\\Audio\\Events\\"

        internal fun sendEvent(name: String, payload: Map<String, Any>) {
            if (isInBackground) {
                pendingEvents.add(mapOf("event" to name, "payload" to payload))
                return
            }
            // Capture a strong reference now; re-validate inside the Handler post.
            val activity = activityRef?.get()?.takeIf { !it.isDestroyed && !it.isFinishing }
                ?: return
            val json = JSONObject(payload).toString()
            Handler(Looper.getMainLooper()).post {
                val act = activityRef?.get()?.takeIf { !it.isDestroyed && !it.isFinishing }
                    ?: return@post
                NativeActionCoordinator.dispatchEvent(act, EVENT_PREFIX + name, json)
            }
        }

        private fun statePayload(): Map<String, Any> {
            val m = mutableMapOf<String, Any>(
                "position"    to positionSeconds(),
                "duration"    to durationSeconds(),
                "url"         to currentUrl,
                "isPlaying"   to (mediaPlayer?.isPlaying == true),
                "isBuffering" to isBuffering,
            )
            metaMetadata?.let { m["metadata"] = it }
            return m
        }

        // ── Position / Duration ───────────────────────────────────────────────

        private fun positionSeconds(): Double = try {
            (mediaPlayer?.currentPosition ?: 0) / 1000.0
        } catch (_: IllegalStateException) { 0.0 }

        private fun durationSeconds(): Double = try {
            val ms = mediaPlayer?.duration ?: 0
            if (ms < 0) 0.0 else ms / 1000.0
        } catch (_: IllegalStateException) { 0.0 }

        // ── Session Metadata ──────────────────────────────────────────────────

        private fun buildSessionMetadata(): MediaMetadataCompat {
            val builder = MediaMetadataCompat.Builder()
            metaTitle?.let     { builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, it) }
            metaArtist?.let    { builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
            metaAlbum?.let     { builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it) }
            metaDurationMs?.let { builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it) }
            currentArtwork?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            return builder.build()
        }

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
                        applyPlaybackRate()
                        updateSessionState()
                        startProgressTimer(preferredProgressIntervalMs)
                        activityRef?.get()?.let { AudioService.refreshPlayState(it) }
                        val payload = statePayload()
                        sendEvent("PlaybackResumed",    payload)
                        sendEvent("RemotePlayReceived", payload)
                    }

                    override fun onPause() {
                        mediaPlayer?.pause()
                        stopProgressTimer()
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
                        ) + metaPayload())
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
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                stopProgressTimer()
                updateSessionState()
                val payload = statePayload()
                sendEvent("PlaybackPaused",      payload)
                sendEvent("RemotePauseReceived", payload)
            } else {
                mediaPlayer?.start()
                applyPlaybackRate()
                updateSessionState()
                startProgressTimer(preferredProgressIntervalMs)
                val payload = statePayload()
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
                    if (playing) playbackRate else 0.0f
                )
                .build()
            session.setPlaybackState(state)
        }

        // ── Playback Rate ─────────────────────────────────────────────────────

        private fun applyPlaybackRate() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && playbackRate != 1.0f) {
                try {
                    mediaPlayer?.playbackParams = PlaybackParams().setSpeed(playbackRate)
                } catch (_: Exception) { /* ignore — not all streams support rate changes */ }
            }
        }

        // ── Audio Focus ───────────────────────────────────────────────────────

        private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    pausedByFocusLoss = false
                    if (mediaPlayer?.isPlaying == true) {
                        mediaPlayer?.pause()
                        stopProgressTimer()
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
                        stopProgressTimer()
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
                        applyPlaybackRate()
                        updateSessionState()
                        startProgressTimer(preferredProgressIntervalMs)
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
            stopProgressTimer()
            if (intervalMs <= 0) return
            val handler = Handler(Looper.getMainLooper())
            progressHandler = handler
            val runnable = object : Runnable {
                override fun run() {
                    if (progressRunnable !== this) return
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
            progressHandler  = null
            progressRunnable = null
        }

        // ── Playlist Navigation ───────────────────────────────────────────────

        private fun effectiveTrackIndex(logicalIndex: Int): Int =
            if (shuffledOrder.isNotEmpty()) shuffledOrder.getOrNull(logicalIndex) ?: logicalIndex else logicalIndex

        internal fun playTrackAt(index: Int) {
            if (index < 0 || index >= playlist.size) return

            // Resolve a live context — prefer the current activity, fall back to appContext.
            val context: Context = activityRef?.get()?.takeIf { !it.isDestroyed && !it.isFinishing }
                ?: appContext ?: return

            playlistIndex = index
            val track    = playlist[effectiveTrackIndex(index)]
            val url      = track["url"] as? String ?: return
            val title    = track["title"]   as? String
            val artist   = track["artist"]  as? String
            val album    = track["album"]   as? String
            val artwork  = track["artwork"] as? String
            val duration = (track["duration"] as? Number)?.toDouble()
            val clip     = track["clip"]    as? String
            @Suppress("UNCHECKED_CAST")
            val metadata = track["metadata"] as? Map<String, Any>

            currentUrl = url
            if (title != null) {
                metaTitle         = title
                metaArtist        = artist
                metaAlbum         = album
                metaDurationMs    = duration?.let { (it * 1000).toLong() }
                metaArtworkSource = artwork
                metaClip          = clip
                metaMetadata      = metadata
            }

            Handler(Looper.getMainLooper()).post {
                // Re-validate context inside the post — activity may have been destroyed.
                val ctx = activityRef?.get()?.takeIf { !it.isDestroyed && !it.isFinishing }
                    ?: appContext ?: return@post

                stopProgressTimer()
                try { mediaPlayer?.stop() } catch (_: IllegalStateException) {}
                mediaPlayer?.release()
                mediaPlayer = null

                if (metaTitle != null) applySessionMetadata(ctx)

                try {
                    mediaPlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        setDataSource(ctx, Uri.parse(url))

                        setOnPreparedListener { mp ->
                            mp.setVolume(userVolume, userVolume)
                            applyPlaybackRate()
                            requestAudioFocus(ctx)
                            mp.start()
                            updateSessionState()
                            startProgressTimer(preferredProgressIntervalMs)
                            AudioService.start(ctx, metaTitle ?: "Now Playing", metaArtist)

                            val trackChangedPayload = mutableMapOf<String, Any>(
                                "index" to index, "total" to playlist.size, "url" to url
                            )
                            title?.let    { trackChangedPayload["title"]    = it }
                            artist?.let   { trackChangedPayload["artist"]   = it }
                            album?.let    { trackChangedPayload["album"]    = it }
                            duration?.let { trackChangedPayload["duration"] = it }
                            artwork?.let  { trackChangedPayload["artwork"]  = it }
                            clip?.let     { trackChangedPayload["clip"]     = it }
                            metadata?.let { trackChangedPayload["metadata"] = it }
                            sendEvent("PlaylistTrackChanged", trackChangedPayload)

                            val startedPayload = mutableMapOf<String, Any>("url" to url)
                            title?.let    { startedPayload["title"]    = it }
                            artist?.let   { startedPayload["artist"]   = it }
                            album?.let    { startedPayload["album"]    = it }
                            duration?.let { startedPayload["duration"] = it }
                            artwork?.let  { startedPayload["artwork"]  = it }
                            clip?.let     { startedPayload["clip"]     = it }
                            metadata?.let { startedPayload["metadata"] = it }
                            sendEvent("PlaybackStarted", startedPayload)
                        }

                        attachCommonListeners(this)
                        prepareAsync()
                    }
                } catch (e: Exception) {
                    sendEvent("PlaybackFailed", mapOf("url" to url, "error" to (e.message ?: "Unknown error")) + metaPayload())
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

        private fun startSleepTimer(minutes: Double) {
            cancelSleepTimer()
            val handler = Handler(Looper.getMainLooper())
            val runnable = Runnable {
                if (mediaPlayer != null) {
                    val payload = statePayload()
                    releasePlayer()
                    sendEvent("PlaybackStopped",   payload)
                    sendEvent("SleepTimerExpired", emptyMap())
                }
            }
            sleepTimerHandler  = handler
            sleepTimerRunnable = runnable
            handler.postDelayed(runnable, (minutes * 60 * 1000).toLong())
        }

        private fun cancelSleepTimer() {
            sleepTimerRunnable?.let { sleepTimerHandler?.removeCallbacks(it) }
            sleepTimerHandler  = null
            sleepTimerRunnable = null
        }

        private fun metaPayload(): Map<String, Any> =
            metaMetadata?.let { mapOf("metadata" to it) } ?: emptyMap()

        private fun attachCommonListeners(mp: MediaPlayer) {
            mp.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                        isBuffering = true
                        sendEvent("PlaybackBuffering", mapOf("url" to currentUrl, "position" to positionSeconds()) + metaPayload())
                    }
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                        isBuffering = false
                        sendEvent("PlaybackReady", mapOf("url" to currentUrl, "duration" to durationSeconds()) + metaPayload())
                    }
                }
                false
            }
            mp.setOnCompletionListener {
                isBuffering = false
                stopProgressTimer()
                sendEvent("PlaybackCompleted", mapOf("url" to currentUrl, "duration" to durationSeconds()) + metaPayload())
                advancePlaylist()
            }
            mp.setOnErrorListener { _, what, extra ->
                isBuffering = false
                stopProgressTimer()
                sendEvent("PlaybackFailed", mapOf(
                    "url" to currentUrl, "error" to "MediaPlayer error: what=$what extra=$extra"
                ) + metaPayload())
                false
            }
        }

        private fun releasePlayer() {
            isBuffering = false
            cancelSleepTimer()
            stopProgressTimer()
            try { mediaPlayer?.stop() } catch (_: IllegalStateException) {}
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

            val params   = JSONObject(parameters)
            val url      = params.optString("url")
            val title    = params.optString("title").takeIf { it.isNotEmpty() }
            val artist   = params.optString("artist").takeIf { it.isNotEmpty() }
            val album    = params.optString("album").takeIf { it.isNotEmpty() }
            val artwork  = params.optString("artwork").takeIf { it.isNotEmpty() }
            val duration = if (params.has("duration")) params.optDouble("duration") else null
            val clip     = params.optString("clip").takeIf { it.isNotEmpty() }
            val metadata = params.optJSONObject("metadata")?.let { obj ->
                obj.keys().asSequence().associateWith { key -> obj.get(key) as Any }
            }

            currentUrl = url

            if (title != null) {
                metaTitle         = title
                metaArtist        = artist
                metaAlbum         = album
                metaDurationMs    = duration?.let { (it * 1000).toLong() }
                metaArtworkSource = artwork
                metaClip          = clip
                metaMetadata      = metadata
            }

            Handler(Looper.getMainLooper()).post {
                stopProgressTimer()
                try { mediaPlayer?.stop() } catch (_: IllegalStateException) {}
                mediaPlayer?.release()
                mediaPlayer = null

                if (metaTitle != null) applySessionMetadata(activity)

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
                            updateSessionState()
                            val payload = mutableMapOf<String, Any>("url" to url)
                            title?.let    { payload["title"]    = it }
                            artist?.let   { payload["artist"]   = it }
                            album?.let    { payload["album"]    = it }
                            duration?.let { payload["duration"] = it }
                            artwork?.let  { payload["artwork"]  = it }
                            clip?.let     { payload["clip"]     = it }
                            metadata?.let { payload["metadata"] = it }
                            sendEvent("PlaybackLoaded", payload)
                        }
                        attachCommonListeners(this)
                        prepareAsync()
                    }
                } catch (e: Exception) {
                    sendEvent("PlaybackFailed", mapOf("url" to url, "error" to (e.message ?: "Unknown error")) + metaPayload())
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

            val params   = JSONObject(parameters)
            val url      = params.optString("url")
            val title    = params.optString("title").takeIf { it.isNotEmpty() }
            val artist   = params.optString("artist").takeIf { it.isNotEmpty() }
            val album    = params.optString("album").takeIf { it.isNotEmpty() }
            val artwork  = params.optString("artwork").takeIf { it.isNotEmpty() }
            val duration = if (params.has("duration")) params.optDouble("duration") else null
            val clip     = params.optString("clip").takeIf { it.isNotEmpty() }
            val metadata = params.optJSONObject("metadata")?.let { obj ->
                obj.keys().asSequence().associateWith { key -> obj.get(key) as Any }
            }

            currentUrl = url

            if (title != null) {
                metaTitle         = title
                metaArtist        = artist
                metaAlbum         = album
                metaDurationMs    = duration?.let { (it * 1000).toLong() }
                metaArtworkSource = artwork
                metaClip          = clip
                metaMetadata      = metadata
            }

            Handler(Looper.getMainLooper()).post {
                stopProgressTimer()
                try { mediaPlayer?.stop() } catch (_: IllegalStateException) {}
                mediaPlayer?.release()
                mediaPlayer = null

                if (metaTitle != null) applySessionMetadata(activity)

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
                            mp.setVolume(userVolume, userVolume)
                            applyPlaybackRate()
                            requestAudioFocus(activity)
                            mp.start()
                            updateSessionState()
                            startProgressTimer(preferredProgressIntervalMs)
                            AudioService.start(activity, metaTitle ?: "Now Playing", metaArtist)

                            val payload = mutableMapOf<String, Any>("url" to url)
                            title?.let    { payload["title"]    = it }
                            artist?.let   { payload["artist"]   = it }
                            album?.let    { payload["album"]    = it }
                            duration?.let { payload["duration"] = it }
                            artwork?.let  { payload["artwork"]  = it }
                            clip?.let     { payload["clip"]     = it }
                            metadata?.let { payload["metadata"] = it }
                            sendEvent("PlaybackStarted", payload)
                        }
                        attachCommonListeners(this)
                        prepareAsync()
                    }
                } catch (e: Exception) {
                    sendEvent("PlaybackFailed", mapOf("url" to url, "error" to (e.message ?: "Unknown error")) + metaPayload())
                }
            }

            return mapOf("success" to true)
        }
    }

    class Pause(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            mediaPlayer?.pause()
            stopProgressTimer()
            updateSessionState()
            AudioService.refreshPlayState(context)
            sendEvent("PlaybackPaused", statePayload())
            return mapOf("success" to true)
        }
    }

    class Resume(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            requestAudioFocus(context)
            mediaPlayer?.start()
            applyPlaybackRate()
            updateSessionState()
            startProgressTimer(preferredProgressIntervalMs)
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
            val seconds  = maxOf(0.0, params.optDouble("seconds", 0.0))
            val from     = positionSeconds()
            val duration = durationSeconds()
            mediaPlayer?.seekTo((seconds * 1000).toInt())
            updateSessionState()
            startProgressTimer(preferredProgressIntervalMs)
            sendEvent("PlaybackSeeked", mapOf(
                "from" to from, "to" to seconds, "duration" to duration, "url" to currentUrl
            ) + metaPayload())
            return mapOf("success" to true)
        }
    }

    class SetVolume(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val level = JSONObject(parameters).optDouble("level", 1.0)
                .coerceIn(0.0, 1.0).toFloat()
            userVolume = level
            mediaPlayer?.setVolume(level, level)
            return mapOf("success" to true)
        }
    }

    class SetPlaybackRate(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val rate = JSONObject(parameters).optDouble("rate", 1.0)
                .coerceIn(0.25, 4.0).toFloat()
            playbackRate = rate
            applyPlaybackRate()
            updateSessionState()
            return mapOf("success" to true, "rate" to rate)
        }
    }

    class SetProgressInterval(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val seconds = JSONObject(parameters).optDouble("seconds", 10.0).coerceIn(0.5, 60.0)
            val ms = (seconds * 1000).toLong()
            preferredProgressIntervalMs = ms
            if (mediaPlayer?.isPlaying == true) startProgressTimer(ms)
            return mapOf("success" to true, "seconds" to seconds)
        }
    }

    class GetDuration(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> =
            mapOf("duration" to durationSeconds())
    }

    class GetCurrentPosition(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> =
            mapOf("position" to positionSeconds())
    }

    class GetState(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val state = mutableMapOf<String, Any>(
                "url"           to currentUrl,
                "position"      to positionSeconds(),
                "duration"      to durationSeconds(),
                "isPlaying"     to (mediaPlayer?.isPlaying == true),
                "isBuffering"   to isBuffering,
                "hasPlayer"     to (mediaPlayer != null),
                "playbackRate"  to playbackRate,
                "hasPlaylist"   to playlist.isNotEmpty(),
                "playlistIndex" to playlistIndex,
                "playlistTotal" to playlist.size,
                "repeatMode"    to repeatMode,
                "shuffleMode"   to shuffleMode,
            )
            metaTitle?.let         { state["title"]    = it }
            metaArtist?.let        { state["artist"]   = it }
            metaAlbum?.let         { state["album"]    = it }
            metaDurationMs?.let    { state["duration"] = it / 1000.0 }
            metaArtworkSource?.let { state["artwork"]  = it }
            metaMetadata?.let      { state["metadata"] = it }
            return state
        }
    }

    class DrainEvents(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val events = pendingEvents.toList()
            pendingEvents.clear()
            return mapOf("success" to true, "events" to events)
        }
    }

    class SetMetadata(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val params = JSONObject(parameters)
            val title  = params.optString("title").takeIf { it.isNotEmpty() }
                ?: return mapOf("success" to false, "error" to "title is required")

            metaTitle         = title
            metaArtist        = params.optString("artist").takeIf { it.isNotEmpty() }
            metaAlbum         = params.optString("album").takeIf { it.isNotEmpty() }
            metaDurationMs    = if (params.has("duration")) (params.optDouble("duration", 0.0) * 1000).toLong() else null
            metaArtworkSource = params.optString("artwork").takeIf { it.isNotEmpty() }

            applySessionMetadata(context)
            if (mediaPlayer != null) {
                updateSessionState()
                AudioService.updateNotification(context, title, metaArtist)
            }

            return mapOf("success" to true)
        }
    }

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

    class PreviousTrack(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            activityRef = WeakReference(activity)
            if (playlist.isEmpty()) return mapOf("success" to false, "error" to "No playlist is active")
            playTrackAt(maxOf(0, playlistIndex - 1))
            return mapOf("success" to true)
        }
    }

    class GetPlaylist(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> = mapOf(
            "success"     to true,
            "items"       to playlist.toList(),
            "index"       to playlistIndex,
            "total"       to playlist.size,
            "repeatMode"  to repeatMode,
            "shuffleMode" to shuffleMode,
        )
    }

    class SetRepeatMode(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val mode = JSONObject(parameters).optString("mode", "none")
            if (mode !in listOf("none", "one", "all")) {
                return mapOf("success" to false, "error" to "mode must be 'none', 'one', or 'all'")
            }
            repeatMode = mode
            sendEvent("PlaylistRepeatModeChanged", mapOf("mode" to mode))
            return mapOf("success" to true)
        }
    }

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
            sendEvent("PlaylistShuffleChanged", mapOf("shuffle" to shuffle))
            return mapOf("success" to true)
        }
    }

    class SetSleepTimer(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val minutes = JSONObject(parameters).optDouble("minutes", 0.0)
            if (minutes <= 0) return mapOf("success" to false, "error" to "minutes must be a positive number")
            startSleepTimer(minutes)
            return mapOf("success" to true, "minutes" to minutes)
        }
    }

    class CancelSleepTimer(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            cancelSleepTimer()
            return mapOf("success" to true)
        }
    }

    class AppendTrack(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val params = JSONObject(parameters)
            val trackJson = params.optJSONObject("track")
                ?: return mapOf("success" to false, "error" to "track is required")
            if (!trackJson.has("url")) return mapOf("success" to false, "error" to "track.url is required")
            val track = mutableMapOf<String, Any>()
            trackJson.keys().forEach { key -> track[key] = trackJson.get(key) }
            playlist.add(track)
            if (shuffleMode) shuffledOrder.add(playlist.size - 1)
            return mapOf("success" to true, "total" to playlist.size)
        }
    }

    class RemoveTrack(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val index = JSONObject(parameters).optInt("index", -1)
            if (index < 0 || index >= playlist.size) {
                return mapOf("success" to false, "error" to "index is out of range")
            }
            playlist.removeAt(index)
            if (shuffleMode) {
                shuffledOrder.removeAll { it == index }
                val iter = shuffledOrder.listIterator()
                while (iter.hasNext()) {
                    val v = iter.next()
                    if (v > index) iter.set(v - 1)
                }
            }
            when {
                playlistIndex > index  -> playlistIndex--
                playlistIndex == index -> playlistIndex = -1
            }
            return mapOf("success" to true, "total" to playlist.size)
        }
    }
}
