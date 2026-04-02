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
composer require narraid/nativephp-audio

# Publish the plugins provider (first time only)
php artisan vendor:publish --tag=nativephp-plugins-provider

# Register the plugin
php artisan native:plugin:register narraid/nativephp-audio

# Verify registration
php artisan native:plugin:list
```

## Usage

### PHP (Livewire/Blade)

```php
use Narraid\Audio\Facades\Audio;

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
import { audioPlayer } from '@narraid/nativephp-audio';

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
    1. Native layer (Swift/Kotlin) calls POST /_native/api/events with event=Narraid\Audio\Events\PlaybackStarted&payload[]=<url>
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


Everything looks correct. Here's a summary of all the changes made:
                                                                                                                                                                                                                          
---                                                                                                                                                                                                                   
5 new PHP events (src/Events/):
- RemotePlayReceived — fires when play is triggered from a remote device/headphone
- RemotePauseReceived — fires when pause is triggered remotely
- RemoteNextTrackReceived — fires when next track is pressed; app handles loading the new track
- RemotePreviousTrackReceived — fires when previous is pressed; includes position so app can decide to restart or go back
- RemoteStopReceived — fires when stop is triggered remotely (also fires PlaybackStopped)

All carry position and duration as context.

iOS (AudioFunctions.swift):
- Added nextTrackCommand, previousTrackCommand, and stopCommand handlers in setupRemoteCommands()
- play/pause/togglePlayPause now also fire RemotePlayReceived/RemotePauseReceived alongside the existing PlaybackResumed/PlaybackPaused

Android (AudioFunctions.kt):
- getOrCreateSession() now sets a MediaSessionCompat.Callback with onPlay, onPause, onSkipToNext, onSkipToPrevious, onStop — handles headphone buttons and Bluetooth commands
- updateSessionState() now advertises ACTION_SKIP_TO_NEXT, ACTION_SKIP_TO_PREVIOUS, and ACTION_STOP so controls appear on lock screen and BT devices

Android (AudioService.kt):
- Added ACTION_NEXT and ACTION_PREVIOUS constants
- Notification now shows [⏮ Previous] [⏯ Play/Pause] [⏭ Next] buttons; compact view shows the play/pause button
- onStartCommand handles the new actions by routing through MediaSession.transportControls

Bridge (bridge.blade.php + nativephp.json):
- 5 new JS custom events: audio-remote-play, audio-remote-pause, audio-remote-next, audio-remote-previous, audio-remote-stop         

---                                                                                                                                                                                                                   
4 new PHP events (src/Events/):
- AudioFocusLost — permanent loss; playback is paused, no auto-resume (another app took over music)
- AudioFocusLostTransient — temporary loss (incoming call, Siri, notification); playback auto-pauses
- AudioFocusDucked — another app requested ducking; volume lowered to 20% (Android only — iOS handles ducking at the OS level without notifying the app)
- AudioFocusGained — focus returned; volume restored, playback auto-resumes if it was paused by a transient loss

All carry position and duration.

Android (AudioFunctions.kt):
- AudioManager.OnAudioFocusChangeListener handles all 4 focus states
- requestAudioFocus() called at start of Play — uses AudioFocusRequest (API 26+) or the legacy API
- abandonAudioFocus() called in Stop and the MediaSession onStop() callback
- SetVolume now tracks userVolume so the correct level is restored after ducking

iOS (AudioFunctions.swift):
- setupAudioSessionObservers() registers:
  - AVAudioSession.interruptionNotification — handles calls/Siri (began → pause, ended → auto-resume when shouldResume is set)
  - AVAudioSession.routeChangeNotification — pauses when headphones are unplugged (.oldDeviceUnavailable)
- Called once from Play.execute() alongside setupRemoteCommands()
- Observers cleaned up in both Stop.execute() and the remote stop command handler

Bridge (bridge.blade.php): audio-focus-lost, audio-focus-lost-transient, audio-focus-ducked, audio-focus-gained custom events

HLS compatibility audit

iOS — was already correct
- AVPlayer + AVPlayerItem(url:) supports HLS (.m3u8) natively and out of the box
- Playback is inherently asynchronous — no blocking calls
- durationSeconds() was NaN-guarded; added isInfinite guard too (some HLS implementations return CMTime.positiveInfinity for duration instead of CMTime.indefinite)

Android — two bugs fixed

┌───────────────────┬──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┬───────────────────────────────────────────────────────────────┐    
│                   │                                                            Before                                                            │                             After                             │    
├───────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────┤  
│ Prepare for       │ prepare() — blocks the thread while fetching the .m3u8 playlist and buffering the first segment. Android docs explicitly     │ prepareAsync() — returns immediately; onPreparedListener      │
│ streams           │ warn against this for streams.                                                                                               │ fires when ready                                              │
├───────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────┤    
│ Live stream       │ durationSeconds() returned -0.001 because MediaPlayer.getDuration() returns -1 for live/indefinite HLS                       │ Returns 0.0 for any negative duration value                   │
│ duration          │                                                                                                                              │                                                               │    
└───────────────────┴──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┴───────────────────────────────────────────────────────────────┘

Behavioural change to note: PlaybackStarted now fires slightly later on Android — after buffering completes rather than immediately when play() is called. This is correct: it now fires when audio actually begins     
playing, which is consistent with iOS behaviour and makes sense for HLS since there's a buffering window before sound starts.


New file: src/Events/RemoteSeekReceived.php                                                                                                                                                                             
PHP event with url, position, duration, and seekTo fields.

nativephp.json
Registered RemoteSeekReceived in the events list.

resources/views/bridge.blade.php
Maps the PHP event to the JS custom event audio-remote-seek.

resources/ios/AudioFunctions.swift                                                                                                                                                                                      
Registered changePlaybackPositionCommand handler — seeks the player then dispatches RemoteSeekReceived.

resources/android/com/narraid/audio/AudioFunctions.kt                                                                                                                                                          
Added onSeekTo() override in the MediaSessionCompat.Callback — seeks the player and dispatches RemoteSeekReceived.
                                                                                                                                                                                                                          
---
How to use it in your app

JavaScript:
window.addEventListener('audio-remote-seek', (e) => {                                                                                                                                                                   
// e.detail.seekTo — where the user dragged to (seconds)                                                                                                                                                          
// e.detail.position — where playback was before seek                                                                                                                                                               
// e.detail.url — current track                                                                                                                                                                                     
});

Livewire:                                                                                                                                                                                                               
#[On('native:Narraid\\Audio\\Events\\RemoteSeekReceived')]                                                                                                                                                     
public function handleRemoteSeek($url, $position, $duration, $seekTo) {                                                                                                                                                 
// update your UI position state                                   
}                                      

⏺ Here's what was fixed:

iOS (AudioFunctions.swift — SetMetadata):
- The AVAudioSession category (.playback) and setActive(true) are now called before writing to MPNowPlayingInfoCenter. Without this, on the very first call the
  OS has no active playback session to attach the info to, so it renders a blank "Now Playing" block.
- setupRemoteCommands() is now called (changed from private to internal), which registers the lock screen widget handlers immediately — this is required for the
  widget to appear on the first call.

Android (AudioFunctions.kt — SetMetadata):
- updateSessionState() is now only called when mediaPlayer != null (i.e. audio is actually loaded). Previously it always set STATE_PAUSED with speed 0.0f when  
  called before play(), which caused some Android versions to hide or grey out the lock screen media controls. After play() sets STATE_PLAYING, the second        
  setMetadata call would correctly show the controls — that was the race you were seeing.
  Changes Made

  All 4 locations now follow the same pattern:

  1. Set metadata immediately without artwork → title/artist appear on lock screen right away
  2. Load artwork on a background thread (DispatchQueue.global on iOS, Thread { } on Android)
  3. Patch artwork in on the main thread once loaded

  ┌──────────────────────┬────────────────────────────────────────┐                                                                                                                                                       
  │         File         │                Location                │                                                                                                                                                     
  ├──────────────────────┼────────────────────────────────────────┤                                                                                                                                                       
  │ AudioFunctions.swift │ SetMetadata.execute()                  │                                                                                                                                                     
  ├──────────────────────┼────────────────────────────────────────┤
  │ AudioFunctions.swift │ Play.execute() (inline metadata block) │                                                                                                                                                       
  ├──────────────────────┼────────────────────────────────────────┤
  │ AudioFunctions.kt    │ SetMetadata.execute()                  │                                                                                                                                                       
  ├──────────────────────┼────────────────────────────────────────┤                                                                                                                                                       
  │ AudioFunctions.kt    │ Play.execute() (inline metadata block) │
  └──────────────────────┴────────────────────────────────────────┘     


Refactoring Summary

Extracted Helpers (both platforms)

- sendEvent(name, payload) - auto-prefixes event names, eliminating "Narraid\\Audio\\Events\\" repeated ~15 times
- statePayload() - builds the common position/duration/url dictionary, was copy-pasted ~15 times
- loadArtworkAsync() - shared artwork loading logic, was duplicated between Play and SetMetadata

Swift-specific

- refreshNowPlayingInfo() - single source of truth for building nowPlayingInfo from stored metadata
- removeObservers() - centralized observer cleanup (was duplicated in Stop + remote stop handler)
- resetPlayer() - full teardown in one call (was 15+ lines duplicated in Stop + remote stop)
- activateAudioSession() / deactivateAudioSession() - extracted repeated session setup

Kotlin-specific

- buildSessionMetadata() - single source of truth for MediaMetadataCompat (was duplicated in Play + SetMetadata)
- applySessionMetadata(context) - sets session metadata + triggers async artwork loading
- releasePlayer() - centralized cleanup (was duplicated in Stop + remote stop callback)

SetMetadata-first fix

- Both platforms: Metadata is now stored in static properties (metaTitle, metaArtist, etc.) as a shared source of truth
- iOS: Play without inline metadata now preserves prior SetMetadata data. syncNowPlayingState() is called after play() so the lock screen rate updates correctly
- Android: SetMetadata no longer starts the foreground service when mediaPlayer is null. Play uses stored metadata for the service notification title/artist instead of relying on AudioService.currentTitle

Line count reduction

- Swift: 662 -> 494 lines (-25%)
- Kotlin AudioFunctions: 597 -> 535 lines (-10%)
- AudioService.kt: unchanged (already clean)

✻ Brewed for 2m 20s                

Changes made:

1. src/Audio.php — Removed setProgressInterval() method
2. nativephp.json — Removed Audio.setProgressInterval bridge function entry
3. resources/ios/AudioFunctions.swift:                                                                                                                                                                                                                                  
   - Removed SetProgressInterval bridge class                                                                                                                                                                                                                            
   - Added defaultProgressInterval = 10.0 constant                                                                                                                                                                                                                       
   - Auto-starts progress timer on Play (after player?.play())                                                                                                                                                                                                           
   - Resets timer on Pause, Resume, and Seek                                                                                                                                                                                                                             
   - Stops timer on PlaybackCompleted and PlaybackFailed                                                                                                                                                                                                                 
   - Stop on Stop was already handled via resetPlayer()
4. resources/android/com/narraid/audio/AudioFunctions.kt:                                                                                                                                                                                                        
   - Removed SetProgressInterval bridge class                                                                                                                                                                                                                            
   - Added DEFAULT_PROGRESS_INTERVAL_MS = 10_000 constant                                                                                                                                                                                                                
   - Auto-starts progress timer on Play (after mp.start())                                                                                                                                                                                                               
   - Resets timer on Pause, Resume, and Seek                                                                                                                                                                                                                             
   - Stops timer on PlaybackCompleted and PlaybackFailed
   - Stop on Stop was already handled via releasePlayer()                                                                                                                                                                                                                
   - Updated ON_RESUME lifecycle to use the default constant

   All changes made. Here's a summary of what was updated on each platform:

Android (AudioFunctions.kt)
- Added metaMetadata: Map<String, Any>? to stored metadata fields
- In both Load and Play: parse metadata from the JSON payload via optJSONObject("metadata")
- Store metaMetadata = metadata alongside the other meta fields when title != null
- Append metadata?.let { payload["metadata"] = it } to both PlaybackLoaded and PlaybackStarted event payloads

iOS (AudioFunctions.swift)
- Added metaMetadata: [String: Any]? to stored metadata fields
- Added metadata: [String: Any]? parameter to preparePlayer and stores it in metaMetadata when title != nil
- In both Load and Play: parse metadata = parameters["metadata"] as? [String: Any]
- Pass it through to preparePlayer and append if let m = metadata { payload["metadata"] = m } to both PlaybackLoaded and PlaybackStarted event payloads        