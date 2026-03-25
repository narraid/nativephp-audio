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

    /**
     * Starts playback of a remote or local audio URL.
     *
     * Expected parameters:
     *  - `url` (String, required) – the URI of the audio resource to play.
     *
     * On success fires [PlaybackStarted]. On completion fires [PlaybackCompleted].
     * Returns a BridgeResponse error when `url` is missing or invalid.
     */
    class Play: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let urlString = parameters["url"] as? String,
                  let url = URL(string: urlString) else {
                return BridgeResponse.error(code: "INVALID_PARAMETERS", message: "URL is required and must be valid.")
            }

            // Cleanup previous observer if any
            if let observer = AudioFunctions.completionObserver {
                NotificationCenter.default.removeObserver(observer)
            }

            // Configure the audio session for background playback before creating the player.
            // .playback category allows audio to continue when the screen locks or the
            // app moves to the background. This must be set before AVPlayer starts.
            let session = AVAudioSession.sharedInstance()
            try? session.setCategory(.playback, mode: .default)
            try? session.setActive(true)

            AudioFunctions.playerItem = AVPlayerItem(url: url)
            AudioFunctions.player = AVPlayer(playerItem: AudioFunctions.playerItem)

            // Add completion observer
            AudioFunctions.completionObserver = NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: AudioFunctions.playerItem,
                queue: .main
            ) { _ in
                LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackCompleted", ["url": urlString])
            }

            AudioFunctions.player?.play()
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackStarted", ["url": urlString])

            return BridgeResponse.success(data: ["success": true])
        }
    }

    /**
     * Pauses the currently playing audio without releasing the AVPlayer.
     * Fires [PlaybackPaused] after pausing.
     */
    class Pause: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.player?.pause()
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackPaused", [:])
            return BridgeResponse.success(data: ["success": true])
        }
    }

    /**
     * Resumes a previously paused AVPlayer.
     * Fires [PlaybackStarted] (reusing the started event) after resuming.
     */
    class Resume: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.player?.play()
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackStarted", [:])
            return BridgeResponse.success(data: ["success": true])
        }
    }

    /**
     * Stops playback and fully releases the AVPlayer state.
     * After this call player is nil and a new Play call is required to resume audio.
     * Fires [PlaybackStopped].
     */
    class Stop: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            AudioFunctions.player?.pause()
            AudioFunctions.player = nil
            AudioFunctions.playerItem = nil
            if let observer = AudioFunctions.completionObserver {
                NotificationCenter.default.removeObserver(observer)
                AudioFunctions.completionObserver = nil
            }
            // Deactivate the session so other apps can resume their audio
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            LaravelBridge.shared.send?("Theunwindfront\\Audio\\Events\\PlaybackStopped", [:])
            return BridgeResponse.success(data: ["success": true])
        }
    }

    /**
     * Seeks the playback position to the given number of seconds.
     *
     * Expected parameters:
     *  - `seconds` (Number, optional, default 0) – target position in seconds.
     */
    class Seek: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let seconds = (parameters["seconds"] as? NSNumber)?.doubleValue ?? 0.0
            let time = CMTime(seconds: seconds, preferredTimescale: 600)
            AudioFunctions.player?.seek(to: time)
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

            // Preserve elapsed time so the scrubber stays accurate
            let elapsed = AudioFunctions.player?.currentTime().seconds ?? 0.0
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsed.isNaN ? 0.0 : elapsed
            info[MPNowPlayingInfoPropertyPlaybackRate] = AudioFunctions.player?.rate ?? 0.0

            if let artworkString = parameters["artwork"] as? String {
                // Try local file path first, then remote URL
                if let image = UIImage(contentsOfFile: artworkString) {
                    info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                } else if let url = URL(string: artworkString),
                          let data = try? Data(contentsOf: url),
                          let image = UIImage(data: data) {
                    info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                }
            }

            MPNowPlayingInfoCenter.default().nowPlayingInfo = info

            // Activate the audio session so remote-control events are delivered
            try? AVAudioSession.sharedInstance().setActive(true)

            return BridgeResponse.success(data: ["success": true])
        }
    }
}
