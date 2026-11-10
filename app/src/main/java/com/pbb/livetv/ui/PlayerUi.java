package com.pbb.livetv.ui;

import com.pbb.livetv.media.PlayerEngine;

/** UI-level playback states: engine states plus the two states only the catalogue can produce. */
public final class PlayerUi {

    public enum State {
        CONNECTING,
        BUFFERING,
        PLAYING,
        PAUSED,
        ENDED,
        ERROR,
        NO_STREAM,
        LICENCE_REQUIRED
    }

    private PlayerUi() {
    }

    public static State fromEngine(PlayerEngine.State state) {
        switch (state) {
            case CONNECTING:
                return State.CONNECTING;
            case BUFFERING:
                return State.BUFFERING;
            case PLAYING:
                return State.PLAYING;
            case PAUSED:
                return State.PAUSED;
            case ENDED:
                return State.ENDED;
            case ERROR:
                return State.ERROR;
            default:
                return State.CONNECTING;
        }
    }

    /** True while the user should see a progress indicator. */
    public static boolean busy(State state) {
        return state == State.CONNECTING || state == State.BUFFERING;
    }

    public static String label(State state) {
        switch (state) {
            case CONNECTING:
                return "CONNECTING";
            case BUFFERING:
                return "BUFFERING";
            case PLAYING:
                return "LIVE";
            case PAUSED:
                return "PAUSED";
            case ERROR:
                return "OFFLINE";
            case NO_STREAM:
                return "NO SIGNAL";
            case LICENCE_REQUIRED:
                return "LICENSED";
            default:
                return "";
        }
    }
}
