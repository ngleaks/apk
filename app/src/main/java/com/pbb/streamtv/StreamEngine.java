package com.pbb.streamtv;

import android.view.Surface;

/**
 * The little bit of player behaviour the activity needs, so the HLS segment player and the
 * single-file MediaPlayer path can be swapped without the activity knowing which is which.
 */
interface StreamEngine {

    /** What the UI renders. */
    enum State {
        IDLE,
        BUFFERING,
        PLAYING,
        PAUSED,
        ENDED,
        ERROR
    }

    interface Listener {
        void onState(State state, String detail);
    }

    void setSurface(Surface surface);

    void prepare(String url);

    void play();

    void pause();

    boolean isPlaying();

    /** Current position in ms, or 0 for live streams. */
    long positionMs();

    /** Duration in ms, or 0 when unknown / live. */
    long durationMs();

    /**
     * Seek to a position. Returns false when the stream is live and cannot seek, which the activity
     * turns into a "LIVE" toast instead of pretending the seek happened.
     */
    boolean seekTo(long positionMs);

    boolean isLive();

    /** Stop playback, keep the instance reusable. */
    void stop();

    /** Stop and free everything. */
    void release();
}
