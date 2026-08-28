<?php

namespace Narraid\Audio;

use Illuminate\Support\ServiceProvider;

class AudioServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(Audio::class, function () {
            return new Audio;
        });

        $this->app->alias(Audio::class, 'audio');
    }
}
