package com.pbb.streamtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The channel list shown by the picker, persisted as JSON in SharedPreferences.
 *
 * <p>Two kinds of rows:
 * <ul>
 *   <li>the three <b>camera slots</b> from {@link StreamConfig#MANIFEST} — stable key, title comes
 *       from the manifest, the URL is a binding the viewer sets once (deep link or rebuild);</li>
 *   <li><b>extra channels</b> added at runtime with {@code pbblivetv://add?url=…}.</li>
 * </ul>
 *
 * <p>The list is always merged with the manifest, so upgrading the app never loses a binding even
 * if the manifest changes.
 */
final class ChannelStore {

    private static final String KEY_BINDINGS = "bindings";   // key -> url
    private static final String KEY_EXTRAS = "extras";       // [ {title,url} ]
    private static final String KEY_HIDDEN = "hidden";       // ["demo"]
    private static final String KEY_RESOLVERS = "resolvers"; // key -> endpoint
    private static final String KEY_REMOTE = "remote_url";   // last link file pulled
    private static final String KEY_KEYS = "content_keys";   // key -> "kid:key"

    private ChannelStore() {
    }

    static final class Channel {
        /** Camera id, or the literal {@code "extra"} for runtime channels. */
        final String key;
        final String title;
        final String url;

        Channel(String key, String title, String url) {
            this.key = key;
            this.title = title;
            this.url = url == null ? "" : url;
        }

        boolean isCamera() {
            return !"extra".equals(key);
        }

        boolean isBound() {
            return !TextUtils.isEmpty(url);
        }

    }

    /** All rows, cameras first in manifest order, then extras. Bindings are merged in. */
    static List<Channel> load(Context context) {
        SharedPreferences prefs = prefs(context);
        JSONObject bindings = readObject(prefs, KEY_BINDINGS);
        JSONArray hidden = readArray(prefs, KEY_HIDDEN);

        List<Channel> out = new ArrayList<>();
        for (StreamConfig.Stream stream : StreamConfig.MANIFEST) {
            if (contains(hidden, stream.key)) {
                continue;
            }
            String url = bindings.optString(stream.key, "");
            if (TextUtils.isEmpty(url)) {
                url = stream.url();
            }
            out.add(new Channel(stream.key, stream.title, url));
        }

        if (StreamConfig.SHOW_DEMO && !contains(hidden, "demo")) {
            StreamConfig.Stream demo = StreamConfig.demo();
            String url = bindings.optString(demo.key, "");
            if (TextUtils.isEmpty(url)) {
                url = demo.url();
            }
            out.add(new Channel(demo.key, demo.title, url));
        }

        JSONArray extras = readArray(prefs, KEY_EXTRAS);
        for (int i = 0; i < extras.length(); i++) {
            JSONObject o = extras.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String url = o.optString("url", "");
            if (!TextUtils.isEmpty(url)) {
                out.add(new Channel("extra", o.optString("title", ""), url));
            }
        }
        return out;
    }

    /** Bind a URL to a camera key (or add an extra channel when the key is unknown). */
    static void bind(Context context, String key, String title, String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        if (!TextUtils.isEmpty(key) && (StreamConfig.byKey(key) != null || "demo".equals(key))) {
            JSONObject bindings = readObject(prefs, KEY_BINDINGS);
            try {
                bindings.put(key, url);
            } catch (Exception ignored) {
                // org.json only throws on null keys
            }
            prefs.edit()
                    .putString(KEY_BINDINGS, bindings.toString())
                    .putString(StreamConfig.KEY_LAST_TITLE, title == null ? StreamConfig.titleForKey(key) : title)
                    .apply();
            unhide(context, key);
            return;
        }
        upsertExtra(context, title, url);
    }

    /** Add (or refresh) a runtime channel. */
    static void upsertExtra(Context context, String title, String url) {
        String cleanTitle = StreamConfig.titleFor(url, title);
        SharedPreferences prefs = prefs(context);
        JSONArray extras = readArray(prefs, KEY_EXTRAS);
        JSONArray next = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < extras.length(); i++) {
            JSONObject o = extras.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (url.equals(o.optString("url", ""))) {
                replaced = true;
                put(next, cleanTitle, url);
            } else {
                next.put(o);
            }
        }
        if (!replaced) {
            put(next, cleanTitle, url);
        }
        prefs.edit().putString(KEY_EXTRAS, next.toString()).apply();
    }

    /**
     * Remove a row. Camera slots are never deleted (the manifest owns them) — they are unbound and
     * hidden instead, so the viewer can restore them from the picker.
     */
    static void remove(Context context, Channel channel) {
        SharedPreferences prefs = prefs(context);
        if (channel.isCamera()) {
            JSONObject bindings = readObject(prefs, KEY_BINDINGS);
            bindings.remove(channel.key);
            JSONArray hidden = readArray(prefs, KEY_HIDDEN);
            if (!contains(hidden, channel.key)) {
                hidden.put(channel.key);
            }
            prefs.edit()
                    .putString(KEY_BINDINGS, bindings.toString())
                    .putString(KEY_HIDDEN, hidden.toString())
                    .apply();
            return;
        }
        JSONArray extras = readArray(prefs, KEY_EXTRAS);
        JSONArray next = new JSONArray();
        for (int i = 0; i < extras.length(); i++) {
            JSONObject o = extras.optJSONObject(i);
            if (o != null && !channel.url.equals(o.optString("url", ""))) {
                next.put(o);
            }
        }
        prefs.edit().putString(KEY_EXTRAS, next.toString()).apply();
    }

    /** Bring every camera slot back (used by the picker's "Restore cameras" row). */
    static void restoreCameras(Context context) {
        SharedPreferences prefs = prefs(context);
        prefs.edit().remove(KEY_HIDDEN).apply();
    }

    /** ClearKey pair ("kid:key") for a camera key, or "" when the stream is clear. */
    static String keyFor(Context context, String key) {
        if (TextUtils.isEmpty(key)) {
            return "";
        }
        String stored = readObject(prefs(context), KEY_KEYS).optString(key, "");
        return TextUtils.isEmpty(stored) ? StreamConfig.presetKey(key) : stored;
    }

    static void bindKey(Context context, String key, String pair) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        JSONObject keys = readObject(prefs, KEY_KEYS);
        try {
            if (TextUtils.isEmpty(pair)) {
                keys.remove(key);
            } else {
                keys.put(key, pair);
            }
        } catch (Exception ignored) {
            // org.json only throws on null keys
        }
        prefs.edit().putString(KEY_KEYS, keys.toString()).apply();
    }

    /** Resolver endpoint bound to a camera key, or "". */
    static String resolverFor(Context context, String key) {
        if (TextUtils.isEmpty(key)) {
            return "";
        }
        return readObject(prefs(context), KEY_RESOLVERS).optString(key, "");
    }

    /** Save (or clear) the resolver endpoint of a camera key. */
    static void bindResolver(Context context, String key, String endpoint) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        JSONObject resolvers = readObject(prefs, KEY_RESOLVERS);
        try {
            if (TextUtils.isEmpty(endpoint)) {
                resolvers.remove(key);
            } else {
                resolvers.put(key, endpoint);
            }
        } catch (Exception ignored) {
            // org.json only throws on null keys
        }
        prefs.edit().putString(KEY_RESOLVERS, resolvers.toString()).apply();
    }

    /** Apply a link file (see {@link RemoteSources}) in one go. */
    static void applyRemote(Context context, List<RemoteSources.Entry> entries) {
        SharedPreferences prefs = prefs(context);
        JSONObject bindings = readObject(prefs, KEY_BINDINGS);
        JSONObject resolvers = readObject(prefs, KEY_RESOLVERS);
        JSONArray extras = readArray(prefs, KEY_EXTRAS);
        for (RemoteSources.Entry e : entries) {
            boolean camera = !TextUtils.isEmpty(e.key)
                    && (StreamConfig.byKey(e.key) != null || "demo".equals(e.key));
            try {
                if (camera) {
                    if (!e.url.isEmpty()) {
                        bindings.put(e.key, e.url);
                        resolvers.remove(e.key);
                    } else if (!e.resolver.isEmpty()) {
                        bindings.remove(e.key);
                        resolvers.put(e.key, e.resolver);
                    }
                } else if (!e.url.isEmpty()) {
                    put(extras, e.title.isEmpty() ? StreamConfig.titleFor(e.url, null) : e.title, e.url);
                }
            } catch (Exception ignored) {
                // org.json only throws on null keys
            }
        }
        prefs.edit()
                .putString(KEY_BINDINGS, bindings.toString())
                .putString(KEY_RESOLVERS, resolvers.toString())
                .putString(KEY_EXTRAS, extras.toString())
                .remove(KEY_HIDDEN)
                .apply();
        rememberRemote(context, "");
    }

    static void rememberRemote(Context context, String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        prefs(context).edit().putString(KEY_REMOTE, url).apply();
    }

    static String lastRemote(Context context) {
        return prefs(context).getString(KEY_REMOTE, "");
    }

    static int boundCount(Context context) {
        int n = 0;
        for (Channel c : load(context)) {
            boolean bound = c.isBound() || !TextUtils.isEmpty(resolverFor(context, c.key));
            if (c.isCamera() && bound && !"demo".equals(c.key)) {
                n++;
            }
        }
        return n;
    }

    /** Very small sanity check so a mistyped URL does not silently become a dead channel. */
    static boolean looksPlayable(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return (lower.startsWith("http://") || lower.startsWith("https://")) && MediaUrls.isStream(url);
    }

    private static void unhide(Context context, String key) {
        SharedPreferences prefs = prefs(context);
        JSONArray hidden = readArray(prefs, KEY_HIDDEN);
        JSONArray next = new JSONArray();
        for (int i = 0; i < hidden.length(); i++) {
            if (!key.equals(hidden.optString(i))) {
                next.put(hidden.optString(i));
            }
        }
        prefs.edit().putString(KEY_HIDDEN, next.toString()).apply();
    }

    private static void put(JSONArray array, String title, String url) {
        JSONObject o = new JSONObject();
        try {
            o.put("title", title);
            o.put("url", url);
        } catch (Exception ignored) {
            // org.json only throws on null keys, which we never pass.
        }
        array.put(o);
    }

    private static boolean contains(JSONArray array, String value) {
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.optString(i))) {
                return true;
            }
        }
        return false;
    }

    private static JSONObject readObject(SharedPreferences prefs, String key) {
        try {
            return new JSONObject(prefs.getString(key, "{}"));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static JSONArray readArray(SharedPreferences prefs, String key) {
        try {
            return new JSONArray(prefs.getString(key, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(StreamConfig.PREFS, Context.MODE_PRIVATE);
    }
}
