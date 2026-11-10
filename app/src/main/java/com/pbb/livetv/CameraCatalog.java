package com.pbb.livetv;

import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * The camera catalogue: seed data + on-device overrides.
 *
 * <p>Seed comes from {@code assets/streams.json} so the stream URLs are never compiled into code
 * (they are still public DASH manifests, not secrets, but keeping them in one declarative file
 * makes it obvious what the app is allowed to play). On first launch the seed is copied into
 * SharedPreferences; later launches read the device copy, which is what the deep links and the
 * remote configuration override mutate. If no override exists, an updated APK's seed wins.
 *
 * <h3>Why some cameras are marked {@code awaitingKey}</h3>
 * The upstream web player (Shaka) configures a ClearKey licence for the ABS-CBN / Kapamilya DASH
 * manifests: the key id/key value pairs are published in its own {@code data.js}. Those keys rotate
 * and are the broadcaster's access control, so this app deliberately does <b>not</b> ship them.
 * Until you supply your own licence for a feed you are licensed to rebroadcast, the camera shows an
 * honest "ClearKey licence not provided" state instead of silently failing or bypassing protection.
 */
public final class CameraCatalog {

    private static final String TAG = "PbbCatalog";
    private static final String ASSET = "streams.json";
    public static final String PREFS = "pbb_livetv";
    private static final String KEY_CATALOG = "catalog_json";
    private static final String KEY_LAST_SECTION = "last_section";
    private static final String KEY_LAST_CAMERA = "last_camera";
    /** Deep link that points at a single JSON file to merge over the seed. */
    public static final String SCHEME = "pbblive";
    public static final String REMOTE_CONFIG_URL = ""; // e.g. https://example.com/pbb/streams.json

    private CameraCatalog() {
    }

    // ------------------------------------------------------------------ loading

    public static List<Camera> load(android.content.Context context) {
        String json = readPrefs(context, KEY_CATALOG);
        if (!TextUtils.isEmpty(json)) {
            List<Camera> parsed = parse(json);
            if (!parsed.isEmpty()) {
                return parsed;
            }
        }
        List<Camera> seeded = parse(readAsset(context));
        if (!seeded.isEmpty()) {
            persist(context, seeded);
        }
        return seeded;
    }

    public static void persist(android.content.Context context, List<Camera> cameras) {
        writePrefs(context, KEY_CATALOG, toJson(cameras));
    }

    /** Used by the remote override / deep link path. Returns the merged list or null on error. */
    public static List<Camera> mergeRemote(android.content.Context context, String json) {
        List<Camera> incoming = parse(json);
        if (incoming.isEmpty()) {
            return null;
        }
        List<Camera> current = load(context);
        List<Camera> merged = new ArrayList<>(current);
        for (Camera fresh : incoming) {
            Camera existing = Camera.byId(merged, fresh.id);
            if (existing == null) {
                merged.add(fresh);
            } else {
                merged.set(merged.indexOf(existing), fresh);
            }
        }
        persist(context, merged);
        return merged;
    }

    public static List<Camera> parse(String json) {
        List<Camera> out = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return out;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String id = o.optString("id", "cam" + (i + 1));
                Camera.Section section = Camera.Section.fromName(o.optString("section", "ALL"), Camera.Section.ALL);
                out.add(new Camera(
                        id,
                        o.optString("label", ""),
                        o.optString("subtitle", ""),
                        section,
                        o.optString("url", ""),
                        o.optString("format", ""),
                        o.optString("keyId", ""),
                        o.optString("key", ""),
                        i + 1));
            }
        } catch (Exception e) {
            Log.w(TAG, "catalogue parse failed: " + e.getMessage());
            out.clear();
        }
        return out;
    }

    public static String toJson(List<Camera> cameras) {
        JSONArray array = new JSONArray();
        try {
            for (Camera c : cameras) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("label", c.label);
                o.put("subtitle", c.subtitle);
                o.put("section", c.section.name());
                o.put("url", c.url);
                o.put("format", c.format);
                if (!c.keyId.isEmpty()) {
                    o.put("keyId", c.keyId);
                }
                if (!c.keyValue.isEmpty()) {
                    o.put("key", c.keyValue);
                }
                array.put(o);
            }
        } catch (Exception ignored) {
            // JSONObject.put only throws on NaN style values, none of which we store.
        }
        return array.toString();
    }

    private static String readAsset(android.content.Context context) {
        try (InputStream in = context.getAssets().open(ASSET)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), Charset.forName("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "no " + ASSET + ": " + e.getMessage());
            return "";
        }
    }

    // ------------------------------------------------------------------ prefs helpers

    private static String readPrefs(android.content.Context context, String key) {
        return context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(key, "");
    }

    private static void writePrefs(android.content.Context context, String key, String value) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putString(key, value).apply();
    }

    public static Camera.Section lastSection(android.content.Context context, Camera.Section fallback) {
        return Camera.Section.fromName(readPrefs(context, KEY_LAST_SECTION), fallback);
    }

    public static void rememberSection(android.content.Context context, Camera.Section section) {
        writePrefs(context, KEY_LAST_SECTION, section.name());
    }

    public static String lastCameraId(android.content.Context context) {
        return readPrefs(context, KEY_LAST_CAMERA);
    }

    public static void rememberCamera(android.content.Context context, String cameraId) {
        if (cameraId != null) {
            writePrefs(context, KEY_LAST_CAMERA, cameraId);
        }
    }

    // ------------------------------------------------------------------ remote override

    public interface ConfigCallback {
        void onConfig(List<Camera> cameras, String error);
    }

    /**
     * Fetch {@code url} (one JSON array in the same shape as assets/streams.json) on a worker
     * thread and merge it over the current catalogue. Optional; the app works without it.
     */
    public static void fetchAndMerge(final android.content.Context context, final String url,
                              final android.os.Handler callbackHandler,
                              final ConfigCallback callback) {
        new Thread(() -> {
            String error = null;
            List<Camera> result = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/json");
                if (conn.getResponseCode() != 200) {
                    error = "HTTP " + conn.getResponseCode();
                } else {
                    InputStream in = conn.getInputStream();
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    int read;
                    while ((read = in.read(chunk)) > 0) {
                        buffer.write(chunk, 0, read);
                    }
                    in.close();
                    result = mergeRemote(context, new String(buffer.toByteArray(), Charset.forName("UTF-8")));
                    if (result == null) {
                        error = "empty catalogue";
                    }
                }
            } catch (Exception e) {
                error = e.getClass().getSimpleName();
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final List<Camera> finalResult = result;
            final String finalError = error;
            callbackHandler.post(() -> callback.onConfig(finalResult, finalError));
        }, "pbb-config").start();
    }

    /** Debug helper: dump the current catalogue to a file so `adb pull` can inspect it. */
    public static void exportTo(android.content.Context context, OutputStream out) throws Exception {
        out.write(toJson(load(context)).getBytes(Charset.forName("UTF-8")));
    }
}
