<?php

namespace Theunwindfront\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class RemotePlayReceived
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public float $position,
        public float $duration,
    ) {
    }
}
