import Foundation
import AVFoundation
import MediaPlayer

/**
 * AudioFunctions houses all BridgeFunction implementations for audio playback control
 * on iOS. Each inner class maps 1-to-1 with a PHP-side bridge call exposed by the
 * NativePHP audio-player plugin.
 *
 * A single shared AVPlayer instance is used across all functions so
 * that state (current track, position, volume) is preserved between calls.
 */
enum AudioFunctions {
    /** Shared AVPlayer instance. Nil when no track has been loaded. */
    private static var player: AVPlayer?
    /** The currently active player item. */
    private static var playerItem: AVPlayerItem?
    /** Observer token for the end-of-play event. */
    private static var completionObserver: Any?
    /** Observer token for playback failure. */
    private static var failureObserver: Any?
    /** Guard so MPRemoteCommandCenter handlers are only registered once per process lifetime. */
    private static var remoteCommandsRegistered = false
    /** URL of the currently loaded track — used in remote-command events. */
    private static var currentURL: String = ""
    /** Observer token for AVAudioSession interruption events (calls, Siri, other apps). */
    private static var interruptionObserver: Any?
    /** Observer token for audio route changes (headphones unplugged). */
    private static var routeChangeObserver: Any?
    /** True when we paused due to an audio-focus interruption so we can auto-resume. */
    private static var pausedByFocusLoss = false
    /** Periodic time observer token for PlaybackProgressUpdated events. */
    private static var progressObserver: Any?

    static func startProgressTimer(interval: Double) {
        stopProgressTimer()
        guard interval > 0, let p = player else { return }
        let cmInterval = CMTime(seconds: interval, preferredTimescale: 600)
        progressObserver = p.addPeriodicTimeObserver(forInterval: cmInterval, queue: .main) { _ in
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackProgressUpdated", [
                "position": AudioFunctions.positionSeconds(),
                "duration": AudioFunctions.durationSeconds(),
                "url": AudioFunctions.currentURL
            ])
        }
    }

    static func stopProgressTimer() {
        if let observer = progressObserver {
            player?.removeTimeObserver(observer)
            progressObserver = nil
        }
    }

    // MARK: - Position / duration helpers

    /** Current playback position in seconds, NaN-safe. */
    private static func positionSeconds() -> Double {
        let p = player?.currentTime().seconds ?? 0.0
        return p.isNaN ? 0.0 : p
    }

    /** Total duration of the loaded item in seconds. Returns 0.0 for live/indefinite streams. */
    private static func durationSeconds() -> Double {
        let d = playerItem?.duration.seconds ?? 0.0
        return (d.isNaN || d.isInfinite) ? 0.0 : d
    }

    // MARK: - Remote command centre

    /**
     * Registers hardware/lock-screen play, pause and toggle-play-pause commands once.
     * Must be called after the AVAudioSession is activated so the system routes remote
     * events to this app.
     *
     * On iOS the system automatically shows the registered app's now-playing info on the
     * lock screen / Control Center, and tapping that UI brings the app to the foreground —
     * no extra "tap to open" code is required.
     */
    static func setupRemoteCommands() {
        guard !remoteCommandsRegistered else { return }
        remoteCommandsRegistered = true

        let center = MPRemoteCommandCenter.shared()

        center.playCommand.isEnabled = true
        center.playCommand.addTarget { _ in
            guard AudioFunctions.player != nil else { return .noSuchContent }
            AudioFunctions.player?.play()
            AudioFunctions.syncNowPlayingState()
            let payload: [String: Any] = [
                "position": AudioFunctions.positionSeconds(),
                "duration": AudioFunctions.durationSeconds(),
                "url": AudioFunctions.currentURL
            ]
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackResumed", payload)
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\RemotePlayReceived", payload)
            return .success
        }

        center.pauseCommand.isEnabled = true
        center.pauseCommand.addTarget { _ in
            guard AudioFunctions.player != nil else { return .noSuchContent }
            AudioFunctions.player?.pause()
            AudioFunctions.syncNowPlayingState()
            let payload: [String: Any] = [
                "position": AudioFunctions.positionSeconds(),
                "duration": AudioFunctions.durationSeconds(),
                "url": AudioFunctions.currentURL
            ]
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackPaused", payload)
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\RemotePauseReceived", payload)
            return .success
        }

        center.togglePlayPauseCommand.isEnabled = true
        center.togglePlayPauseCommand.addTarget { _ in
            guard let player = AudioFunctions.player else { return .noSuchContent }
            let payload: [String: Any] = [
                "position": AudioFunctions.positionSeconds(),
                "duration": AudioFunctions.durationSeconds(),
                "url": AudioFunctions.currentURL
            ]
            if player.rate > 0 {
                player.pause()
                LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackPaused", payload)
                LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\RemotePauseReceived", payload)
            } else {
                player.play()
                LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackResumed", payload)
                LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\RemotePlayReceived", payload)
            }
            AudioFunctions.syncNowPlayingState()
            return .success
        }

        center.nextTrackCommand.isEnabled = true
        center.nextTrackCommand.addTarget { _ in
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\RemoteNextTrackReceived", [
                "position": AudioFunctions.positionSeconds(),
                "duration": AudioFunctions.durationSeconds(),
                "url": AudioFunctions.currentURL
            ])
            return .success
        }

        center.previousTrackCommand.isEnabled = true
        center.previousTrackCommand.addTarget { _ in
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\RemotePreviousTrackReceived", [
                "position": AudioFunctions.positionSeconds(),
                "duration": AudioFunctions.durationSeconds(),
                "url": AudioFunctions.currentURL
            ])
            return .success
        }

        center.changePlaybackPositionCommand.isEnabled = true
        center.changePlaybackPositionCommand.addTarget { event in
            guard let positionEvent = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            let seekTo = positionEvent.positionTime
            let from = AudioFunctions.positionSeconds()
            let duration = AudioFunctions.durationSeconds()
            let time = CMTime(seconds: seekTo, preferredTimescale: 1000)
            AudioFunctions.player?.seek(to: time) { _ in
                AudioFunctions.syncNowPlayingState()
                LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\RemoteSeekReceived", [
                    "position": from,
                    "duration": duration,
                    "url": AudioFunctions.currentURL,
                    "seekTo": seekTo
                ])
            }
            return .success
        }

        center.stopCommand.isEnabled = true
        center.stopCommand.addTarget { _ in
            guard AudioFunctions.player != nil else { return .noSuchContent }
            let payload: [String: Any] = [
                "position": AudioFunctions.positionSeconds(),
                "duration": AudioFunctions.durationSeconds(),
                "url": AudioFunctions.currentURL
            ]
            AudioFunctions.stopProgressTimer()
            AudioFunctions.player?.pause()
            AudioFunctions.player = nil
            AudioFunctions.playerItem = nil
            for observer in [AudioFunctions.completionObserver, AudioFunctions.failureObserver,
                             AudioFunctions.interruptionObserver, AudioFunctions.routeChangeObserver] {
                if let o = observer { NotificationCenter.default.removeObserver(o) }
            }
            AudioFunctions.completionObserver = nil
            AudioFunctions.failureObserver = nil
            AudioFunctions.interruptionObserver = nil
            AudioFunctions.routeChangeObserver = nil
            AudioFunctions.pausedByFocusLoss = false
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackStopped", payload)
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\RemoteStopReceived", payload)
            return .success
        }
    }

    // MARK: - Audio session observers (interruptions + route changes)

    /**
     * Registers AVAudioSession observers for interruptions (phone calls, Siri, other apps stealing
     * focus) and route changes (headphones unplugged). Safe to call multiple times — registers once.
     *
     * Interruption began  → auto-pause + fire AudioFocusLostTransient + PlaybackPaused.
     * Interruption ended  → fire AudioFocusGained; auto-resume when .shouldResume is set.
     * Headphones unplugged → pause + fire AudioFocusLostTransient + PlaybackPaused.
     */
    private static func setupAudioSessionObservers() {
        guard interruptionObserver == nil else { return }

        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { notification in
            guard let typeValue = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }

            switch type {
            case .began:
                guard AudioFunctions.player != nil else { return }
                if AudioFunctions.player?.rate ?? 0 > 0 {
                    AudioFunctions.pausedByFocusLoss = true
                    AudioFunctions.player?.pause()
                    AudioFunctions.syncNowPlayingState()
                    let payload: [String: Any] = [
                        "position": AudioFunctions.positionSeconds(),
                        "duration": AudioFunctions.durationSeconds(),
                        "url": AudioFunctions.currentURL
                    ]
                    LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackPaused", payload)
                    LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\AudioFocusLostTransient", payload)
                }

            case .ended:
                let optionsValue = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                let payload: [String: Any] = [
                    "position": AudioFunctions.positionSeconds(),
                    "duration": AudioFunctions.durationSeconds(),
                    "url": AudioFunctions.currentURL
                ]
                LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\AudioFocusGained", payload)

                if options.contains(.shouldResume) && AudioFunctions.pausedByFocusLoss {
                    AudioFunctions.pausedByFocusLoss = false
                    try? AVAudioSession.sharedInstance().setActive(true)
                    AudioFunctions.player?.play()
                    AudioFunctions.syncNowPlayingState()
                    LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackResumed", payload)
                } else {
                    AudioFunctions.pausedByFocusLoss = false
                }

            @unknown default:
                break
            }
        }

        // Headphones unplugged — pause to prevent unexpected speaker playback
        routeChangeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { notification in
            guard let reasonValue = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue),
                  reason == .oldDeviceUnavailable,
                  AudioFunctions.player != nil,
                  AudioFunctions.player?.rate ?? 0 > 0 else { return }

            AudioFunctions.player?.pause()
            AudioFunctions.syncNowPlayingState()
            let payload: [String: Any] = [
                "position": AudioFunctions.positionSeconds(),
                "duration": AudioFunctions.durationSeconds(),
                "url": AudioFunctions.currentURL
            ]
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackPaused", payload)
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\AudioFocusLostTransient", payload)
        }
    }

    /**
     * Writes the current AVPlayer rate and elapsed time back into MPNowPlayingInfoCenter.
     * Call this after any play/pause state change so the lock-screen scrubber and
     * play/pause button icon stay in sync with the actual player state.
     */
    static func syncNowPlayingState() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else { return }
        let rate   = AudioFunctions.player?.rate ?? 0.0
        let elapsed = AudioFunctions.player?.currentTime().seconds ?? 0.0
        info[MPNowPlayingInfoPropertyPlaybackRate]      = rate
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsed.isNaN ? 0.0 : elapsed
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    /**
     * Starts playback of a remote or local audio URL.
     *
     * Expected parameters:
     *  - `url` (String, required) – the URI of the audio resource to play.
     *
     * On success fires [PlaybackStarted]. On completion fires [PlaybackCompleted].
     * On error fires [PlaybackFailed].
     * Returns a BridgeResponse error when `url` is missing or invalid.
     */
    class Play: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let urlString = parameters["url"] as? String,
                  let url = URL(string: urlString) else {
                return BridgeResponse.error(code: "INVALID_PARAMETERS", message: "URL is required and must be valid.")
            }

            // Optional metadata — included in PlaybackStarted and used to populate
            // lock screen / Bluetooth controls immediately without a separate setMetadata call.
            let title    = parameters["title"]    as? String
            let artist   = parameters["artist"]   as? String
            let album    = parameters["album"]    as? String
            let artwork  = parameters["artwork"]  as? String
            let duration = (parameters["duration"] as? NSNumber)?.doubleValue

            // Cleanup previous observers if any
            if let observer = AudioFunctions.completionObserver {
                NotificationCenter.default.removeObserver(observer)
            }
            if let observer = AudioFunctions.failureObserver {
                NotificationCenter.default.removeObserver(observer)
            }

            // Configure the audio session for background playback before creating the player.
            // .playback category allows audio to continue when the screen locks or the
            // app moves to the background. This must be set before AVPlayer starts.
            let session = AVAudioSession.sharedInstance()
            try? session.setCategory(.playback, mode: .default)
            try? session.setActive(true)

            // Register lock-screen / Bluetooth remote-control command handlers
            AudioFunctions.setupRemoteCommands()

            // Register audio session observers for interruptions and route changes
            AudioFunctions.setupAudioSessionObservers()

            AudioFunctions.currentURL = urlString
            AudioFunctions.playerItem = AVPlayerItem(url: url)
            AudioFunctions.player = AVPlayer(playerItem: AudioFunctions.playerItem)

            // Apply metadata to MPNowPlayingInfoCenter immediately if provided
            if let title = title {
                var info: [String: Any] = [:]
                info[MPMediaItemPropertyTitle] = title
                if let artist   = artist   { info[MPMediaItemPropertyArtist]       = artist }
                if let album    = album    { info[MPMediaItemPropertyAlbumTitle]    = album }
                if let duration = duration { info[MPMediaItemPropertyPlaybackDuration] = duration }
                info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = 0.0
                info[MPNowPlayingInfoPropertyPlaybackRate] = 1.0
                if let artworkString = artwork {
                    if let image = UIImage(contentsOfFile: artworkString) {
                        info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                    } else if let artUrl = URL(string: artworkString),
                              let data = try? Data(contentsOf: artUrl),
                              let image = UIImage(data: data) {
                        info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                    }
                }
                MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            }

            // Add completion observer
            AudioFunctions.completionObserver = NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: AudioFunctions.playerItem,
                queue: .main
            ) { _ in
                LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackCompleted", [
                    "url": urlString,
                    "duration": AudioFunctions.durationSeconds()
                ])
            }

            // Add failure observer
            AudioFunctions.failureObserver = NotificationCenter.default.addObserver(
                forName: .AVPlayerItemFailedToPlayToEndTime,
                object: AudioFunctions.playerItem,
                queue: .main
            ) { notification in
                let error = (notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error)?
                    .localizedDescription ?? "Unknown error"
                LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackFailed", [
                    "url": urlString,
                    "error": error
                ])
            }

            AudioFunctions.player?.play()

            var startedPayload: [String: Any] = ["url": urlString]
            if let title    = title    { startedPayload["title"]    = title }
            if let artist   = artist   { startedPayload["artist"]   = artist }
            if let album    = album    { startedPayload["album"]    = album }
            if let duration = duration { startedPayload["duration"] = duration }
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackStarted", startedPayload)

            return BridgeResponse.success(data: ["success": true])
        }
    }

    /**
     * Pauses the currently playing audio without releasing the AVPlayer.
     * Fires [PlaybackPaused] with the current position and duration.
     */
    class Pause: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.player?.pause()
            AudioFunctions.syncNowPlayingState()
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackPaused", [
                "position": AudioFunctions.positionSeconds(),
                "duration": AudioFunctions.durationSeconds(),
                "url": AudioFunctions.currentURL
            ])
            return BridgeResponse.success(data: ["success": true])
        }
    }

    /**
     * Resumes a previously paused AVPlayer.
     * Fires [PlaybackResumed] with the current position and duration.
     */
    class Resume: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.player?.play()
            AudioFunctions.syncNowPlayingState()
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackResumed", [
                "position": AudioFunctions.positionSeconds(),
                "duration": AudioFunctions.durationSeconds(),
                "url": AudioFunctions.currentURL
            ])
            return BridgeResponse.success(data: ["success": true])
        }
    }

    /**
     * Stops playback and fully releases the AVPlayer state.
     * After this call player is nil and a new Play call is required to resume audio.
     * Fires [PlaybackStopped] with the position and duration captured before release.
     */
    class Stop: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            // Capture state before releasing the player
            let position = AudioFunctions.positionSeconds()
            let duration = AudioFunctions.durationSeconds()

            AudioFunctions.stopProgressTimer()
            AudioFunctions.player?.pause()
            AudioFunctions.player = nil
            AudioFunctions.playerItem = nil
            if let observer = AudioFunctions.completionObserver {
                NotificationCenter.default.removeObserver(observer)
                AudioFunctions.completionObserver = nil
            }
            if let observer = AudioFunctions.failureObserver {
                NotificationCenter.default.removeObserver(observer)
                AudioFunctions.failureObserver = nil
            }
            if let observer = AudioFunctions.interruptionObserver {
                NotificationCenter.default.removeObserver(observer)
                AudioFunctions.interruptionObserver = nil
            }
            if let observer = AudioFunctions.routeChangeObserver {
                NotificationCenter.default.removeObserver(observer)
                AudioFunctions.routeChangeObserver = nil
            }
            AudioFunctions.pausedByFocusLoss = false
            // Deactivate the session so other apps can resume their audio
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackStopped", [
                "position": position,
                "duration": duration,
                "url": AudioFunctions.currentURL
            ])
            return BridgeResponse.success(data: ["success": true])
        }
    }

    /**
     * Seeks the playback position to the given number of seconds.
     * Fires [PlaybackSeeked] with from, to, and duration once the seek completes.
     *
     * Expected parameters:
     *  - `seconds` (Number, optional, default 0) – target position in seconds.
     */
    class Seek: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let seconds = (parameters["seconds"] as? NSNumber)?.doubleValue ?? 0.0
            let from = AudioFunctions.positionSeconds()
            let duration = AudioFunctions.durationSeconds()
            let time = CMTime(seconds: seconds, preferredTimescale: 600)
            AudioFunctions.player?.seek(to: time) { _ in
                LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackSeeked", [
                    "from": from,
                    "to": seconds,
                    "duration": duration,
                    "url": AudioFunctions.currentURL
                ])
            }
            return BridgeResponse.success(data: ["success": true])
        }
    }

    /**
     * Sets the playback volume.
     *
     * Expected parameters:
     *  - `level` (Number, optional, default 1.0) – volume level in the range [0.0, 1.0].
     */
    class SetVolume: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let level = (parameters["level"] as? NSNumber)?.floatValue ?? 1.0
            AudioFunctions.player?.volume = level
            return BridgeResponse.success(data: ["success": true])
        }
    }

    /**
     * Returns the total duration of the currently loaded track in seconds.
     * Returns `0.0` when no track is loaded.
     */
    class GetDuration: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let duration = AudioFunctions.playerItem?.duration.seconds ?? 0.0
            // nan represents duration not being available/loaded yet
            let validDuration = duration.isNaN ? 0.0 : duration
            return BridgeResponse.success(data: ["duration": validDuration])
        }
    }

    /**
     * Sets the interval at which PlaybackProgressUpdated events are fired.
     * Pass 0 to disable progress events.
     *
     * Expected parameters:
     *  - `seconds` (Number, optional, default 0) – interval in seconds (0 = disabled).
     */
    class SetProgressInterval: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let seconds = (parameters["seconds"] as? NSNumber)?.doubleValue ?? 0.0
            AudioFunctions.startProgressTimer(interval: seconds)
            return BridgeResponse.success(data: ["success": true])
        }
    }

    /**
     * Returns the current playback position in seconds.
     * Returns `0.0` when no track is loaded.
     */
    class GetCurrentPosition: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let position = AudioFunctions.player?.currentTime().seconds ?? 0.0
            let validPosition = position.isNaN ? 0.0 : position
            return BridgeResponse.success(data: ["position": validPosition])
        }
    }

    /**
     * Sets track metadata on MPNowPlayingInfoCenter for display on lock screens,
     * Control Center, Bluetooth devices, and CarPlay.
     *
     * Expected parameters:
     *  - `title`    (String, required) – track title.
     *  - `artist`   (String, optional) – artist name.
     *  - `album`    (String, optional) – album name.
     *  - `artwork`  (String, optional) – URL or local path of the artwork image.
     *  - `duration` (Number, optional) – total duration in seconds.
     */
    class SetMetadata: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let title = parameters["title"] as? String else {
                return BridgeResponse.error(code: "INVALID_PARAMETERS", message: "title is required.")
            }

            // Build the info dictionary on the current (PHP worker) thread.
            // Artwork loading may involve a blocking network call so we keep it here
            // rather than on the main thread.
            var info: [String: Any] = [:]
            info[MPMediaItemPropertyTitle] = title

            if let artist = parameters["artist"] as? String {
                info[MPMediaItemPropertyArtist] = artist
            }
            if let album = parameters["album"] as? String {
                info[MPMediaItemPropertyAlbumTitle] = album
            }
            if let duration = (parameters["duration"] as? NSNumber)?.doubleValue {
                info[MPMediaItemPropertyPlaybackDuration] = duration
            }

            // Snapshot player state before crossing to the main thread.
            let elapsed = AudioFunctions.player?.currentTime().seconds ?? 0.0
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsed.isNaN ? 0.0 : elapsed
            info[MPNowPlayingInfoPropertyPlaybackRate] = AudioFunctions.player?.rate ?? 0.0

            if let artworkString = parameters["artwork"] as? String {
                if let image = UIImage(contentsOfFile: artworkString) {
                    info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                } else if let url = URL(string: artworkString),
                          let data = try? Data(contentsOf: url),
                          let image = UIImage(data: data) {
                    info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                }
            }

            // Apple requires MPNowPlayingInfoCenter and AVAudioSession updates to happen
            // on the main thread. NativePHPCall is invoked from the PHP worker thread, so
            // dispatch to main. Without this the OS silently ignores the update on the
            // first call (no audio engine refresh is running yet to flush the write).
            let infoToSet = info
            DispatchQueue.main.async {
                let session = AVAudioSession.sharedInstance()
                try? session.setCategory(.playback, mode: .default)
                try? session.setActive(true)
                // Register lock-screen / Control Center handlers so the widget appears
                // immediately, even when setMetadata() is called before play().
                AudioFunctions.setupRemoteCommands()
                MPNowPlayingInfoCenter.default().nowPlayingInfo = infoToSet
            }

            return BridgeResponse.success(data: ["success": true])
        }
    }
}
