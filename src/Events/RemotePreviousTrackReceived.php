<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class RemotePreviousTrackReceived
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public array $track,
        public float $position,
        public bool $isBuffering,
        public bool $isPlaying,
    ) {
    }
}
