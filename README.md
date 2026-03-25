# NativePHP Audio Player Plugin

A NativePHP plugin for audio playback on mobile devices.

## Features

- **Play/Pause/Resume** - Full control over audio playback
- **Stop** - Stop and reset playback position
- **Seek** - Jump to any position in the audio
- **Volume Control** - Set volume programmatically
- **Duration/Position** - Get audio duration and current position

## 🚀 Future Roadmap

I'm actively working on the following features and will update the package soon:

- **MediaSession Support** - Send track metadata (artist, title, album, artwork) to Bluetooth devices, lock screens, and OS media centers.
- **Remote Controls** - Handle playback commands (play, pause, next, previous) from connected devices and headphone buttons.
- **Audio Focus** - Automatic pausing/ducking when other apps play audio or during incoming calls.
- **Background Playback** - Enhanced support for playing audio when the app is in the background or the screen is off.


## Installation

```bash
# Install the package
composer require theunwindfront/nativephp-audio

# Publish the plugins provider (first time only)
php artisan vendor:publish --tag=nativephp-plugins-provider

# Register the plugin
php artisan native:plugin:register theunwindfront/nativephp-audio

# Verify registration
php artisan native:plugin:list
```

## Usage

### PHP (Livewire/Blade)

```php
use Theunwindfront\Audio\Facades\Audio;

// Play an audio file (using a sample open-source link for testing)
Audio::play('https://www.w3schools.com/html/horse.mp3');

// Pause
Audio::pause();

// Resume
Audio::resume();

// Seek to 30 seconds
Audio::seek(30.0);

// Set volume (0.0 to 1.0)
Audio::setVolume(0.8);

// Get info
$duration = Audio::getDuration();
$position = Audio::getCurrentPosition();
```

### JavaScript (Vue/React/Inertia)

```javascript
import { audioPlayer } from '@theunwindfront/nativephp-audio';

// Play an audio file
await audioPlayer.play('https://www.w3schools.com/html/horse.mp3');

// Pause/Resume
await audioPlayer.pause();
await audioPlayer.resume();

// Stop and reset
await audioPlayer.stop();

// Seek to 30 seconds
await audioPlayer.seek(30.0);

// Volume Control (0.0 to 1.0)
await audioPlayer.setVolume(1.0);

// Get Duration and Current Position
const duration = await audioPlayer.getDuration();
const position = await audioPlayer.getCurrentPosition();

// Event Listeners
window.addEventListener('audio-started', (e) => console.log('Started:', e.detail.url));
window.addEventListener('audio-paused', () => console.log('Paused'));
window.addEventListener('audio-stopped', () => console.log('Stopped'));
window.addEventListener('audio-completed', (e) => console.log('Completed:', e.detail.url));
```

## API Reference

| Method | Returns | Description |
|--------|---------|-------------|
| `play(string $url)` | `bool` | Play an audio file |
| `pause()` | `bool` | Pause playback |
| `resume()` | `bool` | Resume playback |
| `stop()` | `bool` | Stop playback |
| `seek(float $seconds)` | `bool` | Seek to position |
| `setVolume(float $level)` | `bool` | Set volume (0.0-1.0) |
| `getDuration()` | `?float` | Get audio duration |
| `getCurrentPosition()` | `?float` | Get current position |

## Version Support

| Platform | Minimum Version |
|----------|----------------|
| Android  | 5.0 (API 21)   |
| iOS      | 13.0            |

## Support

For questions or issues, email pansuriya.sagar94@gmail.com

## License

The MIT License (MIT). Please see [License File](LICENSE) for more information.

MediaSession Support — What was added

PHP (src/Audio.php + src/Facades/Audio.php)
- New setMetadata(title, artist, album, artwork, duration) method — bridges to native via Audio.setMetadata
- Facade @method annotation added

iOS (resources/ios/AudioFunctions.swift)
- Imports MediaPlayer framework
- New SetMetadata class sets MPNowPlayingInfoCenter.default().nowPlayingInfo with title, artist, album, duration, elapsed time, and playback rate
- Artwork loaded from local file path or remote URL (synchronous fetch — happens on the bridge thread)
- Activates AVAudioSession so remote-control events are delivered to the lock screen / Control Center

Android (resources/android/.../AudioFunctions.kt)
- Imports MediaSessionCompat, MediaMetadataCompat, PlaybackStateCompat
- Shared MediaSessionCompat instance created lazily via getOrCreateSession()
- New SetMetadata class populates MediaMetadataCompat (title, artist, album, duration, artwork bitmap) and sets an initial PlaybackStateCompat — making the track visible on lock screens, Bluetooth devices, and Android Auto

Bridge manifest (nativephp.json)
- Registered Audio.setMetadata for both platforms
- Added androidx.media:media:1.7.0 dependency (provides MediaSessionCompat)
- Added android.permission.MEDIA_CONTENT_CONTROL permission

JavaScript (resources/js/audio.js)
- setMetadata({ title, artist, album, artwork, duration }) method added

Usage example:                                                                                                                                                                                                                                                          
Audio::setMetadata(                                                                                                                                                                                                                                                       
title: 'Song Title',
artist: 'Artist Name',                                                                                                                                                                                                                                                
album: 'Album Name',                                                                                                                                                                                                                                                
artwork: 'https://example.com/cover.jpg',
duration: 243.5,                         
);                                                                        


                                                                                                                                                                                                                                                                           
---                                                                                                                                                                                                                                                                     
Background Playback — What was added

iOS (AudioFunctions.swift)
- Play: configures AVAudioSession with .playback category and activates it before the AVPlayer starts — this is the single most important change; without it iOS suspends audio the moment the app backgrounds
- Stop: deactivates the session with .notifyOthersOnDeactivation so other apps can reclaim audio, and clears MPNowPlayingInfoCenter

nativephp.json (iOS)
- Added "UIBackgroundModes": ["audio"] to info_plist — tells iOS this app is permitted to run audio in the background; without this entry the OS ignores the session category

Android — new AudioService.kt
- A START_STICKY foreground service that shows a persistent "Now Playing" notification
- Android requires a foreground service + visible notification to keep any process alive when backgrounded; without it the OS will kill the MediaPlayer within seconds of the screen turning off
- start(context, title, artist) — starts/restarts the service (called by Play)
- stop(context) — stops the service and dismisses the notification (called by Stop)
- updateNotification(context, title, artist) — refreshes the notification text (called by SetMetadata)
- Creates a NotificationChannel for Android 8+, uses IMPORTANCE_LOW so it's silent

Android AudioFunctions.kt
- Play: calls AudioService.start(context) after MediaPlayer begins
- Stop: calls AudioService.stop(context) after releasing MediaPlayer
- SetMetadata: calls AudioService.updateNotification(...) so the notification shows the real track title/artist

nativephp.json (Android)
- Added FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK (Android 14+), and WAKE_LOCK permissions
- Registered AudioService under services with foreground_service_type: mediaPlayback
- Added androidx.core:core-ktx:1.13.1 dependency (for NotificationCompat)

  New event — PlaybackProgressUpdated
    - Fired by the native layer at a configurable interval during playback
    - Carries $position (current seconds) and $duration (total seconds)
    - Auto-discovered by NativePHP Mobile's PluginRegistry via the nativephp.json events array
    - Dispatched via POST /_native/api/events just like the other events

  How the events flow:
    1. Native layer (Swift/Kotlin) calls POST /_native/api/events with event=Theunwindfront\Audio\Events\PlaybackStarted&payload[]=<url>
    2. NativePHP's DispatchEventFromAppController instantiates and dispatches the Laravel event
    3. Your app listens with standard Laravel event listeners or Livewire #[On('native:...')] attributes


Done. Here's a summary of all changes:

Updated events:
- PlaybackStarted — added title, artist, album, duration (nullable)                                                                                                              - PlaybackPaused — added position, duration
- PlaybackStopped — added position, duration
- PlaybackCompleted — added url, duration

New events:
- PlaybackResumed — position, duration
- PlaybackSeeked — from, to, duration
- PlaybackFailed — url, error

Bridge updated with audio-resumed, audio-seeked, audio-failed custom events.

One thing to note: the native side (Swift/Kotlin) will need to be updated to pass the new payload fields when dispatching these events. The PlaybackPaused, PlaybackStopped,   
PlaybackResumed, PlaybackSeeked, and PlaybackFailed events especially require the native layer to provide position/duration at the moment they fire.


Here's a summary of all the changes made:
                                                                                          
---
iOS (AudioFunctions.swift)

┌─────────────────────────────────────┬─────────────────────────────┬─────────────────────────────────────────────────────────────┐                             
│                Event                │           Before            │                            After                            │                             
├─────────────────────────────────────┼─────────────────────────────┼─────────────────────────────────────────────────────────────┤                           
│ PlaybackStarted (remote play cmd)   │ PlaybackStarted + [:]       │ PlaybackResumed + {position, duration}                      │
├─────────────────────────────────────┼─────────────────────────────┼─────────────────────────────────────────────────────────────┤
│ PlaybackPaused (remote/Pause class) │ [:]                         │ {position, duration}                                        │                             
├─────────────────────────────────────┼─────────────────────────────┼─────────────────────────────────────────────────────────────┤                             
│ PlaybackResumed (Resume class)      │ fired PlaybackStarted + [:] │ fires PlaybackResumed + {position, duration}                │                             
├─────────────────────────────────────┼─────────────────────────────┼─────────────────────────────────────────────────────────────┤                             
│ PlaybackStopped (Stop class)        │ [:]                         │ {position, duration} captured before player released        │                           
├─────────────────────────────────────┼─────────────────────────────┼─────────────────────────────────────────────────────────────┤                             
│ PlaybackCompleted                   │ {url}                       │ {url, duration}                                             │                           
├─────────────────────────────────────┼─────────────────────────────┼─────────────────────────────────────────────────────────────┤                             
│ PlaybackSeeked                      │ not fired                   │ fires in seek completion callback with {from, to, duration} │                           
├─────────────────────────────────────┼─────────────────────────────┼─────────────────────────────────────────────────────────────┤                             
│ PlaybackFailed                      │ not fired                   │ fires on AVPlayerItemFailedToPlayToEndTime                  │                           
└─────────────────────────────────────┴─────────────────────────────┴─────────────────────────────────────────────────────────────┘

Added currentURL, positionSeconds(), durationSeconds() helpers. Added failureObserver static var alongside completionObserver.
   
---                                                                                                                                                             
Android (AudioFunctions.kt)

- Play constructor changed to FragmentActivity — stores a WeakReference used by all subsequent dispatches
- Added sendEvent() helper in companion (marshals to main thread via Handler)
- Play.execute: fires PlaybackStarted; sets onCompletionListener → PlaybackCompleted; sets onErrorListener → PlaybackFailed
- Pause.execute: fires PlaybackPaused with {position, duration}
- Resume.execute: fires PlaybackResumed with {position, duration}
- Stop.execute: fires PlaybackStopped with {position, duration} (captured before release)
- Seek.execute: fires PlaybackSeeked with {from, to, duration}
- togglePlayPause(): fires PlaybackPaused or PlaybackResumed accordingly

nativephp.json

Added PlaybackResumed, PlaybackSeeked, and PlaybackFailed to the events array for PluginRegistry auto-discovery.        