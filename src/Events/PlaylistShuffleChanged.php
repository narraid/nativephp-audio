<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlaylistShuffleChanged
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public bool $shuffle,
        /** Epoch milliseconds when the audio engine emitted the event. */
        public ?int $at = null,
    ) {
    }
}
