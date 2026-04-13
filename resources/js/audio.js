const audioPlayer = {
    load: async (url, { title = null, artist = null, album = null, artwork = null, duration = null, metadata = null } = {}) => {
        const params = { url };
        if (title !== null)    params.title    = title;
        if (artist !== null)   params.artist   = artist;
        if (album !== null)    params.album    = album;
        if (artwork !== null)  params.artwork  = artwork;
        if (duration !== null) params.duration = duration;
        if (metadata !== null) params.metadata = metadata;
        return await window.nativephp.call('Audio.load', params);
    },

    play: async (url, { title = null, artist = null, album = null, artwork = null, duration = null, metadata = null } = {}) => {
        const params = { url };
        if (title !== null)    params.title    = title;
        if (artist !== null)   params.artist   = artist;
        if (album !== null)    params.album    = album;
        if (artwork !== null)  params.artwork  = artwork;
        if (duration !== null) params.duration = duration;
        if (metadata !== null) params.metadata = metadata;
        return await window.nativephp.call('Audio.play', params);
    },

    pause: async () => {
        return await window.nativephp.call('Audio.pause');
    },

    resume: async () => {
        return await window.nativephp.call('Audio.resume');
    },

    stop: async () => {
        return await window.nativephp.call('Audio.stop');
    },

    seek: async (seconds) => {
        return await window.nativephp.call('Audio.seek', { seconds });
    },

    setVolume: async (level) => {
        return await window.nativephp.call('Audio.setVolume', { level });
    },

    setPlaybackRate: async (rate) => {
        return await window.nativephp.call('Audio.setPlaybackRate', { rate });
    },

    setProgressInterval: async (seconds) => {
        return await window.nativephp.call('Audio.setProgressInterval', { seconds });
    },

    getDuration: async () => {
        return await window.nativephp.call('Audio.getDuration');
    },

    getCurrentPosition: async () => {
        return await window.nativephp.call('Audio.getCurrentPosition');
    },

    getState: async () => {
        return await window.nativephp.call('Audio.getState');
    },

    drainEvents: async () => {
        return await window.nativephp.call('Audio.drainEvents');
    },

    setMetadata: async ({ title, artist = null, album = null, artwork = null, duration = null } = {}) => {
        const params = { title };
        if (artist !== null)   params.artist   = artist;
        if (album !== null)    params.album    = album;
        if (artwork !== null)  params.artwork  = artwork;
        if (duration !== null) params.duration = duration;
        return await window.nativephp.call('Audio.setMetadata', params);
    },

    setPlaylist: async (items, { autoPlay = true, startIndex = 0 } = {}) => {
        return await window.nativephp.call('Audio.setPlaylist', { items, autoPlay, startIndex });
    },

    nextTrack: async () => {
        return await window.nativephp.call('Audio.nextTrack');
    },

    previousTrack: async () => {
        return await window.nativephp.call('Audio.previousTrack');
    },

    skipTrack: async (index, startSeconds = 0) => {
        return await window.nativephp.call('Audio.skipTrack', { index, startSeconds });
    },

    getTrack: async (index) => {
        return await window.nativephp.call('Audio.getTrack', { index });
    },

    getActiveTrack: async () => {
        return await window.nativephp.call('Audio.getActiveTrack');
    },

    getActiveTrackIndex: async () => {
        return await window.nativephp.call('Audio.getActiveTrackIndex');
    },

    getPlaylist: async () => {
        return await window.nativephp.call('Audio.getPlaylist');
    },

    setRepeatMode: async (mode) => {
        return await window.nativephp.call('Audio.setRepeatMode', { mode });
    },

    setShuffleMode: async (shuffle) => {
        return await window.nativephp.call('Audio.setShuffleMode', { shuffle });
    },

    setSleepTimer: async (minutes) => {
        return await window.nativephp.call('Audio.setSleepTimer', { minutes });
    },

    cancelSleepTimer: async () => {
        return await window.nativephp.call('Audio.cancelSleepTimer');
    },

    appendTrack: async (track) => {
        return await window.nativephp.call('Audio.appendTrack', { track });
    },

    removeTrack: async (index) => {
        return await window.nativephp.call('Audio.removeTrack', { index });
    },
};

export default audioPlayer;
