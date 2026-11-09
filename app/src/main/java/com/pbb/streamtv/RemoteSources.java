package com.pbb.streamtv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional link source: one small JSON file, fetched over plain HTTPS, that says which stream
 * belongs to which camera. No website, no HTML, no WebView — the TV app just reads the file.
 *
 * <pre>
 * {
 *   "channels": [
 *     { "key": "all",      "url": "https://cdn.example/main.m3u8" },
 *     { "key": "dining",   "resolver": "https://cdn.example/api/live?cam=dining" },
 *     { "key": "external", "resolver": "https://cdn.example/api/live?cam=external" }
 *   ]
 * }
 * </pre>
 *
 * <p>A {@code resolver} is an endpoint that answers with a stream URL (JSON, plain text or a
 * playlist body); {@link #extractStreamUrl} pulls the first {@code .m3u8} / {@code .mpd} out of it.
 * That covers "the link changes every few minutes" without shipping a new APK, and it is the only
 * thing the app ever needs from a server: the player stays native.
 */
final class RemoteSources {

    private static final int TIMEOUT_MS = 12_000;
    private static final String UA = "PBBforFREE-TV/1.2 (Android TV)";

    /** Any http(s) URL that ends in a stream extension or carries a playlist query. */
    private static final Pattern STREAM = Pattern.compile(
            "https?://[^\"'\\s<>\\\\]+?\\.(?:m3u8|mpd)(?:\\?[^\"'\\s<>\\\\]*)?"
                    + "|https?://[^\"'\\s<>\\\\]+?[?&](?:format|type)=(?:m3u8|mpd)[^\"'\\s<>\\\\]*");

    private RemoteSources() {
    }

    static final class Entry {
        final String key;
        final String title;
        final String url;
        final String resolver;

        Entry(String key, String title, String url, String resolver) {
            this.key = key == null ? "" : key;
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
            this.resolver = resolver == null ? "" : resolver;
        }
    }

    static final class Result {
        final int cameras;
        final int extras;

        Result(int cameras, int extras) {
            this.cameras = cameras;
            this.extras = extras;
        }

        String summary() {
            if (cameras == 0 && extras == 0) {
                return "No channels in that file";
            }
            return cameras + " camera link(s), " + extras + " extra channel(s)";
        }
    }

    /** Fetch the link file and apply it to the device. Network call: run on the IO thread. */
    static Result sync(Context context, String configUrl) throws IOException {
        String body = fetch(configUrl);
        List<Entry> entries = parse(body);
        ChannelStore.applyRemote(context, entries);
        int cameras = 0;
        int extras = 0;
        for (Entry e : entries) {
            if (StreamConfig.byKey(e.key) != null || "demo".equals(e.key)) {
                cameras++;
            } else {
                extras++;
            }
        }
        return new Result(cameras, extras);
    }

    /** Ask a resolver endpoint for the current URL of one camera. Network call: IO thread. */
    static String resolve(String resolverUrl) throws IOException {
        String url = extractStreamUrl(fetch(resolverUrl));
        if (url.isEmpty()) {
            throw new IOException("no stream URL in response");
        }
        return url;
    }

    static List<Entry> parse(String body) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(body);
            JSONArray channels = root.optJSONArray("channels");
            if (channels == null && root.optJSONObject("channels") != null) {
                // Also accept {"channels": {"all": "https://…", "dining": "https://…"}}
                JSONObject map = root.optJSONObject("channels");
                JSONArray keys = map.names();
                for (int i = 0; keys != null && i < keys.length(); i++) {
                    String key = keys.optString(i);
                    entries.add(new Entry(key, "", map.optString(key, ""), ""));
                }
                return entries;
            }
            for (int i = 0; channels != null && i < channels.length(); i++) {
                JSONObject o = channels.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String url = firstNonEmpty(o.optString("url", ""), o.optString("stream", ""),
                        o.optString("m3u8", ""));
                String resolver = firstNonEmpty(o.optString("resolver", ""), o.optString("api", ""));
                if (url.isEmpty() && resolver.isEmpty()) {
                    continue;
                }
                entries.add(new Entry(o.optString("key", ""), o.optString("title", ""), url, resolver));
            }
        } catch (Exception e) {
            // Not JSON: take whatever stream URL the body contains and save it as an extra channel.
            String url = extractStreamUrl(body);
            if (url.isEmpty()) {
                throw new IOException("unreadable link file");
            }
            entries.add(new Entry("", "Remote stream", url, ""));
        }
        return entries;
    }

    /** First playable stream URL in a JSON, text or playlist body (handles JSON-escaped slashes). */
    static String extractStreamUrl(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        String text = body.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
        Matcher matcher = STREAM.matcher(text);
        return matcher.find() ? matcher.group() : "";
    }

    /**
     * Human-readable health check for a link: status code, size and the first playlist line.
     * This is what tells "the link is fine" apart from "the CDN wants a session" on a 403.
     */
    static String probe(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            for (java.util.Map.Entry<String, String> header : Net.headers().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            int code = connection.getResponseCode();
            String type = connection.getContentType();
            if (code < 200 || code >= 300) {
                return "HTTP " + code + (code == 403 || code == 401 ? " (session/geo locked)" : "");
            }
            InputStream in = connection.getInputStream();
            byte[] head = new byte[96];
            int read = in.read(head);
            in.close();
            String first = read > 0 ? new String(head, 0, read, "UTF-8").split("\r?\n")[0] : "";
            return "HTTP " + code + " · " + (type == null ? "?" : type)
                    + (first.isEmpty() ? "" : " · " + first.trim());
        } catch (Exception e) {
            return "ERR " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static String fetch(String url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            connection.setRequestProperty("User-Agent", UA);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > 512 * 1024) { // a link file is a few KB; never buffer a video
                    throw new IOException("response too large");
                }
            }
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }
}
