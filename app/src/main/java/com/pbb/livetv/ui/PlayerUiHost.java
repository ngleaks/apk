package com.pbb.livetv.ui;

import com.pbb.livetv.Camera;

/**
 * The tiny surface the activity exposes to diagnostics and instrumentation tests: which section is
 * selected and which camera is on screen. Kept separate so the rest of the app never reaches into
 * activity internals.
 */
public interface PlayerUiHost {

    Camera.Section currentSection();

    /** Camera id currently visible, or null when the ALL grid is showing. */
    default String currentCameraId() {
        return null;
    }
}
