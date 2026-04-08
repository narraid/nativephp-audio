<script>
    document.addEventListener('nativephp_event', (event) => {
        const { event: name, payload } = event.detail;

        const map = {
            'Narraid\\Audio\\Events\\PlaybackLoaded':            'audio-loaded',
            'Narraid\\Audio\\Events\\PlaybackStarted':           'audio-started',
            'Narraid\\Audio\\Events\\PlaybackPaused':            'audio-paused',
            'Narraid\\Audio\\Events\\PlaybackResumed':           'audio-resumed',
            'Narraid\\Audio\\Events\\PlaybackStopped':           'audio-stopped',
            'Narraid\\Audio\\Events\\PlaybackCompleted':         'audio-completed',
            'Narraid\\Audio\\Events\\PlaybackSeeked':            'audio-seeked',
            'Narraid\\Audio\\Events\\PlaybackFailed':            'audio-failed',
            'Narraid\\Audio\\Events\\PlaybackProgressUpdated':   'audio-progress-updated',
            'Narraid\\Audio\\Events\\PlaybackBuffering':         'audio-buffering',
            'Narraid\\Audio\\Events\\PlaybackReady':             'audio-ready',
            'Narraid\\Audio\\Events\\RemotePlayReceived':        'audio-remote-play',
            'Narraid\\Audio\\Events\\RemotePauseReceived':       'audio-remote-pause',
            'Narraid\\Audio\\Events\\RemoteNextTrackReceived':   'audio-remote-next',
            'Narraid\\Audio\\Events\\RemotePreviousTrackReceived': 'audio-remote-previous',
            'Narraid\\Audio\\Events\\RemoteSeekReceived':        'audio-remote-seek',
            'Narraid\\Audio\\Events\\RemoteStopReceived':        'audio-remote-stop',
            'Narraid\\Audio\\Events\\AudioFocusLost':            'audio-focus-lost',
            'Narraid\\Audio\\Events\\AudioFocusLostTransient':   'audio-focus-lost-transient',
            'Narraid\\Audio\\Events\\AudioFocusDucked':          'audio-focus-ducked',
            'Narraid\\Audio\\Events\\AudioFocusGained':          'audio-focus-gained',
            'Narraid\\Audio\\Events\\PlaylistSet':               'audio-playlist-set',
            'Narraid\\Audio\\Events\\PlaylistTrackChanged':      'audio-playlist-track-changed',
            'Narraid\\Audio\\Events\\PlaylistEnded':             'audio-playlist-ended',
            'Narraid\\Audio\\Events\\PlaylistRepeatModeChanged': 'audio-playlist-repeat-changed',
            'Narraid\\Audio\\Events\\PlaylistShuffleChanged':    'audio-playlist-shuffle-changed',
            'Narraid\\Audio\\Events\\SleepTimerExpired':         'audio-sleep-timer-expired',
        };

        const domEvent = map[name];
        if (domEvent) {
            window.dispatchEvent(new CustomEvent(domEvent, { detail: payload }));
        }
    });
</script>
