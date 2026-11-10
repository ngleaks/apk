package com.pbb.livetv.media;

import android.view.Surface;

/**
 * The complete surface area the UI needs from a media engine.
 *
 * <p>The app ships a framework-only implementation ({@link HlsEngine}, {@link ProgressiveEngine})
 * because the offline build cannot fetch Media3. The interface is deliberately Media3-shaped
 * (state machine + surface + listener) so swapping in {@code androidx.media3.exoplayer.ExoPlayer}
 * later is a one-class change: {@code Media3Engine implements PlayerEngine}.
 */
public interface PlayerEngine {

    enum State {
        IDLE,
        CONNECTING,
        BUFFERING,
        PLAYING,
        PAUSED,
        ENDED,
        ERROR
    }

    interface Listener {
        /**
         * @param state  new state
         * @param detail short human readable reason, e.g. "HTTP 403", "offline"
         */
        void onEngineState(PlayerEngine engine, State state, String detail);

        /** Video size became known (or changed) – lets the UI lock the tile aspect ratio. */
        void onVideoSize(PlayerEngine engine, int width, int height);
    }

    /** Start (or restart) playback of {@code url}. Safe to call repeatedly. */
    void prepare(String url);

    void setSurface(Surface surface);

    /** 0..1. Grid tiles stay muted, the focused/fullscreen camera is audible. */
    void setVolume(float volume);

    void play();

    void pause();

    void retry();

    boolean isPlaying();

    boolean isLive();

    State state();

    /** Tear down threads, MediaPlayer instances and cached surfaces. */
    void release();

    interface Factory {
        PlayerEngine create(String url, Listener listener);
    }
}
