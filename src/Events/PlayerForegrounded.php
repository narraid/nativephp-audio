<?php

namespace Narraid\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlayerForegrounded
{
    use Dispatchable, SerializesModels;

    public function __construct(
    ) {
    }
}
