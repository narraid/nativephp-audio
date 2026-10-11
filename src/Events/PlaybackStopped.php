<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlaybackStopped
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public array $track,
        public float $position,
        public float $buffered = 0.0,
        public bool $isBuffering = false,
        public bool $isPlaying = false,
        /** Epoch milliseconds when the audio engine emitted the event. */
        public ?int $at = null,
        /** Playback speed when the event was emitted. */
        public ?float $rate = null,
        /** speaker, headphones, bluetooth, airplay, car, usb, hdmi or other. */
        public ?string $output = null,
        /** Why playback paused or stopped: user, remote, focus_loss, focus_loss_transient, route_lost, sleep_timer or reset. */
        public ?string $cause = null,
    ) {
    }
}
