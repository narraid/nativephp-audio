const audioPlayer = {
    load: async (url, { title = null, artist = null, album = null, artwork = null, duration = null } = {}) => {
        const params = { url };
        if (title !== null) params.title = title;
        if (artist !== null) params.artist = artist;
        if (album !== null) params.album = album;
        if (artwork !== null) params.artwork = artwork;
        if (duration !== null) params.duration = duration;
        return await window.nativephp.call('Audio.load', params);
    },
    play: async (url, { title = null, artist = null, album = null, artwork = null, duration = null } = {}) => {
        const params = { url };
        if (title !== null) params.title = title;
        if (artist !== null) params.artist = artist;
        if (album !== null) params.album = album;
        if (artwork !== null) params.artwork = artwork;
        if (duration !== null) params.duration = duration;
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
    getDuration: async () => {
        return await window.nativephp.call('Audio.getDuration');
    },
    getCurrentPosition: async () => {
        return await window.nativephp.call('Audio.getCurrentPosition');
    },
    setMetadata: async ({ title, artist = null, album = null, artwork = null, duration = null } = {}) => {
        const params = { title };
        if (artist !== null) params.artist = artist;
        if (album !== null) params.album = album;
        if (artwork !== null) params.artwork = artwork;
        if (duration !== null) params.duration = duration;
        return await window.nativephp.call('Audio.setMetadata', params);
    }
};

export default audioPlayer;
