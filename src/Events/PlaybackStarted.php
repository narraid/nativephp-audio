<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlaybackStarted
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public string $url,
        public ?string $title = null,
        public ?string $artist = null,
        public ?string $album = null,
        public ?float $duration = null,
        public ?array $metadata = null,
    ) {
    }
}
