package com.theunwindfront.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.nativephp.mobile.bridge.BridgeFunction
import org.json.JSONObject
import java.net.URL

class AudioFunctions {
    companion object {
        private var mediaPlayer: MediaPlayer? = null
        private var mediaSession: MediaSessionCompat? = null

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
    }

    class Play(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val params = JSONObject(parameters)
            val url = params.optString("url")
            val result = JSONObject()

            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()

                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(context, Uri.parse(url))
                    prepare()
                    start()
                }
                // Start the foreground service so playback survives backgrounding
                AudioService.start(context)
                result.put("success", true)
            } catch (e: Exception) {
                result.put("success", false)
                result.put("error", e.message)
            }

            return result.toMap()
        }
    }

    class Pause(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            mediaPlayer?.pause()
            return mapOf("success" to true)
        }
    }

    class Resume(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            mediaPlayer?.start()
            return mapOf("success" to true)
        }
    }

    class Stop(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            AudioService.stop(context)
            return mapOf("success" to true)
        }
    }

    class Seek(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val params = JSONObject(parameters)
            val seconds = params.optDouble("seconds", 0.0)
            mediaPlayer?.seekTo((seconds * 1000).toInt())
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
                    bitmap?.let { metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
                } catch (_: Exception) { /* artwork is optional — ignore fetch failures */ }
            }

            session.setMetadata(metaBuilder.build())

            val stateBuilder = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    if (mediaPlayer?.isPlaying == true) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED,
                    (mediaPlayer?.currentPosition ?: 0).toLong(),
                    1.0f
                )
            session.setPlaybackState(stateBuilder.build())

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
