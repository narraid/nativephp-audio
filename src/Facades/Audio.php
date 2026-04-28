<?php

namespace Narraid\Audio\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static bool        load(string $url, ?string $title = null, ?string $artist = null, ?string $album = null, ?string $artwork = null, ?float $duration = null, ?string $clip = null, ?array $metadata = null)
 * @method static bool        play(string $url, ?string $title = null, ?string $artist = null, ?string $album = null, ?string $artwork = null, ?float $duration = null, ?string $clip = null, ?array $metadata = null)
 * @method static bool        pause()
 * @method static bool        resume()
 * @method static bool        stop()
 * @method static bool        reset()
 * @method static bool        seek(float $seconds)
 * @method static bool        seekBy(float $seconds)
 * @method static bool        setVolume(float $level)
 * @method static bool        setPlaybackRate(float $rate)
 * @method static bool        setProgressInterval(float $seconds)
 * @method static float|null  getDuration()
 * @method static float|null  getCurrentPosition()
 * @method static array|null  getProgress()
 * @method static array|null  getState()
 * @method static array       drainEvents()
 * @method static bool        setMetadata(string $title, ?string $artist = null, ?string $album = null, ?string $artwork = null, ?float $duration = null, ?array $metadata = null)
 * @method static bool        setPlaylist(array $items, bool $autoPlay = true, int $startIndex = 0, float $startSeconds = 0.0)
 * @method static bool        nextTrack(float $startSeconds = 0.0)
 * @method static bool        previousTrack(float $startSeconds = 0.0)
 * @method static bool        skipTrack(int $index, float $startSeconds = 0.0)
 * @method static bool        moveTrack(int $fromIndex, int $toIndex)
 * @method static bool        appendTrack(array $track)
 * @method static bool        removeTrack(int $index)
 * @method static bool        removeUpcomingTracks()
 * @method static array|null  getTrack(int $index)
 * @method static array|null  getActiveTrack()
 * @method static int|null    getActiveTrackIndex()
 * @method static array|null  getPlaylist()
 * @method static bool        setRepeatMode(string $mode)
 * @method static bool        setShuffleMode(bool $shuffle)
 * @method static bool        setSleepTimer(float $minutes)
 * @method static bool        cancelSleepTimer()
 *
 * @see \Narraid\Audio\Audio
 */
class Audio extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Narraid\Audio\Audio::class;
    }
}
