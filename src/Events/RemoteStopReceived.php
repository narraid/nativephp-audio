<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class RemoteStopReceived
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public string $url,
        public float $position,
        public float $duration,
        public bool $isPlaying,
        public ?array $metadata = null,
    ) {
    }
}
