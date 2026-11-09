package com.pbb.streamtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.net.Uri;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP headers used for every request the app makes: playlist fetches, segments and progressive
 * files alike.
 *
 * <p>Most legitimate CDNs are behind a referer/origin check (a stream that answers 403 in a bare
 * player but plays fine in a page). Rather than shipping a browser to fake that, the app sends the
 * two headers a player is allowed to send and lets the viewer set them once:
 *
 * <pre>Referer: https://www.iwanttfc.com/</pre>
 *
 * <p>Nothing here mints a session, a token or a DRM key — it only forwards what the viewer already
 * has.
 */
final class Net {

    static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    static final String KEY_UA = "hdr_user_agent";
    static final String KEY_REFERER = "hdr_referer";
    static final String KEY_ORIGIN = "hdr_origin";
    static final String KEY_COOKIE = "hdr_cookie";

    private static volatile Map<String, String> current = new LinkedHashMap<>();

    private Net() {
    }

    /** Re-read the header settings. Cheap; call on start and after the dialog saves. */
    static void refresh(Context context) {
        current = build(context.getSharedPreferences(StreamConfig.PREFS, Context.MODE_PRIVATE));
    }

    static Map<String, String> headers() {
        Map<String, String> copy = new LinkedHashMap<>(current);
        if (copy.isEmpty()) {
            copy.put("User-Agent", DEFAULT_UA);
        }
        return copy;
    }

    static boolean hasCustomReferer(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(StreamConfig.PREFS, Context.MODE_PRIVATE);
        return !prefs.getString(KEY_REFERER, "").trim().isEmpty();
    }

    static void save(Context context, String userAgent, String referer, String origin, String cookie) {
        SharedPreferences prefs = context.getSharedPreferences(StreamConfig.PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_UA, safe(userAgent))
                .putString(KEY_REFERER, safe(referer))
                .putString(KEY_ORIGIN, safe(origin))
                .putString(KEY_COOKIE, safe(cookie))
                .apply();
        refresh(context);
    }

    static void clear(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(StreamConfig.PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .remove(KEY_UA).remove(KEY_REFERER).remove(KEY_ORIGIN).remove(KEY_COOKIE)
                .apply();
        refresh(context);
    }

    /**
     * Attach the headers to a MediaPlayer source. {@code setDataSource(Context, Uri, Map)} exists
     * since API 14, so this works on every TV the app supports.
     */
    static void applyTo(MediaPlayer player, Context context, String url) {
        if (player == null || url == null) {
            return;
        }
        try {
            player.setDataSource(context, Uri.parse(url), headers());
        } catch (Exception e) {
            try {
                player.setDataSource(url);
            } catch (Exception ignored) {
                // the caller's error listener takes it from here
            }
        }
    }

    /** Same, with the header snapshot captured when the segment was queued. */
    static void applyTo(MediaPlayer player, Context context, String url, Map<String, String> headers) {
        if (player == null || url == null) {
            return;
        }
        try {
            player.setDataSource(context, Uri.parse(url), headers);
        } catch (Exception e) {
            try {
                player.setDataSource(url);
            } catch (Exception ignored) {
                // the caller's error listener takes it from here
            }
        }
    }

    static String userAgent(Context context) {
        return build(context.getSharedPreferences(StreamConfig.PREFS, Context.MODE_PRIVATE))
                .get("User-Agent");
    }

    static String referer(Context context) {
        return context.getSharedPreferences(StreamConfig.PREFS, Context.MODE_PRIVATE)
                .getString(KEY_REFERER, "");
    }

    static String cookie(Context context) {
        return context.getSharedPreferences(StreamConfig.PREFS, Context.MODE_PRIVATE)
                .getString(KEY_COOKIE, "");
    }

    /** "https://www.iwanttfc.com/watch/x" -> "https://www.iwanttfc.com" */
    static String originOf(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return "";
            }
            return scheme + "://" + host;
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, String> build(SharedPreferences prefs) {
        Map<String, String> headers = new LinkedHashMap<>();
        String ua = prefs.getString(KEY_UA, "").trim();
        headers.put("User-Agent", ua.isEmpty() ? DEFAULT_UA : ua);

        String referer = prefs.getString(KEY_REFERER, "").trim();
        String origin = prefs.getString(KEY_ORIGIN, "").trim();
        if (!referer.isEmpty()) {
            headers.put("Referer", referer);
            if (origin.isEmpty()) {
                origin = originOf(referer);
            }
        }
        if (!origin.isEmpty()) {
            headers.put("Origin", origin);
        }
        String cookie = prefs.getString(KEY_COOKIE, "").trim();
        if (!cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        return headers;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
