<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

/**
 * Fired every Audio::setHeartbeatInterval() seconds while playing. Unlike
 * PlaybackProgressUpdated, heartbeats are never merged in the background queue.
 */
class PlaybackHeartbeat
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public array $track,
        public float $position,
        public float $buffered,
        public bool $isBuffering,
        public bool $isPlaying,
        /** Epoch milliseconds when the audio engine emitted the event. */
        public ?int $at = null,
        /** Playback speed when the event was emitted. */
        public ?float $rate = null,
        /** speaker, headphones, bluetooth, airplay, car, usb, hdmi or other. */
        public ?string $output = null,
    ) {
    }
}