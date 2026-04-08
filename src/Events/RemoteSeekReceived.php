<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class RemoteSeekReceived
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public string $url,
        public float $position,
        public float $duration,
        public float $seekTo,
        public ?array $metadata = null,
    ) {
    }
}
