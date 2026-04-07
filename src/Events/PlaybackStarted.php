<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlaybackStarted
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public string $url,
        public float $position = 0.0,
        public ?string $title = null,
        public ?string $artist = null,
        public ?string $album = null,
        public ?float $duration = null,
        public ?string $artwork = null,
        public ?string $clip = null,
        public ?array $metadata = null,
    ) {
    }
}
