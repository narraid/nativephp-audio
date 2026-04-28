<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlaylistEnded
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public ?int $lastIndex = null,
        public ?array $lastTrack = null,
        public ?float $lastPosition = null,
    ) {
    }
}
