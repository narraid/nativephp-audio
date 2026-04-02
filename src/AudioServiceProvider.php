<?php

namespace Narraid\Audio;

use Illuminate\Support\Facades\Blade;
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

    public function boot(): void
    {
        $this->loadViewsFrom(__DIR__.'/../resources/views', 'native-audio');

        Blade::directive('nativeAudioBridge', function () {
            return "<?php echo view('native-audio::bridge'); ?>";
        });
    }
}
