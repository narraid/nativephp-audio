## 🎵 NativePHP Audio Player v8.1.0

Signals for Spotify-style listening stats.

### Changes
- **Every state payload now includes `rate`** (playback speed) **and `output`** (`speaker`, `headphones`, `bluetooth`, `airplay`, `car`, `usb`, `hdmi` or `other`). All PHP event classes with a position accept them as optional `?float $rate` / `?string $output`.
- **`PlaybackPaused` and `PlaybackStopped` include `cause`**: `user` (the `pause` / `stop` bridge calls), `remote` (lock screen, notification, headset), `focus_loss`, `focus_loss_transient` (a call or another app), `route_lost` (headphones unplugged, Bluetooth gone), `sleep_timer` or `reset`.
- **New `PlaybackHeartbeat` event**, fired every `Audio::setHeartbeatInterval($seconds)` while playing (5–600 s; `0`, the default, turns it off). Heartbeats are never merged in the background queue, so each one survives a long background stretch with its own `at`.
- **New `AudioRouteChanged` event** (`output`, `previousOutput`, `reason`: `new_device`, `device_lost` or `other`) when the audio output changes.

### Fixes
- **Android**: Playback pauses when headphones are unplugged (`ACTION_AUDIO_BECOMING_NOISY`), with `cause: route_lost`.
- **iOS**: Unplugging headphones no longer sends `AudioFocusLostTransient`; listen for `PlaybackPaused` with `cause: route_lost`.

## 🎵 NativePHP Audio Player v7.1.0

### Fixes
- **iOS**: Events no longer queue forever after opening Control Center, the notification shade or a call banner. The background queue is now used only after `didEnterBackground`, and is released on `willEnterForeground` / `didBecomeActive` (previously set on `willResignActive`, which returning from those overlays never undid — the player UI froze until a real background cycle).
- **iOS / Android**: Remote "next" (lock screen, headset, notification) on the last track with repeat off no longer restarts that track — it does nothing.
- **Android**: Events sent while no activity is available are queued instead of silently dropped.
- **iOS / Android**: The background event queue is thread-safe.

### Changes
- **Every event payload now includes `at`** — epoch milliseconds when the engine emitted it, on live and queued events alike. All PHP event classes accept an optional `?int $at`, so existing listeners are unaffected. Use it to timestamp events replayed by `Audio::drainEvents()`.
- **Background queue is bounded**: a `PlaybackProgressUpdated` queued directly after another replaces it (the `at` values keep the elapsed time), and the queue holds at most 5000 events.

## 🚧 Coming Soon (v1.1.0)

- **MediaSession Support**: Full track metadata (artist, title, album, etc.) on Bluetooth devices and OS media controls.
- **Remote Control Commands**: Handle play/pause/prev/next from headphones and lock screens.
- **Improved Background Playback**: Better stability for long-running audio sessions.

## 🎵 NativePHP Audio Player v1.0.2


### Fixes
- **Android**: Fixed incorrect `BridgeFunction` import in `AudioFunctions.kt`.

## 🎵 NativePHP Audio Player v1.0.1

### Fixes
- **Package Naming**: Corrected package name to `narraid/nativephp-audio` for better composer integration.

## 🎵 NativePHP Audio Player v1.0.0

### Features & Improvements
- **Core Audio Playback**: Native audio playback for iOS (Swift) and Android (Kotlin).
- **Controls**: Play, pause, stop, and seek controls with volume adjustment.
- **JavaScript Bridge**: Comprehensive Vue/React/Inertia support with full API and event listeners.
- **Unified API**: Simplified PHP API for cross-platform audio management.
- **CI/CD**: Automated GitHub Releases on tag push.