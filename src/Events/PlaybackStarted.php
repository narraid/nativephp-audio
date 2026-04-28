<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlaybackStarted
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public array $track,
        public float $position = 0.0,
    ) {
    }
}
