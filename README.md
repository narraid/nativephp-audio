Changes Made

Issues Found & Fixed

PHP (src/Audio.php) — 672 → 288 lines
- Extracted call() and query() helpers — eliminated 7-line boilerplate repeated in every method
- Extracted trackParams() — eliminated duplicate 8-param signature shared between load() and play()
- Removed all verbose @param docblocks that just repeated parameter names

Facade (src/Facades/Audio.php)
- Added missing @method entries: skipTrack, getTrack, getActiveTrack, getActiveTrackIndex
- Fixed setPlaylist missing $startSeconds param, nextTrack/previousTrack missing $startSeconds

PHP Event (PlaybackProgressUpdated.php)
- Added buffered field (aligns with RNTP's Progress type)

Android (AudioFunctions.kt) — biggest structural win
- Extracted setupSingleTrack(activity, params, autoPlay) — replaced two near-identical 80-line Load.execute() and Play.execute() with 6 lines each
- Fixed seekTo() Int overflow bug — seekToMs(Long) now uses seekTo(Long, Int) on API 26+
- Added bufferingPercent via setOnBufferingUpdateListener + bufferedSeconds() helper
- Added buffered to statePayload() and GetState
- New bridge classes: Reset, SeekBy, GetProgress, MoveTrack, RemoveUpcomingTracks

iOS (AudioFunctions.swift)
- Added bufferedSeconds() reading loadedTimeRanges
- Added buffered to statePayload() and GetState
- Fixed player?.rate ?? 0 > 0 precedence ambiguity → explicit (player?.rate ?? 0) > 0
- New bridge classes: Reset, SeekBy, GetProgress, MoveTrack, RemoveUpcomingTracks

JS (audio.js) — bug fixes
- setPlaylist now passes startSeconds
- nextTrack/previousTrack now accept and pass startSeconds
- setMetadata now passes metadata
- Added clip to load/play (was missing)
- All new methods added

nativephp.json — registered all 5 new bridge functions

Result: All 4 layers (PHP, Android, iOS, JS) stay in sync at exactly 33 methods.             