package com.pbb.streamtv;

import android.net.Uri;

import java.util.Locale;

/** URL classification used by the player. Pure functions, cheap to unit test. */
final class MediaUrls {

    private MediaUrls() {
    }

    /** True when the URL looks like something ExoPlayer can play directly. */
    static boolean isStream(String url) {
        if (url == null) {
            return false;
        }
        String path = url;
        try {
            Uri uri = Uri.parse(url);
            path = (uri.getPath() == null ? "" : uri.getPath());
            if (uri.getQueryParameter("url") != null) {
                path = path + uri.getQueryParameter("url");
            }
        } catch (Exception ignored) {
            // Fall back to matching on the raw string.
        }
        String lower = path.toLowerCase(Locale.US);
        return lower.contains(".m3u8")
                || lower.contains(".mpd")
                || lower.contains(".mp4")
                || lower.contains(".webm")
                || lower.contains(".mkv")
                || lower.contains(".m4a")
                || lower.contains(".mp3")
                || lower.contains("/hls/")
                || lower.contains("/dash/")
                || lower.contains("format=m3u8")
                || lower.contains("format=mpd")
                || lower.startsWith("rtmp")
                || lower.startsWith("rtsp");
    }

    /** Video containers Media3 should treat as progressive files rather than adaptive streams. */
    static boolean isProgressiveFile(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        return lower.contains(".mp4")
                || lower.contains(".webm")
                || lower.contains(".mkv")
                || lower.contains(".m4a")
                || lower.contains(".mp3");
    }

    /** Best effort: hide player chrome / autoplay when the page is one of the TV-friendly hosts. */
    static boolean isHls(String url) {
        return url != null && url.toLowerCase(Locale.US).contains(".m3u8");
    }
}
