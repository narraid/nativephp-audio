<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class SleepTimerExpired
{
    use Dispatchable, SerializesModels;

    public function __construct(
        /** Epoch milliseconds when the audio engine emitted the event. */
        public ?int $at = null,
    ) {
    }
}
