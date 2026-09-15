<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlaybackLoaded
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public array $track,
        /** Epoch milliseconds when the audio engine emitted the event. */
        public ?int $at = null,
    ) {
    }
}