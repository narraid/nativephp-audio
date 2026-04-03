<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlaybackCompleted
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public string $url,
        public float $duration,
        public ?array $metadata = null,
    ) {
    }
}
