package com.pbb.livetv.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/** Tiny HTTP helpers. Everything runs off the main thread; callers own their executor. */
public final class Net {

    private Net() {
    }

    public static boolean isOnline(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return true; // unknown: let the stream attempt decide
        }
    }

    /** GET a small text resource (playlist, JSON). Retries twice with backoff. */
    public static String getText(String url, int timeoutMs) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = open(url, timeoutMs);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code);
                }
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                in.close();
                return new String(out.toByteArray(), Charset.forName("UTF-8"));
            } catch (IOException e) {
                last = e;
                sleep(400L * (attempt + 1));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw last == null ? new IOException("request failed") : last;
    }

    public static HttpURLConnection open(String url, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "PBBLiveTV/1.0 (Android TV)");
        conn.setRequestProperty("Accept", "*/*");
        return conn;
    }

    /** Download to a byte array – used for media segments, kept small on purpose. */
    public static byte[] getBytes(String url, int timeoutMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = open(url, timeoutMs);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            in.close();
            return out.toByteArray();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
