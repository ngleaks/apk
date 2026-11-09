package com.pbb.streamtv;

import android.os.Build;
import android.view.View;

/** Tiny shims so the app targets API 35 while still installing on API 24 TVs. */
final class ApiCompat {

    private ApiCompat() {
    }

    static boolean isAtLeast(int api) {
        return Build.VERSION.SDK_INT >= api;
    }

    /** Immersive sticky mode: full screen, swipes revealed (there is no touch on a TV anyway). */
    static void goImmersive(View decor) {
        if (decor == null) {
            return;
        }
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN;
        if (isAtLeast(19)) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        decor.setSystemUiVisibility(flags);
    }

    /** prefers a hardware overlay surface (HDR-ish path, zero copy) when the API has it. */
    static boolean supportsHdrSwitch() {
        return isAtLeast(24);
    }
}
