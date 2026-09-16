<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class RemotePlayReceived
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
    ) {
    }
}
