<?php

namespace Narraid\Audio;

class Audio
{
    // ── Internal Helpers ──────────────────────────────────────────────────────

    private function call(string $method, array $params = []): bool
    {
        if (! function_exists('nativephp_call')) return false;
        $result  = nativephp_call($method, empty($params) ? '{}' : json_encode($params));
        $decoded = json_decode($result ?? '{}', true);
        return (bool) ($decoded['success'] ?? false);
    }

    private function query(string $method, array $params = []): ?array
    {
        if (! function_exists('nativephp_call')) return null;
        $result  = nativephp_call($method, empty($params) ? '{}' : json_encode($params));
        $decoded = json_decode($result ?? '', true);
        return is_array($decoded) ? $decoded : null;
    }

    private function trackParams(
        string $url,
        ?string $title,
        ?string $artist,
        ?string $album,
        ?string $artwork,
        ?float $duration,
        ?string $clip,
        ?array $metadata,
    ): array {
        return array_filter([
            'url'      => $url,
            'title'    => $title,
            'artist'   => $artist,
            'album'    => $album,
            'artwork'  => $artwork,
            'duration' => $duration,
            'clip'     => $clip,
            'metadata' => $metadata,
        ], fn ($v) => $v !== null);
    }

    // ── Single-track ──────────────────────────────────────────────────────────

    /**
     * Prepare a track without starting playback.
     * Fires PlaybackLoaded when ready; call resume() to start.
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
        return $this->call('Audio.load', $this->trackParams($url, $title, $artist, $album, $artwork, $duration, $clip, $metadata));
    }

    /**
     * Load and immediately start playing a track.
     * Fires PlaybackStarted once playback begins.
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
        return $this->call('Audio.play', $this->trackParams($url, $title, $artist, $album, $artwork, $duration, $clip, $metadata));
    }

    public function pause():  bool { return $this->call('Audio.pause'); }
    public function resume(): bool { return $this->call('Audio.resume'); }
    public function stop():   bool { return $this->call('Audio.stop'); }

    /** Stop playback and clear the active playlist and all player state. */
    public function reset(): bool { return $this->call('Audio.reset'); }

    /** Seek to an absolute position in seconds (clamped to >= 0). */
    public function seek(float $seconds): bool
    {
        return $this->call('Audio.seek', ['seconds' => max(0.0, $seconds)]);
    }

    /** Seek relative to the current position. Negative values seek backward. */
    public function seekBy(float $seconds): bool
    {
        return $this->call('Audio.seekBy', ['seconds' => $seconds]);
    }

    /** @param float $level Volume from 0.0 (mute) to 1.0 (max). */
    public function setVolume(float $level): bool
    {
        return $this->call('Audio.setVolume', ['level' => max(0.0, min(1.0, $level))]);
    }

    /** @param float $rate Speed multiplier, clamped to [0.25, 4.0]. */
    public function setPlaybackRate(float $rate): bool
    {
        return $this->call('Audio.setPlaybackRate', ['rate' => max(0.25, min(4.0, $rate))]);
    }

    /** @param float $seconds Interval between PlaybackProgressUpdated events, clamped to [0.5, 60]. */
    public function setProgressInterval(float $seconds): bool
    {
        return $this->call('Audio.setProgressInterval', ['seconds' => max(0.5, min(60.0, $seconds))]);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public function getDuration(): ?float
    {
        $r = $this->query('Audio.getDuration');
        return isset($r['duration']) ? (float) $r['duration'] : null;
    }

    public function getCurrentPosition(): ?float
    {
        $r = $this->query('Audio.getCurrentPosition');
        return isset($r['position']) ? (float) $r['position'] : null;
    }

    /**
     * Get position, duration, and buffered seconds in one call.
     * Returns ['position' => float, 'duration' => float, 'buffered' => float] or null.
     */
    public function getProgress(): ?array
    {
        return $this->query('Audio.getProgress');
    }

    /**
     * Get the full playback state: track, position, duration, buffered,
     * isPlaying, isBuffering, hasPlayer, playbackRate, playlist info, and modes.
     */
    public function getState(): ?array
    {
        return $this->query('Audio.getState');
    }

    /**
     * Drain events queued while the app was in the background.
     * Each item has shape ['event' => string, 'payload' => array].
     *
     * @return array[]
     */
    public function drainEvents(): array
    {
        return $this->query('Audio.drainEvents')['events'] ?? [];
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    /** Update lock screen / Bluetooth metadata without restarting playback. */
    public function setMetadata(
        string $title,
        ?string $artist = null,
        ?string $album = null,
        ?string $artwork = null,
        ?float $duration = null,
        ?array $metadata = null,
    ): bool {
        return $this->call('Audio.setMetadata', array_filter([
            'title'    => $title,
            'artist'   => $artist,
            'album'    => $album,
            'artwork'  => $artwork,
            'duration' => $duration,
            'metadata' => $metadata,
        ], fn ($v) => $v !== null));
    }

    // ── Playlist ──────────────────────────────────────────────────────────────

    /**
     * Replace the queue and optionally start playing.
     * Each item in $items must have a 'url' key.
     */
    public function setPlaylist(
        array $items,
        bool $autoPlay = true,
        int $startIndex = 0,
        float $startSeconds = 0.0,
    ): bool {
        return $this->call('Audio.setPlaylist', compact('items', 'autoPlay', 'startIndex', 'startSeconds'));
    }

    public function nextTrack(float $startSeconds = 0.0): bool
    {
        return $this->call('Audio.nextTrack', ['startSeconds' => $startSeconds]);
    }

    public function previousTrack(float $startSeconds = 0.0): bool
    {
        return $this->call('Audio.previousTrack', ['startSeconds' => $startSeconds]);
    }

    public function skipTrack(int $index, float $startSeconds = 0.0): bool
    {
        return $this->call('Audio.skipTrack', compact('index', 'startSeconds'));
    }

    /** Move a track from one queue position to another. */
    public function moveTrack(int $fromIndex, int $toIndex): bool
    {
        return $this->call('Audio.moveTrack', compact('fromIndex', 'toIndex'));
    }

    /** Append a single track to the end of the active queue. Track must have a 'url' key. */
    public function appendTrack(array $track): bool
    {
        return $this->call('Audio.appendTrack', compact('track'));
    }

    public function removeTrack(int $index): bool
    {
        return $this->call('Audio.removeTrack', compact('index'));
    }

    /** Remove all tracks after the currently playing track. */
    public function removeUpcomingTracks(): bool
    {
        return $this->call('Audio.removeUpcomingTracks');
    }

    public function getTrack(int $index): ?array
    {
        $r = $this->query('Audio.getTrack', compact('index'));
        return isset($r['track']) && is_array($r['track']) ? $r['track'] : null;
    }

    public function getActiveTrack(): ?array
    {
        $r = $this->query('Audio.getActiveTrack');
        return isset($r['track']) && is_array($r['track']) ? $r['track'] : null;
    }

    public function getActiveTrackIndex(): ?int
    {
        $r = $this->query('Audio.getActiveTrackIndex');
        return isset($r['index']) ? (int) $r['index'] : null;
    }

    public function getPlaylist(): ?array
    {
        return $this->query('Audio.getPlaylist');
    }

    // ── Queue Settings ────────────────────────────────────────────────────────

    /** @param string $mode 'none' | 'one' | 'all' */
    public function setRepeatMode(string $mode): bool
    {
        return $this->call('Audio.setRepeatMode', compact('mode'));
    }

    public function setShuffleMode(bool $shuffle): bool
    {
        return $this->call('Audio.setShuffleMode', compact('shuffle'));
    }

    // ── Sleep Timer ───────────────────────────────────────────────────────────

    /**
     * Schedule playback to stop after $minutes minutes (must be > 0).
     * Fires PlaybackStopped then SleepTimerExpired when triggered.
     */
    public function setSleepTimer(float $minutes): bool
    {
        return $this->call('Audio.setSleepTimer', ['minutes' => max(0.0, $minutes)]);
    }

    public function cancelSleepTimer(): bool
    {
        return $this->call('Audio.cancelSleepTimer');
    }
}
