package com.pbb.livetv;

import java.util.Locale;

/** URL classification. Pure functions; the player and the model both rely on them. */
public final class StreamFormat {

    public static final String HLS = "hls";
    public static final String DASH = "dash";
    public static final String PROGRESSIVE = "progressive";

    private StreamFormat() {
    }

    public static String guess(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        if (lower.contains(".mpd") || lower.contains("format=mpd") || lower.contains("/dash/")) {
            return DASH;
        }
        if (lower.contains(".m3u8") || lower.contains("format=m3u8") || lower.contains("/hls/")) {
            return HLS;
        }
        return PROGRESSIVE;
    }

    public static boolean isDash(String url) {
        return DASH.equals(guess(url));
    }

    public static boolean isHls(String url) {
        return HLS.equals(guess(url));
    }

    public static String label(String format) {
        if (DASH.equals(format)) {
            return "MPEG-DASH";
        }
        if (HLS.equals(format)) {
            return "HLS";
        }
        return "Progressive";
    }
}
