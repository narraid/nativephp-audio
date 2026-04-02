import Foundation
import AVFoundation
import MediaPlayer

enum AudioFunctions {

    // MARK: - Player State

    private static var player: AVPlayer?
    private static var playerItem: AVPlayerItem?
    private static var currentURL = ""

    // MARK: - Stored Metadata (single source of truth for now-playing info)

    private static var metaTitle: String?
    private static var metaArtist: String?
    private static var metaAlbum: String?
    private static var metaDuration: Double?
    private static var metaArtworkSource: String?
    private static var metaMetadata: [String: Any]?

    // MARK: - Observers

    private static var completionObserver: Any?
    private static var failureObserver: Any?
    private static var interruptionObserver: Any?
    private static var routeChangeObserver: Any?
    private static var progressObserver: Any?
    private static weak var progressObserverPlayer: AVPlayer?

    // MARK: - Flags

    private static var remoteCommandsRegistered = false
    private static var pausedByFocusLoss = false

    // MARK: - Progress Defaults

    private static let defaultProgressInterval: Double = 10.0

    // MARK: - Event Helpers

    private static let eventPrefix = "Theunwindfront\\Audio\\Events\\"

    private static func sendEvent(_ name: String, _ payload: [String: Any]) {
        LaravelBridge.shared.send?(eventPrefix + name, payload)
    }

    private static func statePayload() -> [String: Any] {
        ["position": positionSeconds(), "duration": durationSeconds(), "url": currentURL]
    }

    // MARK: - Position / Duration

    private static func positionSeconds() -> Double {
        let p = player?.currentTime().seconds ?? 0.0
        return p.isNaN ? 0.0 : p
    }

    private static func durationSeconds() -> Double {
        let d = playerItem?.duration.seconds ?? 0.0
        return (d.isNaN || d.isInfinite) ? 0.0 : d
    }

    // MARK: - Plugin Initialization

    /**
     * Called by NativePHP at app launch (registered via nativephp.json init_function).
     * Configures the AVAudioSession with the .playback category so that audio continues
     * in the background as soon as the app starts — matching the AppDelegate-level
     * setup used in the reference implementation.
     */
    static func setupAudioSession() {
        NativePHPPluginRegistry.shared.registerOnAppLaunch("Audio") {
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
            try? AVAudioSession.sharedInstance().setActive(true)
        }
    }

    // MARK: - Audio Session

    private static func activateAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default)
        try? session.setActive(true)
    }

    private static func deactivateAudioSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: - Now Playing Info

    /**
     * Rebuilds MPNowPlayingInfoCenter from stored metadata fields.
     * Artwork is loaded asynchronously and patched in once available.
     */
    private static func refreshNowPlayingInfo() {
        guard let title = metaTitle else { return }
        var info: [String: Any] = [MPMediaItemPropertyTitle: title]
        if let artist   = metaArtist   { info[MPMediaItemPropertyArtist]           = artist }
        if let album    = metaAlbum    { info[MPMediaItemPropertyAlbumTitle]        = album }
        if let duration = metaDuration { info[MPMediaItemPropertyPlaybackDuration]  = duration }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = positionSeconds()
        info[MPNowPlayingInfoPropertyPlaybackRate]        = player?.rate ?? 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info

        if let src = metaArtworkSource {
            loadArtworkAsync(from: src)
        }
    }

    /**
     * Loads artwork from a URL or local file path on a background thread,
     * then patches it into the existing nowPlayingInfo on the main thread.
     */
    private static func loadArtworkAsync(from source: String) {
        DispatchQueue.global(qos: .userInitiated).async {
            let image: UIImage?
            if let local = UIImage(contentsOfFile: source) {
                image = local
            } else if let url = URL(string: source),
                      let data = try? Data(contentsOf: url) {
                image = UIImage(data: data)
            } else {
                image = nil
            }
            guard let loaded = image else { return }
            let artwork = MPMediaItemArtwork(boundsSize: loaded.size) { _ in loaded }
            DispatchQueue.main.async {
                var current = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                current[MPMediaItemPropertyArtwork] = artwork
                MPNowPlayingInfoCenter.default().nowPlayingInfo = current
            }
        }
    }

    /**
     * Writes the current AVPlayer rate and elapsed time into the existing
     * nowPlayingInfo so the lock-screen scrubber and play/pause icon stay in sync.
     */
    static func syncNowPlayingState() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyPlaybackRate]        = player?.rate ?? 0.0
        let elapsed = player?.currentTime().seconds ?? 0.0
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsed.isNaN ? 0.0 : elapsed
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    // MARK: - Cleanup

    private static func removeObservers() {
        for observer in [completionObserver, failureObserver, interruptionObserver, routeChangeObserver] {
            if let o = observer { NotificationCenter.default.removeObserver(o) }
        }
        completionObserver  = nil
        failureObserver     = nil
        interruptionObserver = nil
        routeChangeObserver  = nil
    }

    /**
     * Fully tears down the player: stops progress timer, releases the AVPlayer,
     * removes all observers, deactivates the audio session, and clears nowPlayingInfo.
     */
    private static func resetPlayer() {
        stopProgressTimer()
        player?.pause()
        player     = nil
        playerItem = nil
        removeObservers()
        pausedByFocusLoss = false
        deactivateAudioSession()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    // MARK: - Progress Timer

    static func startProgressTimer(interval: Double) {
        stopProgressTimer()
        guard interval > 0, let p = player else { return }
        let cmInterval = CMTime(seconds: interval, preferredTimescale: 600)
        progressObserver = p.addPeriodicTimeObserver(forInterval: cmInterval, queue: .main) { _ in
            sendEvent("PlaybackProgressUpdated", statePayload())
        }
        progressObserverPlayer = p
    }

    static func stopProgressTimer() {
        if let observer = progressObserver {
            // removeTimeObserver must be called on the same AVPlayer that added it —
            // calling it on a different instance raises NSInvalidArgumentException.
            progressObserverPlayer?.removeTimeObserver(observer)
            progressObserver = nil
            progressObserverPlayer = nil
        }
    }

    // MARK: - Remote Command Centre

    static func setupRemoteCommands() {
        guard !remoteCommandsRegistered else { return }
        remoteCommandsRegistered = true

        let center = MPRemoteCommandCenter.shared()

        center.playCommand.isEnabled = true
        center.playCommand.addTarget { _ in
            guard player != nil else { return .noSuchContent }
            player?.play()
            syncNowPlayingState()
            let payload = statePayload()
            sendEvent("PlaybackResumed",    payload)
            sendEvent("RemotePlayReceived", payload)
            return .success
        }

        center.pauseCommand.isEnabled = true
        center.pauseCommand.addTarget { _ in
            guard player != nil else { return .noSuchContent }
            player?.pause()
            syncNowPlayingState()
            let payload = statePayload()
            sendEvent("PlaybackPaused",      payload)
            sendEvent("RemotePauseReceived", payload)
            return .success
        }

        center.togglePlayPauseCommand.isEnabled = true
        center.togglePlayPauseCommand.addTarget { _ in
            guard let p = player else { return .noSuchContent }
            let payload = statePayload()
            if p.rate > 0 {
                p.pause()
                sendEvent("PlaybackPaused",      payload)
                sendEvent("RemotePauseReceived", payload)
            } else {
                p.play()
                sendEvent("PlaybackResumed",    payload)
                sendEvent("RemotePlayReceived", payload)
            }
            syncNowPlayingState()
            return .success
        }

        center.nextTrackCommand.isEnabled = true
        center.nextTrackCommand.addTarget { _ in
            sendEvent("RemoteNextTrackReceived", statePayload())
            return .success
        }

        center.previousTrackCommand.isEnabled = true
        center.previousTrackCommand.addTarget { _ in
            sendEvent("RemotePreviousTrackReceived", statePayload())
            return .success
        }

        center.changePlaybackPositionCommand.isEnabled = true
        center.changePlaybackPositionCommand.addTarget { event in
            guard let posEvent = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            let seekTo   = posEvent.positionTime
            let from     = positionSeconds()
            let duration = durationSeconds()
            player?.seek(to: CMTime(seconds: seekTo, preferredTimescale: 1000)) { _ in
                syncNowPlayingState()
                sendEvent("RemoteSeekReceived", [
                    "position": from, "duration": duration, "url": currentURL, "seekTo": seekTo
                ])
            }
            return .success
        }

        center.stopCommand.isEnabled = true
        center.stopCommand.addTarget { _ in
            guard player != nil else { return .noSuchContent }
            let payload = statePayload()
            resetPlayer()
            sendEvent("PlaybackStopped",      payload)
            sendEvent("RemoteStopReceived",   payload)
            return .success
        }
    }

    // MARK: - Audio Session Observers (interruptions + route changes)

    private static func setupAudioSessionObservers() {
        guard interruptionObserver == nil else { return }

        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil, queue: .main
        ) { notification in
            guard let typeValue = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }

            switch type {
            case .began:
                guard player != nil, player?.rate ?? 0 > 0 else { return }
                pausedByFocusLoss = true
                player?.pause()
                syncNowPlayingState()
                let payload = statePayload()
                sendEvent("PlaybackPaused",          payload)
                sendEvent("AudioFocusLostTransient", payload)

            case .ended:
                let opts = AVAudioSession.InterruptionOptions(
                    rawValue: notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                )
                let payload = statePayload()
                sendEvent("AudioFocusGained", payload)
                if opts.contains(.shouldResume) && pausedByFocusLoss {
                    pausedByFocusLoss = false
                    try? AVAudioSession.sharedInstance().setActive(true)
                    player?.play()
                    syncNowPlayingState()
                    sendEvent("PlaybackResumed", payload)
                } else {
                    pausedByFocusLoss = false
                }

            @unknown default:
                break
            }
        }

        routeChangeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil, queue: .main
        ) { notification in
            guard let reasonValue = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue),
                  reason == .oldDeviceUnavailable,
                  player != nil, player?.rate ?? 0 > 0 else { return }

            player?.pause()
            syncNowPlayingState()
            let payload = statePayload()
            sendEvent("PlaybackPaused",          payload)
            sendEvent("AudioFocusLostTransient", payload)
        }
    }

    // MARK: - Bridge Functions

    /**
     * Shared helper that sets up the AVPlayer with a URL and metadata
     * but does NOT start playback. Used by both Load and Play.
     */
    private static func preparePlayer(urlString: String, url: URL, title: String?, artist: String?, album: String?, artwork: String?, duration: Double?, metadata: [String: Any]?) {
        // Stop progress timer BEFORE replacing the player — removeTimeObserver must be
        // called on the same AVPlayer instance that added it, otherwise it crashes.
        stopProgressTimer()

        // Clean up previous observers
        if let o = completionObserver { NotificationCenter.default.removeObserver(o) }
        if let o = failureObserver    { NotificationCenter.default.removeObserver(o) }

        activateAudioSession()
        setupRemoteCommands()
        setupAudioSessionObservers()

        currentURL = urlString

        // Store inline metadata if provided; otherwise preserve metadata from a prior setMetadata call.
        if title != nil {
            metaTitle         = title
            metaArtist        = artist
            metaAlbum         = album
            metaDuration      = duration
            metaArtworkSource = artwork
            metaMetadata      = metadata
        }

        playerItem = AVPlayerItem(url: url)
        player     = AVPlayer(playerItem: playerItem)

        // Apply stored metadata to lock screen / Control Center.
        refreshNowPlayingInfo()

        // Completion observer
        completionObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: playerItem, queue: .main
        ) { _ in
            stopProgressTimer()
            sendEvent("PlaybackCompleted", [
                "url": urlString, "duration": durationSeconds()
            ])
        }

        // Failure observer
        failureObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: playerItem, queue: .main
        ) { notification in
            stopProgressTimer()
            let error = (notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error)?
                .localizedDescription ?? "Unknown error"
            sendEvent("PlaybackFailed", ["url": urlString, "error": error])
        }
    }

    class Load: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let urlString = parameters["url"] as? String,
                  let url = URL(string: urlString) else {
                return BridgeResponse.error(code: "INVALID_PARAMETERS", message: "URL is required and must be valid.")
            }

            let title    = parameters["title"]    as? String
            let artist   = parameters["artist"]   as? String
            let album    = parameters["album"]    as? String
            let artwork  = parameters["artwork"]  as? String
            let duration = (parameters["duration"] as? NSNumber)?.doubleValue
            let metadata = parameters["metadata"] as? [String: Any]

            AudioFunctions.preparePlayer(urlString: urlString, url: url, title: title, artist: artist, album: album, artwork: artwork, duration: duration, metadata: metadata)

            // Do NOT call player?.play() — audio is loaded but paused.
            AudioFunctions.syncNowPlayingState()

            var loadedPayload: [String: Any] = ["url": urlString]
            if let t = title    { loadedPayload["title"]    = t }
            if let a = artist   { loadedPayload["artist"]   = a }
            if let a = album    { loadedPayload["album"]    = a }
            if let d = duration { loadedPayload["duration"] = d }
            if let m = metadata { loadedPayload["metadata"] = m }
            AudioFunctions.sendEvent("PlaybackLoaded", loadedPayload)

            return BridgeResponse.success(data: ["success": true])
        }
    }

    class Play: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let urlString = parameters["url"] as? String,
                  let url = URL(string: urlString) else {
                return BridgeResponse.error(code: "INVALID_PARAMETERS", message: "URL is required and must be valid.")
            }

            let title    = parameters["title"]    as? String
            let artist   = parameters["artist"]   as? String
            let album    = parameters["album"]    as? String
            let artwork  = parameters["artwork"]  as? String
            let duration = (parameters["duration"] as? NSNumber)?.doubleValue
            let metadata = parameters["metadata"] as? [String: Any]

            AudioFunctions.preparePlayer(urlString: urlString, url: url, title: title, artist: artist, album: album, artwork: artwork, duration: duration, metadata: metadata)

            AudioFunctions.player?.play()
            // Sync rate/elapsed after play() so the lock screen shows the correct state.
            AudioFunctions.syncNowPlayingState()
            AudioFunctions.startProgressTimer(interval: AudioFunctions.defaultProgressInterval)

            var startedPayload: [String: Any] = ["url": urlString]
            if let t = title    { startedPayload["title"]    = t }
            if let a = artist   { startedPayload["artist"]   = a }
            if let a = album    { startedPayload["album"]    = a }
            if let d = duration { startedPayload["duration"] = d }
            if let m = metadata { startedPayload["metadata"] = m }
            AudioFunctions.sendEvent("PlaybackStarted", startedPayload)

            return BridgeResponse.success(data: ["success": true])
        }
    }

    class Pause: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.player?.pause()
            AudioFunctions.syncNowPlayingState()
            AudioFunctions.startProgressTimer(interval: AudioFunctions.defaultProgressInterval)
            AudioFunctions.sendEvent("PlaybackPaused", AudioFunctions.statePayload())
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class Resume: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.activateAudioSession()
            AudioFunctions.player?.play()
            AudioFunctions.syncNowPlayingState()
            AudioFunctions.startProgressTimer(interval: AudioFunctions.defaultProgressInterval)
            AudioFunctions.sendEvent("PlaybackResumed", AudioFunctions.statePayload())
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class Stop: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let payload = AudioFunctions.statePayload()
            AudioFunctions.resetPlayer()
            AudioFunctions.sendEvent("PlaybackStopped", payload)
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class Seek: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let seconds  = (parameters["seconds"] as? NSNumber)?.doubleValue ?? 0.0
            let from     = AudioFunctions.positionSeconds()
            let duration = AudioFunctions.durationSeconds()
            AudioFunctions.player?.seek(to: CMTime(seconds: seconds, preferredTimescale: 600)) { _ in
                AudioFunctions.startProgressTimer(interval: AudioFunctions.defaultProgressInterval)
                AudioFunctions.sendEvent("PlaybackSeeked", [
                    "from": from, "to": seconds, "duration": duration, "url": AudioFunctions.currentURL
                ])
            }
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class SetVolume: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.player?.volume = (parameters["level"] as? NSNumber)?.floatValue ?? 1.0
            return BridgeResponse.success(data: ["success": true])
        }
    }

    class GetDuration: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            return BridgeResponse.success(data: ["duration": AudioFunctions.durationSeconds()])
        }
    }

    class GetCurrentPosition: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            return BridgeResponse.success(data: ["position": AudioFunctions.positionSeconds()])
        }
    }

    /**
     * Sets track metadata on MPNowPlayingInfoCenter for display on lock screens,
     * Control Center, Bluetooth devices, and CarPlay.
     *
     * Safe to call before Play — metadata is stored and applied when Play starts.
     * Safe to call after Play — lock screen updates immediately.
     */
    class SetMetadata: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let title = parameters["title"] as? String else {
                return BridgeResponse.error(code: "INVALID_PARAMETERS", message: "title is required.")
            }

            // Replace all stored metadata (nil fields are intentionally cleared).
            AudioFunctions.metaTitle         = title
            AudioFunctions.metaArtist        = parameters["artist"]   as? String
            AudioFunctions.metaAlbum         = parameters["album"]    as? String
            AudioFunctions.metaDuration      = (parameters["duration"] as? NSNumber)?.doubleValue
            AudioFunctions.metaArtworkSource = parameters["artwork"]  as? String

            // Ensure the audio session and remote commands are ready so the lock screen
            // shows metadata even before Play is called.
            DispatchQueue.main.async {
                AudioFunctions.activateAudioSession()
                AudioFunctions.setupRemoteCommands()
                AudioFunctions.refreshNowPlayingInfo()
            }

            return BridgeResponse.success(data: ["success": true])
        }
    }
}
