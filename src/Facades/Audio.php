<?php

namespace Narraid\Audio\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static bool        load(string $url, ?string $title = null, ?string $artist = null, ?string $album = null, ?string $artwork = null, ?float $duration = null, ?array $metadata = null)
 * @method static bool        play(string $url, ?string $title = null, ?string $artist = null, ?string $album = null, ?string $artwork = null, ?float $duration = null, ?array $metadata = null)
 * @method static bool        pause()
 * @method static bool        resume()
 * @method static bool        stop()
 * @method static bool        seek(float $seconds)
 * @method static bool        setVolume(float $level)
 * @method static bool        setPlaybackRate(float $rate)
 * @method static bool        setProgressInterval(float $seconds)
 * @method static float|null  getDuration()
 * @method static float|null  getCurrentPosition()
 * @method static array|null  getState()
 * @method static array       drainEvents()
 * @method static bool        setMetadata(string $title, ?string $artist = null, ?string $album = null, ?string $artwork = null, ?float $duration = null)
 * @method static bool        setPlaylist(array $items, bool $autoPlay = true, int $startIndex = 0)
 * @method static bool        nextTrack()
 * @method static bool        previousTrack()
 * @method static array|null  getPlaylist()
 * @method static bool        setRepeatMode(string $mode)
 * @method static bool        setShuffleMode(bool $shuffle)
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
