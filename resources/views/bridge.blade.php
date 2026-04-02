<script>
    document.addEventListener('nativephp_event', (event) => {
        const { event: name, payload } = event.detail;

        if (name === 'Narraid\\Audio\\Events\\PlaybackStarted') {
            window.dispatchEvent(new CustomEvent('audio-started', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\PlaybackPaused') {
            window.dispatchEvent(new CustomEvent('audio-paused', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\PlaybackStopped') {
            window.dispatchEvent(new CustomEvent('audio-stopped', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\PlaybackCompleted') {
            window.dispatchEvent(new CustomEvent('audio-completed', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\PlaybackProgressUpdated') {
            window.dispatchEvent(new CustomEvent('audio-progress-updated', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\PlaybackResumed') {
            window.dispatchEvent(new CustomEvent('audio-resumed', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\PlaybackSeeked') {
            window.dispatchEvent(new CustomEvent('audio-seeked', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\PlaybackFailed') {
            window.dispatchEvent(new CustomEvent('audio-failed', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\PlaybackLoaded') {
            window.dispatchEvent(new CustomEvent('audio-loaded', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\RemotePlayReceived') {
            window.dispatchEvent(new CustomEvent('audio-remote-play', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\RemotePauseReceived') {
            window.dispatchEvent(new CustomEvent('audio-remote-pause', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\RemoteNextTrackReceived') {
            window.dispatchEvent(new CustomEvent('audio-remote-next', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\RemotePreviousTrackReceived') {
            window.dispatchEvent(new CustomEvent('audio-remote-previous', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\RemoteSeekReceived') {
            window.dispatchEvent(new CustomEvent('audio-remote-seek', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\RemoteStopReceived') {
            window.dispatchEvent(new CustomEvent('audio-remote-stop', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\AudioFocusLost') {
            window.dispatchEvent(new CustomEvent('audio-focus-lost', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\AudioFocusLostTransient') {
            window.dispatchEvent(new CustomEvent('audio-focus-lost-transient', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\AudioFocusDucked') {
            window.dispatchEvent(new CustomEvent('audio-focus-ducked', { detail: payload }));
        }

        if (name === 'Narraid\\Audio\\Events\\AudioFocusGained') {
            window.dispatchEvent(new CustomEvent('audio-focus-gained', { detail: payload }));
        }
    });
</script>
