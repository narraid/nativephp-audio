<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

/**
 * The audio output changed, e.g. Bluetooth headphones connected or disconnected.
 */
class AudioRouteChanged
{
    use Dispatchable, SerializesModels;

    public function __construct(
        /** speaker, headphones, bluetooth, airplay, car, usb, hdmi or other. */
        public string $output,
        public ?string $previousOutput = null,
        /** new_device, device_lost or other. */
        public string $reason = 'other',
        public array $track = [],
        public float $position = 0.0,
        public float $buffered = 0.0,
        public bool $isBuffering = false,
        public bool $isPlaying = false,
        /** Epoch milliseconds when the audio engine emitted the event. */
        public ?int $at = null,
        public ?float $rate = null,
    ) {
    }
}
