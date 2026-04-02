<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlaylistTrackChanged
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public int $index,
        public int $total,
        public string $url,
        public ?string $title = null,
        public ?string $artist = null,
        public ?string $album = null,
        public ?float $duration = null,
        public ?array $metadata = null,
    ) {
    }
}
