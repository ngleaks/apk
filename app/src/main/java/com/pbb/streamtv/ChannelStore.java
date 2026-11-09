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
 * The channel list shown on the left of the player, persisted as JSON in SharedPreferences.
 *
 * <p>Seeded once from {@link StreamConfig#MANIFEST}; afterwards the viewer owns the list (add/remove
 * from the panel, or push a channel in with a {@code pbblivetv://} deep link).
 */
final class ChannelStore {

    private static final String KEY_CHANNELS = "channels";
    private static final String KEY_SEEDED = "seeded";

    private ChannelStore() {
    }

    static final class Channel {
        final String title;
        final String url;

        Channel(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    static List<Channel> load(Context context) {
        SharedPreferences prefs = prefs(context);
        List<Channel> out = new ArrayList<>();
        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            for (StreamConfig.Stream stream : StreamConfig.MANIFEST) {
                if (!stream.isEmpty()) {
                    out.add(new Channel(stream.title, stream.hlsUrl.isEmpty() ? stream.fallbackUrl : stream.hlsUrl));
                }
            }
            save(context, out);
            prefs.edit().putBoolean(KEY_SEEDED, true).apply();
            return out;
        }
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_CHANNELS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                String url = o.optString("url", "");
                if (!TextUtils.isEmpty(url)) {
                    out.add(new Channel(o.optString("title", ""), url));
                }
            }
        } catch (Exception ignored) {
            // Corrupt store: fall back to an empty list rather than crashing on a TV.
        }
        return out;
    }

    static void save(Context context, List<Channel> channels) {
        JSONArray array = new JSONArray();
        for (Channel c : channels) {
            JSONObject o = new JSONObject();
            try {
                o.put("title", c.title);
                o.put("url", c.url);
            } catch (Exception ignored) {
                // org.json only throws on null keys, which we never pass.
            }
            array.put(o);
        }
        prefs(context).edit().putString(KEY_CHANNELS, array.toString()).apply();
    }

    /** Add (or refresh) a channel and return the resulting list. */
    static List<Channel> upsert(Context context, String title, String url) {
        String cleanTitle = StreamConfig.titleFor(url, title);
        List<Channel> channels = load(context);
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).url.equals(url)) {
                channels.set(i, new Channel(cleanTitle, url));
                save(context, channels);
                return channels;
            }
        }
        channels.add(new Channel(cleanTitle, url));
        save(context, channels);
        return channels;
    }

    static List<Channel> remove(Context context, String url) {
        List<Channel> channels = load(context);
        for (int i = channels.size() - 1; i >= 0; i--) {
            if (channels.get(i).url.equals(url)) {
                channels.remove(i);
            }
        }
        save(context, channels);
        return channels;
    }

    /** Very small sanity check so a mistyped URL does not silently become a dead channel. */
    static boolean looksPlayable(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return (lower.startsWith("http://") || lower.startsWith("https://")) && MediaUrls.isStream(url);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(StreamConfig.PREFS, Context.MODE_PRIVATE);
    }
}
