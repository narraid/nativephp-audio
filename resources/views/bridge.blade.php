<script>
    document.addEventListener('nativephp_event', (event) => {
        const { event: name, payload } = event.detail;

        if (name === 'Theunwindfront\\Audio\\Events\\PlaybackStarted') {
            window.dispatchEvent(new CustomEvent('audio-started', { detail: payload }));
        }

        if (name === 'Theunwindfront\\Audio\\Events\\PlaybackPaused') {
            window.dispatchEvent(new CustomEvent('audio-paused', { detail: payload }));
        }

        if (name === 'Theunwindfront\\Audio\\Events\\PlaybackStopped') {
            window.dispatchEvent(new CustomEvent('audio-stopped', { detail: payload }));
        }

        if (name === 'Theunwindfront\\Audio\\Events\\PlaybackCompleted') {
            window.dispatchEvent(new CustomEvent('audio-completed', { detail: payload }));
        }

        if (name === 'Theunwindfront\\Audio\\Events\\PlaybackResumed') {
            window.dispatchEvent(new CustomEvent('audio-resumed', { detail: payload }));
        }

        if (name === 'Theunwindfront\\Audio\\Events\\PlaybackSeeked') {
            window.dispatchEvent(new CustomEvent('audio-seeked', { detail: payload }));
        }

        if (name === 'Theunwindfront\\Audio\\Events\\PlaybackFailed') {
            window.dispatchEvent(new CustomEvent('audio-failed', { detail: payload }));
        }

        if (name === 'Theunwindfront\\Audio\\Events\\RemotePlayReceived') {
            window.dispatchEvent(new CustomEvent('audio-remote-play', { detail: payload }));
        }

        if (name === 'Theunwindfront\\Audio\\Events\\RemotePauseReceived') {
            window.dispatchEvent(new CustomEvent('audio-remote-pause', { detail: payload }));
        }

        if (name === 'Theunwindfront\\Audio\\Events\\RemoteNextTrackReceived') {
            window.dispatchEvent(new CustomEvent('audio-remote-next', { detail: payload }));
        }

        if (name === 'Theunwindfront\\Audio\\Events\\RemotePreviousTrackReceived') {
            window.dispatchEvent(new CustomEvent('audio-remote-previous', { detail: payload }));
        }

        if (name === 'Theunwindfront\\Audio\\Events\\RemoteStopReceived') {
            window.dispatchEvent(new CustomEvent('audio-remote-stop', { detail: payload }));
        }

        if (name === 'Theunwindfront\\Audio\\Events\\AudioFocusLost') {
            window.dispatchEvent(new CustomEvent('audio-focus-lost', { detail: payload }));
        }

        if (name === 'Theunwindfront\\Audio\\Events\\AudioFocusLostTransient') {
            window.dispatchEvent(new CustomEvent('audio-focus-lost-transient', { detail: payload }));
        }

        if (name === 'Theunwindfront\\Audio\\Events\\AudioFocusDucked') {
            window.dispatchEvent(new CustomEvent('audio-focus-ducked', { detail: payload }));
        }

        if (name === 'Theunwindfront\\Audio\\Events\\AudioFocusGained') {
            window.dispatchEvent(new CustomEvent('audio-focus-gained', { detail: payload }));
        }
    });
</script>
