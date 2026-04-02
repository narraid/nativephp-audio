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
    private static var backgroundObserversRegistered = false
    private static var pausedByFocusLoss = false

    // MARK: - Playlist State

    private static var playlist: [[String: Any]] = []
    private static var playlistIndex: Int = -1
    private static var repeatMode: String = "none"
    private static var shuffleMode: Bool = false
    private static var shuffledOrder: [Int] = []

    // MARK: - Background Event Queue

    private static var isInBackground = false
    private static var pendingEvents: [[String: Any]] = []

    // MARK: - Progress Defaults

    private static let defaultProgressInterval: Double = 10.0

    // MARK: - Event Helpers

    private static let eventPrefix = "Narraid\\Audio\\Events\\"

    private static func sendEvent(_ name: String, _ payload: [String: Any]) {
        guard !isInBackground else {
            pendingEvents.append(["event": name, "payload": payload])
            return
        }
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
            AudioFunctions.setupBackgroundObservers()
        }
    }

    private static func setupBackgroundObservers() {
        guard !backgroundObserversRegistered else { return }
        backgroundObserversRegistered = true

        NotificationCenter.default.addObserver(
            forName: UIApplication.willResignActiveNotification,
            object: nil, queue: .main
        ) { _ in
            isInBackground = true
        }

        NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil, queue: .main
        ) { _ in
            isInBackground = false
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

    // MARK: - Playlist Navigation

    private static func effectivePlaylistIndex(for logicalIndex: Int) -> Int {
        guard !shuffledOrder.isEmpty, logicalIndex < shuffledOrder.count else { return logicalIndex }
        return shuffledOrder[logicalIndex]
    }

    private static func playTrackAt(index: Int) {
        guard index >= 0, index < playlist.count else { return }
        playlistIndex = index
        let track = playlist[effectivePlaylistIndex(for: index)]

        guard let urlString = track["url"] as? String,
              let url = URL(string: urlString) else { return }

        let title    = track["title"]    as? String
        let artist   = track["artist"]   as? String
        let album    = track["album"]    as? String
        let artwork  = track["artwork"]  as? String
        let duration = (track["duration"] as? NSNumber)?.doubleValue
        let metadata = track["metadata"] as? [String: Any]

        preparePlayer(urlString: urlString, url: url, title: title, artist: artist,
                      album: album, artwork: artwork, duration: duration, metadata: metadata)
        player?.play()
        syncNowPlayingState()
        startProgressTimer(interval: defaultProgressInterval)

        var trackChangedPayload: [String: Any] = ["index": index, "total": playlist.count, "url": urlString]
        if let t = title    { trackChangedPayload["title"]    = t }
        if let a = artist   { trackChangedPayload["artist"]   = a }
        if let a = album    { trackChangedPayload["album"]    = a }
        if let d = duration { trackChangedPayload["duration"] = d }
        if let m = metadata { trackChangedPayload["metadata"] = m }
        sendEvent("PlaylistTrackChanged", trackChangedPayload)

        var startedPayload: [String: Any] = ["url": urlString]
        if let t = title    { startedPayload["title"]    = t }
        if let a = artist   { startedPayload["artist"]   = a }
        if let a = album    { startedPayload["album"]    = a }
        if let d = duration { startedPayload["duration"] = d }
        if let m = metadata { startedPayload["metadata"] = m }
        sendEvent("PlaybackStarted", startedPayload)
    }

    private static func advancePlaylist() {
        guard !playlist.isEmpty else { return }
        switch repeatMode {
        case "one":
            playTrackAt(index: playlistIndex)
        case "all":
            playTrackAt(index: (playlistIndex + 1) % playlist.count)
        default:
            let next = playlistIndex + 1
            if next < playlist.count {
                playTrackAt(index: next)
            } else {
                sendEvent("PlaylistEnded", ["total": playlist.count])
            }
        }
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
            if !playlist.isEmpty {
                let nextIndex = repeatMode == "all"
                    ? (playlistIndex + 1) % playlist.count
                    : min(playlistIndex + 1, playlist.count - 1)
                playTrackAt(index: nextIndex)
            }
            sendEvent("RemoteNextTrackReceived", statePayload())
            return .success
        }

        center.previousTrackCommand.isEnabled = true
        center.previousTrackCommand.addTarget { _ in
            if !playlist.isEmpty {
                playTrackAt(index: max(0, playlistIndex - 1))
            }
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
            advancePlaylist()
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

            AudioFunctions.playlist      = []
            AudioFunctions.playlistIndex = -1

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

            AudioFunctions.playlist      = []
            AudioFunctions.playlistIndex = -1

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
     * Returns the full current playback state from the native layer.
     * Used by PHP to reconcile state after a runtime restart (e.g. OS killed PHP in background).
     *
     * Returns: url, position, duration, isPlaying, title, artist, album, artwork, hasPlayer
     */
    class GetState: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            var state: [String: Any] = [
                "url":        AudioFunctions.currentURL,
                "position":   AudioFunctions.positionSeconds(),
                "duration":   AudioFunctions.durationSeconds(),
                "isPlaying":  AudioFunctions.player?.rate ?? 0 > 0,
                "hasPlayer":  AudioFunctions.player != nil,
            ]
            if let t = AudioFunctions.metaTitle         { state["title"]    = t }
            if let a = AudioFunctions.metaArtist         { state["artist"]   = a }
            if let a = AudioFunctions.metaAlbum          { state["album"]    = a }
            if let d = AudioFunctions.metaDuration       { state["duration"] = d }
            if let w = AudioFunctions.metaArtworkSource  { state["artwork"]  = w }
            if let m = AudioFunctions.metaMetadata  { state["metadata"]  = m }

            return BridgeResponse.success(data: state)
        }
    }

    /**
     * Returns all events that were queued while the app was in the background,
     * then clears the queue. Call this when the app returns to the foreground
     * to replay missed events through Livewire.
     *
     * Each entry in the returned array has the shape:
     *   { "event": "EventName", "payload": { ... } }
     */
    class DrainEvents: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let events = AudioFunctions.pendingEvents
            AudioFunctions.pendingEvents = []
            return BridgeResponse.success(data: ["events": events])
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

    /**
     * Sets the playlist queue natively so tracks auto-advance in the background.
     * Each item must have a "url" key; title/artist/album/artwork/duration/metadata are optional.
     * Pass autoPlay: false to load the queue without starting playback immediately.
     * Pass startIndex to begin at a specific track (default: 0).
     */
    class SetPlaylist: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let items = parameters["items"] as? [[String: Any]], !items.isEmpty else {
                return BridgeResponse.error(code: "INVALID_PARAMETERS", message: "items must be a non-empty array of track objects.")
            }

            let autoPlay   = parameters["autoPlay"]   as? Bool ?? true
            let startIndex = (parameters["startIndex"] as? NSNumber)?.intValue ?? 0

            AudioFunctions.playlist      = items
            AudioFunctions.playlistIndex = -1

            if AudioFunctions.shuffleMode {
                AudioFunctions.shuffledOrder = Array(0..<items.count).shuffled()
            } else {
                AudioFunctions.shuffledOrder = []
            }

            AudioFunctions.sendEvent("PlaylistSet", ["total": items.count])

            if autoPlay {
                AudioFunctions.playTrackAt(index: startIndex)
            }

            return BridgeResponse.success(data: ["success": true])
        }
    }

    /**
     * Skips to the next track in the active playlist.
     */
    class NextTrack: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard !AudioFunctions.playlist.isEmpty else {
                return BridgeResponse.error(code: "NO_PLAYLIST", message: "No playlist is active.")
            }
            let nextIndex = AudioFunctions.repeatMode == "all"
                ? (AudioFunctions.playlistIndex + 1) % AudioFunctions.playlist.count
                : min(AudioFunctions.playlistIndex + 1, AudioFunctions.playlist.count - 1)
            AudioFunctions.playTrackAt(index: nextIndex)
            return BridgeResponse.success(data: ["success": true])
        }
    }

    /**
     * Skips to the previous track in the active playlist.
     */
    class PreviousTrack: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard !AudioFunctions.playlist.isEmpty else {
                return BridgeResponse.error(code: "NO_PLAYLIST", message: "No playlist is active.")
            }
            AudioFunctions.playTrackAt(index: max(0, AudioFunctions.playlistIndex - 1))
            return BridgeResponse.success(data: ["success": true])
        }
    }

    /**
     * Returns the current playlist queue and playback state.
     */
    class GetPlaylist: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            return BridgeResponse.success(data: [
                "items":       AudioFunctions.playlist,
                "index":       AudioFunctions.playlistIndex,
                "total":       AudioFunctions.playlist.count,
                "repeatMode":  AudioFunctions.repeatMode,
                "shuffleMode": AudioFunctions.shuffleMode,
            ])
        }
    }

    /**
     * Sets the repeat mode: "none" (default), "one" (repeat current track), "all" (repeat playlist).
     */
    class SetRepeatMode: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let mode = parameters["mode"] as? String ?? "none"
            guard ["none", "one", "all"].contains(mode) else {
                return BridgeResponse.error(code: "INVALID_PARAMETERS", message: "mode must be 'none', 'one', or 'all'.")
            }
            AudioFunctions.repeatMode = mode
            return BridgeResponse.success(data: ["success": true])
        }
    }

    /**
     * Enables or disables shuffle mode. When enabled, a new random play order is generated.
     */
    class SetShuffleMode: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let shuffle = parameters["shuffle"] as? Bool ?? false
            AudioFunctions.shuffleMode = shuffle
            if shuffle, !AudioFunctions.playlist.isEmpty {
                AudioFunctions.shuffledOrder = Array(0..<AudioFunctions.playlist.count).shuffled()
            } else {
                AudioFunctions.shuffledOrder = []
            }
            return BridgeResponse.success(data: ["success": true])
        }
    }
}
