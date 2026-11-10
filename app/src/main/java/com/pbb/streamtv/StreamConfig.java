package com.pbb.streamtv;

import android.content.SharedPreferences;
import android.net.Uri;


/**
 * The single source of truth for "what are we watching".
 *
 * <p>The app ships with the three PBB 3.0 camera slots the website offers (All Access / Main,
 * Dining area, External den) but <b>no</b> stream URLs: those live behind iWant and are not public.
 * Each slot is bound to a real stream once, either with the {@code pbblivetv://add?key=…&url=…}
 * deep link or by filling in {@link #MANIFEST} and rebuilding, and from then on it plays straight
 * from the TV with no login, no browser and no typing.
 *
 * <p>Nothing here loads third-party HTML into a WebView: the optional landing page is only opened
 * in the system browser, never rendered in-app.
 */
final class StreamConfig {

    /** Overview page. Only ever opened externally — it is a mobile site, not a TV UI. */
    static final String LANDING_PAGE = "https://pbblivestream.vercel.app/";

    /** Public smoke-test stream: if this plays, decoder + network + player are all fine. */
    static final String DEMO_HLS =
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8";

    /**
     * The camera switcher from the website, in the same order it uses.
     *
     * <p>Keys are stable — bind a stream to a key once and every future build keeps the binding
     * (bindings live on the device, in {@link ChannelStore}). Put your own licensed HLS/DASH URL in
     * the third argument to bake it into the APK instead.
     */
    /**
     * Shipped presets — the DASH endpoints published by the community site
     * github.com/hanuramakuku/Pbb-Live-Stream (decoded from its data.js). They are Widevine/PlayReady
     * ClearKey streams: without the 32-hex key pair they answer 403 and the app says so instead of
     * spinning. A key can be set per camera at runtime (never baked into this repo).
     */
    static final Stream[] MANIFEST = new Stream[]{
            new Stream("all", "Main stream · All Access",
                    "https://absp-live.akamaized.net/dash/live/2099522/pbbcollab30allaccesslivestream/manifest.mpd",
                    ""),
            new Stream("dining", "Dining area",
                    "https://absp-live.akamaized.net/dash/live/2099522/pbbcollab30allaccesslivestream-diningarea/manifest.mpd",
                    ""),
            new Stream("external", "External den",
                    "https://absp-live.akamaized.net/dash/live/2099522/pbbcollab30allaccesslivestream-externalden/manifest.mpd",
                    ""),
            new Stream("mainlive", "Main live channel",
                    "https://abslive.akamaized.net/dash/live/2099522/pinoybigbrothercollabedition30live/manifest.mpd",
                    ""),
    };

    /**
     * ClearKey entries for the presets, in the same order, as {@code "kidHex:keyHex"}. Empty on
     * purpose: keys are added per device with "Content keys" in the app, or by filling this array in
     * a private build. The app refuses to play a protected stream it has no key for.
     */
    static final String[] PRESET_KEYS = new String[]{"", "", "", ""};

    /** Off by default; long-press the row in the channel list to remove it after the first run. */
    static final boolean SHOW_DEMO = true;

    /** Last camera opens by itself on launch instead of showing the picker. */
    static final boolean AUTO_RESUME = true;

    /** First run only: jump to the website in the system browser when no camera is bound yet. */
    static final boolean OPEN_SITE_WHEN_UNBOUND = false;

    /**
     * Deep links:
     * {@code pbblivetv://add?key=dining&url=<stream>&title=Dining}, {@code pbblivetv://add?url=…}
     * and {@code pbblivetv://play?url=<stream>&title=Label}.
     */
    static final String DEEPLINK_SCHEME = "pbblivetv";

    static final String PREFS = "pbb_tv";
    static final String KEY_LAST_URL = "last_url";
    static final String KEY_LAST_TITLE = "last_title";
    static final String KEY_LAST_KEY = "last_key";

    /**
     * Optional link file (JSON) the TV app can pull with "Import links": maps camera keys to a
     * stream URL or to a resolver endpoint. Empty = feature hidden. See {@link RemoteSources}.
     */
    static final String REMOTE_CONFIG_URL = "";

    private StreamConfig() {
    }

    static final class Stream {
        /** Stable camera id ({@code all} / {@code dining} / {@code external}). */
        final String key;
        final String title;
        /** HLS / DASH URL, empty when the camera is not bound yet. */
        final String hlsUrl;
        /** Progressive fallback (MP4), optional. */
        final String fallbackUrl;

        Stream(String key, String title, String hlsUrl, String fallbackUrl) {
            this.key = key;
            this.title = title;
            this.hlsUrl = hlsUrl;
            this.fallbackUrl = fallbackUrl;
        }

        boolean isEmpty() {
            return hlsUrl.isEmpty() && fallbackUrl.isEmpty();
        }

        String url() {
            return hlsUrl.isEmpty() ? fallbackUrl : hlsUrl;
        }
    }

    /** Preset key for a camera key, or "". */
    static String presetKey(String key) {
        for (int i = 0; i < MANIFEST.length; i++) {
            if (MANIFEST[i].key.equals(key)) {
                return i < PRESET_KEYS.length ? PRESET_KEYS[i] : "";
            }
        }
        return "";
    }

    static Stream demo() {
        return new Stream("demo", "Demo · HLS test stream", DEMO_HLS, "");
    }

    /** Manifest entry for a key, or null. */
    static Stream byKey(String key) {
        if (key == null) {
            return null;
        }
        for (Stream s : MANIFEST) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return null;
    }

    /** Human title for a key ({@code dining} -> "Dining area"), falling back to a generic label. */
    static String titleForKey(String key) {
        Stream s = byKey(key);
        return s == null ? "PBB Live" : s.title;
    }

    static boolean isAutoResume() {
        return AUTO_RESUME;
    }

    static final class PlayRequest {
        final String url;
        final String title;
        final String key;
        final boolean fromDeepLink;

        PlayRequest(String url, String title, String key, boolean fromDeepLink) {
            this.url = url;
            this.title = title;
            this.key = key == null ? "" : key;
            this.fromDeepLink = fromDeepLink;
        }

        static PlayRequest none() {
            return new PlayRequest("", "", "", false);
        }

        boolean isEmpty() {
            return url.isEmpty();
        }
    }

    /** Resolve what to open: deep link > saved resume point > nothing. */
    static PlayRequest resolve(Uri data, SharedPreferences prefs) {
        if (data != null && DEEPLINK_SCHEME.equals(data.getScheme())) {
            String url = data.getQueryParameter("url");
            String title = data.getQueryParameter("title");
            String key = data.getQueryParameter("key");
            if (url != null && !url.isEmpty()) {
                return new PlayRequest(url,
                        title == null ? titleForKey(key) : title,
                        key,
                        true);
            }
        }
        String saved = prefs.getString(KEY_LAST_URL, "");
        if (saved != null && !saved.isEmpty()) {
            String title = prefs.getString(KEY_LAST_TITLE, "PBB Live");
            String key = prefs.getString(KEY_LAST_KEY, "");
            return new PlayRequest(saved, title == null ? "PBB Live" : title, key, false);
        }
        return PlayRequest.none();
    }

    static void remember(SharedPreferences prefs, String url, String title, String key) {
        if (url == null || url.isEmpty()) {
            return;
        }
        prefs.edit()
                .putString(KEY_LAST_URL, url)
                .putString(KEY_LAST_TITLE, title)
                .putString(KEY_LAST_KEY, key == null ? "" : key)
                .apply();
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
