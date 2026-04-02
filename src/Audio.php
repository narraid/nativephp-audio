<?php

namespace Narraid\Audio;

class Audio
{
    /**
     * Load an audio file without starting playback.
     * The audio is prepared and ready to play via resume().
     * Fires a PlaybackLoaded event once the audio is ready.
     *
     * @param  string       $url      URL or local path of the audio file
     * @param  string|null  $title    Track title
     * @param  string|null  $artist   Artist name
     * @param  string|null  $album    Album name
     * @param  string|null  $artwork  URL or local path to artwork image
     * @param  float|null   $duration Total track duration in seconds
     * @param  array|null   $metadata Arbitrary key/value pairs passed through to native events
     */
    public function load(
        string $url,
        ?string $title = null,
        ?string $artist = null,
        ?string $album = null,
        ?string $artwork = null,
        ?float $duration = null,
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
                'metadata' => $metadata,
            ], fn ($v) => $v !== null);

            $result = nativephp_call('Audio.load', json_encode($params));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }

    /**
     * Play an audio file from a URL or local path.
     * Optionally pass track metadata so PlaybackStarted fires with a complete payload
     * and the lock screen / Bluetooth controls are populated immediately.
     *
     * @param  string       $url      URL or local path of the audio file
     * @param  string|null  $title    Track title
     * @param  string|null  $artist   Artist name
     * @param  string|null  $album    Album name
     * @param  string|null  $artwork  URL or local path to artwork image
     * @param  float|null   $duration Total track duration in seconds
     * @param  array|null   $metadata Arbitrary key/value pairs passed through to native events
     */
    public function play(
        string $url,
        ?string $title = null,
        ?string $artist = null,
        ?string $album = null,
        ?string $artwork = null,
        ?float $duration = null,
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
                'metadata' => $metadata,
            ], fn ($v) => $v !== null);

            $result = nativephp_call('Audio.play', json_encode($params));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
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
                $decoded = json_decode($result);

                return $decoded->success ?? false;
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
                $decoded = json_decode($result);

                return $decoded->success ?? false;
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
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }

    /**
     * Seek to a specific position in the audio (in seconds)
     */
    public function seek(float $seconds): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.seek', json_encode(['seconds' => $seconds]));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
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
                $decoded = json_decode($result);

                return $decoded->success ?? false;
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
                $decoded = json_decode($result);

                return isset($decoded->duration) ? (float) $decoded->duration : null;
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
                $decoded = json_decode($result);

                return isset($decoded->position) ? (float) $decoded->position : null;
            }
        }

        return null;
    }

    /**
     * Get the full current playback state from the native audio layer.
     *
     * Returns an associative array with keys:
     *   url, position, duration, isPlaying, hasPlayer, title, artist, album, artwork
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
    ): bool {
        if (function_exists('nativephp_call')) {
            $params = array_filter([
                'title' => $title,
                'artist' => $artist,
                'album' => $album,
                'artwork' => $artwork,
                'duration' => $duration,
            ], fn ($v) => $v !== null);

            $result = nativephp_call('Audio.setMetadata', json_encode($params));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }
}
