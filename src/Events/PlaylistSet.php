<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlaylistSet
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public int $total,
        public int $startIndex,
        public bool $autoPlay,
        public float $startSeconds,
        public ?int $lastIndex = null,
        public ?array $lastTrack = null,
        public ?float $lastPosition = null,
    ) {
    }
}
