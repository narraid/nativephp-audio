const audioPlayer = {
    load: async (url, { title = null, artist = null, album = null, artwork = null, duration = null, clip = null, metadata = null } = {}) => {
        const params = { url };
        if (title !== null)    params.title    = title;
        if (artist !== null)   params.artist   = artist;
        if (album !== null)    params.album    = album;
        if (artwork !== null)  params.artwork  = artwork;
        if (duration !== null) params.duration = duration;
        if (clip !== null)     params.clip     = clip;
        if (metadata !== null) params.metadata = metadata;
        return await window.nativephp.call('Audio.load', params);
    },

    play: async (url, { title = null, artist = null, album = null, artwork = null, duration = null, clip = null, metadata = null } = {}) => {
        const params = { url };
        if (title !== null)    params.title    = title;
        if (artist !== null)   params.artist   = artist;
        if (album !== null)    params.album    = album;
        if (artwork !== null)  params.artwork  = artwork;
        if (duration !== null) params.duration = duration;
        if (clip !== null)     params.clip     = clip;
        if (metadata !== null) params.metadata = metadata;
        return await window.nativephp.call('Audio.play', params);
    },

    pause:  async () => await window.nativephp.call('Audio.pause'),
    resume: async () => await window.nativephp.call('Audio.resume'),
    stop:   async () => await window.nativephp.call('Audio.stop'),
    reset:  async () => await window.nativephp.call('Audio.reset'),

    seek:   async (seconds) => await window.nativephp.call('Audio.seek',   { seconds }),
    seekBy: async (seconds) => await window.nativephp.call('Audio.seekBy', { seconds }),

    setVolume:           async (level)   => await window.nativephp.call('Audio.setVolume',           { level }),
    setPlaybackRate:     async (rate)    => await window.nativephp.call('Audio.setPlaybackRate',     { rate }),
    setProgressInterval: async (seconds) => await window.nativephp.call('Audio.setProgressInterval', { seconds }),

    getDuration:        async () => await window.nativephp.call('Audio.getDuration'),
    getCurrentPosition: async () => await window.nativephp.call('Audio.getCurrentPosition'),
    getProgress:        async () => await window.nativephp.call('Audio.getProgress'),
    getState:           async () => await window.nativephp.call('Audio.getState'),
    drainEvents:        async () => await window.nativephp.call('Audio.drainEvents'),

    setMetadata: async ({ title, artist = null, album = null, artwork = null, duration = null, metadata = null } = {}) => {
        const params = { title };
        if (artist !== null)   params.artist   = artist;
        if (album !== null)    params.album    = album;
        if (artwork !== null)  params.artwork  = artwork;
        if (duration !== null) params.duration = duration;
        if (metadata !== null) params.metadata = metadata;
        return await window.nativephp.call('Audio.setMetadata', params);
    },

    setPlaylist: async (items, { autoPlay = true, startIndex = 0, startSeconds = 0 } = {}) =>
        await window.nativephp.call('Audio.setPlaylist', { items, autoPlay, startIndex, startSeconds }),

    nextTrack:     async (startSeconds = 0) => await window.nativephp.call('Audio.nextTrack',     { startSeconds }),
    previousTrack: async (startSeconds = 0) => await window.nativephp.call('Audio.previousTrack', { startSeconds }),
    skipTrack:     async (index, startSeconds = 0) => await window.nativephp.call('Audio.skipTrack', { index, startSeconds }),

    moveTrack:           async (fromIndex, toIndex) => await window.nativephp.call('Audio.moveTrack', { fromIndex, toIndex }),
    appendTrack:         async (track)  => await window.nativephp.call('Audio.appendTrack',         { track }),
    removeTrack:         async (index)  => await window.nativephp.call('Audio.removeTrack',         { index }),
    removeUpcomingTracks: async ()      => await window.nativephp.call('Audio.removeUpcomingTracks'),

    getTrack:            async (index)  => await window.nativephp.call('Audio.getTrack',            { index }),
    getActiveTrack:      async ()       => await window.nativephp.call('Audio.getActiveTrack'),
    getActiveTrackIndex: async ()       => await window.nativephp.call('Audio.getActiveTrackIndex'),
    getPlaylist:         async ()       => await window.nativephp.call('Audio.getPlaylist'),

    setRepeatMode:  async (mode)    => await window.nativephp.call('Audio.setRepeatMode',  { mode }),
    setShuffleMode: async (shuffle) => await window.nativephp.call('Audio.setShuffleMode', { shuffle }),

    setSleepTimer:    async (minutes) => await window.nativephp.call('Audio.setSleepTimer',    { minutes }),
    cancelSleepTimer: async ()        => await window.nativephp.call('Audio.cancelSleepTimer'),
};

export default audioPlayer;
