<?php

namespace Narraid\Audio;

class Audio
{
    /**
     * Load an audio file without starting playback.
     * The audio is prepared and ready to play via resume().
     * Fires PlaybackLoaded once ready, PlaybackBuffering while fetching,
     * and PlaybackReady when enough data is buffered.
     *
     * @param  string       $url      URL or local path of the audio file
     * @param  string|null  $title    Track title
     * @param  string|null  $artist   Artist name
     * @param  string|null  $album    Album name
     * @param  string|null  $artwork  URL or local path to artwork image
     * @param  float|null   $duration Total track duration in seconds
     * @param  string|null  $clip     URL or local path to a short audio preview/clip
     * @param  array|null   $metadata Arbitrary key/value pairs passed through to native events
     */
    public function load(
        string $url,
        ?string $title = null,
        ?string $artist = null,
        ?string $album = null,
        ?string $artwork = null,
        ?float $duration = null,
        ?string $clip = null,
        ?array $metadata = null,
    ): bool {
        if (function_exists('nativephp_call')) {
            $params = array_filter([
                'url'      => $url,
                'title'    => $title,
                'artist'   => $artist,
                'album'    => $album,
                'artwork'  => $artwork,
                'duration' => $duration,
                'clip'     => $clip,
                'metadata' => $metadata,
            ], fn ($v) => $v !== null);

            $result = nativephp_call('Audio.load', json_encode($params));

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Play an audio file from a URL or local path.
     * Optionally pass track metadata so PlaybackStarted fires with a complete payload
     * and the lock screen / Bluetooth controls are populated immediately.
     * Fires PlaybackBuffering while fetching and PlaybackReady when buffered.
     *
     * @param  string       $url      URL or local path of the audio file
     * @param  string|null  $title    Track title
     * @param  string|null  $artist   Artist name
     * @param  string|null  $album    Album name
     * @param  string|null  $artwork  URL or local path to artwork image
     * @param  float|null   $duration Total track duration in seconds
     * @param  string|null  $clip     URL or local path to a short audio preview/clip
     * @param  array|null   $metadata Arbitrary key/value pairs passed through to native events
     */
    public function play(
        string $url,
        ?string $title = null,
        ?string $artist = null,
        ?string $album = null,
        ?string $artwork = null,
        ?float $duration = null,
        ?string $clip = null,
        ?array $metadata = null,
    ): bool {
        if (function_exists('nativephp_call')) {
            $params = array_filter([
                'url'      => $url,
                'title'    => $title,
                'artist'   => $artist,
                'album'    => $album,
                'artwork'  => $artwork,
                'duration' => $duration,
                'clip'     => $clip,
                'metadata' => $metadata,
            ], fn ($v) => $v !== null);

            $result = nativephp_call('Audio.play', json_encode($params));

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Pause the current audio playback
     */
    public function pause(): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.pause', '{}');

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Resume the paused audio playback
     */
    public function resume(): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.resume', '{}');

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Stop the audio playback and reset the position
     */
    public function stop(): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.stop', '{}');

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Seek to a specific position in the audio (in seconds)
     *
     * @param  float  $seconds  Target position in seconds (must be >= 0)
     */
    public function seek(float $seconds): bool
    {
        $seconds = max(0.0, $seconds);

        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.seek', json_encode(['seconds' => $seconds]));

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Set the audio volume
     *
     * @param  float  $level  Volume level from 0.0 (mute) to 1.0 (maximum)
     */
    public function setVolume(float $level): bool
    {
        $level = max(0.0, min(1.0, $level));

        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setVolume', json_encode(['level' => $level]));

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Set the playback speed.
     * On Android this requires API 23+; on older versions the rate is stored but not applied.
     *
     * @param  float  $rate  Speed multiplier. 1.0 = normal, 0.5 = half speed, 2.0 = double speed.
     *                       Clamped to the range [0.25, 4.0] by the native layer.
     */
    public function setPlaybackRate(float $rate): bool
    {
        $rate = max(0.25, min(4.0, $rate));

        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setPlaybackRate', json_encode(['rate' => $rate]));

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Set how often PlaybackProgressUpdated events fire.
     *
     * @param  float  $seconds  Interval in seconds. Clamped to [0.5, 60.0] by the native layer.
     */
    public function setProgressInterval(float $seconds): bool
    {
        $seconds = max(0.5, min(60.0, $seconds));

        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setProgressInterval', json_encode(['seconds' => $seconds]));

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Get the duration of the current audio in seconds
     */
    public function getDuration(): ?float
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.getDuration', '{}');

            if ($result) {
                $decoded = json_decode($result, true);

                return isset($decoded['duration']) ? (float) $decoded['duration'] : null;
            }
        }

        return null;
    }

    /**
     * Get the current playback position in seconds
     */
    public function getCurrentPosition(): ?float
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.getCurrentPosition', '{}');

            if ($result) {
                $decoded = json_decode($result, true);

                return isset($decoded['position']) ? (float) $decoded['position'] : null;
            }
        }

        return null;
    }

    /**
     * Get the full current playback state from the native audio layer.
     *
     * Returns an associative array with keys:
     *   url, position, duration, isPlaying, isBuffering, hasPlayer, playbackRate,
     *   hasPlaylist, playlistIndex, playlistTotal, repeatMode, shuffleMode,
     *   title, artist, album, artwork, metadata
     *
     * Returns null when not running inside a NativePHP app or the call fails.
     */
    public function getState(): ?array
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.getState', '{}');

            if ($result) {
                $decoded = json_decode($result, true);

                return is_array($decoded) ? $decoded : null;
            }
        }

        return null;
    }

    /**
     * Drain all events that were queued while the app was in the background.
     *
     * Events are stored natively when PHP cannot safely receive them (background mode).
     * Call this when the app returns to the foreground — typically in a Livewire component's
     * mount() or a dedicated resume hook — to replay everything that was missed.
     *
     * Each item in the returned array has the shape:
     *   ['event' => 'EventName', 'payload' => [...]]
     *
     * @return array[]
     */
    public function drainEvents(): array
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.drainEvents', '{}');

            if ($result) {
                $decoded = json_decode($result, true);

                return $decoded['events'] ?? [];
            }
        }

        return [];
    }

    /**
     * Set track metadata for display on lock screens, Bluetooth devices, and OS media centers.
     *
     * @param  string       $title    Track title
     * @param  string|null  $artist   Artist name
     * @param  string|null  $album    Album name
     * @param  string|null  $artwork  URL or local path to artwork image
     * @param  float|null   $duration Total track duration in seconds
     */
    public function setMetadata(
        string $title,
        ?string $artist = null,
        ?string $album = null,
        ?string $artwork = null,
        ?float $duration = null,
        ?array $metadata = null,
    ): bool {
        if (function_exists('nativephp_call')) {
            $params = array_filter([
                'title'    => $title,
                'artist'   => $artist,
                'album'    => $album,
                'artwork'  => $artwork,
                'duration' => $duration,
                'metadata' => $metadata,
            ], fn ($v) => $v !== null);

            $result = nativephp_call('Audio.setMetadata', json_encode($params));

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Set the playlist queue natively so tracks auto-advance in the background on both iOS and Android.
     *
     * Each item in $items must have a 'url' key. Optional keys per item:
     *   title, artist, album, artwork, duration, clip, metadata
     *
     * @param  array  $items       Array of track objects
     * @param  bool   $autoPlay    Start playing immediately (default true)
     * @param  int    $startIndex  Index of the track to start from (default 0)
     */
    public function setPlaylist(array $items, bool $autoPlay = true, int $startIndex = 0): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setPlaylist', json_encode([
                'items'      => $items,
                'autoPlay'   => $autoPlay,
                'startIndex' => $startIndex,
            ]));

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Skip to the next track in the active playlist
     */
    public function nextTrack(): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.nextTrack', '{}');

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Skip to the previous track in the active playlist
     */
    public function previousTrack(): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.previousTrack', '{}');

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Get the current playlist state.
     *
     * Returns an associative array with keys:
     *   items, index, total, repeatMode, shuffleMode
     */
    public function getPlaylist(): ?array
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.getPlaylist', '{}');

            if ($result) {
                $decoded = json_decode($result, true);

                return is_array($decoded) ? $decoded : null;
            }
        }

        return null;
    }

    /**
     * Set the repeat mode for the active playlist.
     *
     * @param  string  $mode  'none' (default), 'one' (repeat current track), 'all' (repeat playlist)
     */
    public function setRepeatMode(string $mode): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setRepeatMode', json_encode(['mode' => $mode]));

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Enable or disable shuffle mode for the active playlist.
     *
     * @param  bool  $shuffle  true to shuffle, false to play in order
     */
    public function setShuffleMode(bool $shuffle): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setShuffleMode', json_encode(['shuffle' => $shuffle]));

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Schedule playback to stop after a number of minutes.
     * Fires PlaybackStopped and SleepTimerExpired when it triggers.
     *
     * @param  float  $minutes  Minutes until playback stops (must be > 0)
     */
    public function setSleepTimer(float $minutes): bool
    {
        $minutes = max(0.0, $minutes);

        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setSleepTimer', json_encode(['minutes' => $minutes]));

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Cancel an active sleep timer before it fires.
     */
    public function cancelSleepTimer(): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.cancelSleepTimer', '{}');

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Append a track to the end of the active playlist.
     *
     * @param  array  $track  Track object with at least a 'url' key
     */
    public function appendTrack(array $track): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.appendTrack', json_encode(['track' => $track]));

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }

    /**
     * Remove a track from the active playlist by index.
     *
     * @param  int  $index  Zero-based index of the track to remove
     */
    public function removeTrack(int $index): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.removeTrack', json_encode(['index' => $index]));

            if ($result) {
                $decoded = json_decode($result, true);

                return (bool) ($decoded['success'] ?? false);
            }
        }

        return false;
    }
}
