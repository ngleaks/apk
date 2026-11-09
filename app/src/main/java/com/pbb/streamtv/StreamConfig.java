package com.pbb.streamtv;

import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.Nullable;

/**
 * The single source of truth for "what are we watching".
 *
 * <p>Replace {@link #MANIFEST} with the stream you own, or point {@link #LANDING_PAGE} at your own
 * site. Nothing here loads third-party HTML into a WebView: the optional landing page is only read
 * for links, and the link the viewer picks is resolved straight into the native player or the
 * system browser.
 */
final class StreamConfig {

    /** Optional HTML overview page. Links found on it are extracted, never rendered in-app. */
    static final String LANDING_PAGE = "https://pbblivestream.vercel.app/";

    /**
     * Streams offered on the channel list. Leave empty to rely purely on {@link #LANDING_PAGE}
     * link discovery, or fill it in with URLs you are allowed to rebroadcast.
     */
    static final Stream[] MANIFEST = new Stream[]{
            new Stream("Main camera", "", ""),
            new Stream("Dining area", "", ""),
            new Stream("External cam", "", ""),
    };

    /** Resume the last channel automatically instead of showing the channel list on every launch. */
    static final boolean AUTO_RESUME = true;

    /** Deep link: {@code pbblivetv://play?url=<encoded stream>&title=<label>}. */
    static final String DEEPLINK_SCHEME = "pbblivetv";

    static final String PREFS = "pbb_tv";
    static final String KEY_LAST_URL = "last_url";
    static final String KEY_LAST_TITLE = "last_title";

    private StreamConfig() {
    }

    static final class Stream {
        final String title;
        final String hlsUrl;
        final String fallbackUrl;

        Stream(String title, String hlsUrl, String fallbackUrl) {
            this.title = title;
            this.hlsUrl = hlsUrl;
            this.fallbackUrl = fallbackUrl;
        }

        boolean isEmpty() {
            return hlsUrl.isEmpty() && fallbackUrl.isEmpty();
        }
    }

    static boolean isAutoResume() {
        return AUTO_RESUME;
    }

    static final class PlayRequest {
        final String url;
        final String title;
        final boolean fromDeepLink;

        PlayRequest(String url, String title, boolean fromDeepLink) {
            this.url = url;
            this.title = title;
            this.fromDeepLink = fromDeepLink;
        }

        static PlayRequest none() {
            return new PlayRequest("", "", false);
        }

        boolean isEmpty() {
            return url.isEmpty();
        }
    }

    /** Resolve what to open: deep link > saved resume point > nothing. */
    static PlayRequest resolve(@Nullable Uri data, SharedPreferences prefs) {
        if (data != null && DEEPLINK_SCHEME.equals(data.getScheme())) {
            String url = data.getQueryParameter("url");
            String title = data.getQueryParameter("title");
            if (url != null && !url.isEmpty()) {
                return new PlayRequest(url, title == null ? "PBB Live" : title, true);
            }
        }
        String saved = prefs.getString(KEY_LAST_URL, "");
        if (saved != null && !saved.isEmpty()) {
            String title = prefs.getString(KEY_LAST_TITLE, "PBB Live");
            return new PlayRequest(saved, title == null ? "PBB Live" : title, false);
        }
        return PlayRequest.none();
    }

    static void remember(SharedPreferences prefs, String url, String title) {
        if (url == null || url.isEmpty()) {
            return;
        }
        prefs.edit().putString(KEY_LAST_URL, url).putString(KEY_LAST_TITLE, title).apply();
    }

    /** Human readable label for a stream URL, used when the page gives us no title. */
    static String titleFor(String url, String fallback) {
        if (fallback != null && !fallback.trim().isEmpty()) {
            return fallback.trim();
        }
        try {
            String host = Uri.parse(url).getHost();
            if (host != null && !host.isEmpty()) {
                return host.startsWith("www.") ? host.substring(4) : host;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "PBB Live";
    }
}
