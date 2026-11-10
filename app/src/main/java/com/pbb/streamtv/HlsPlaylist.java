package com.pbb.streamtv;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A hand rolled HLS playlist reader.
 *
 * <p>{@code android.media.MediaPlayer} can only play a single progressive file. Most live sources
 * hand out an {@code .m3u8} with a rolling window of short MPEG-TS segments, so this class fetches
 * the playlist, keeps the segment hand off going, and {@link SegmentPlayer} plays the segments back
 * to back. It implements just enough of RFC 8216 for live streams: master playlists, #EXT-X-TARGETDURATION,
 * #EXT-X-MEDIA-SEQUENCE, #EXT-X-ENDLIST, byte ranges are ignored (rare for live) and variant choice
 * is "highest bandwidth the network can do".
 */
final class HlsPlaylist {

    static final class Variant {
        final String url;
        final long bandwidth;
        final String resolution;

        Variant(String url, long bandwidth, String resolution) {
            this.url = url;
            this.bandwidth = bandwidth;
            this.resolution = resolution;
        }
    }

    static final class Segment {
        final String url;
        final double duration;
        final long mediaSequence;

        Segment(String url, double duration, long mediaSequence) {
            this.url = url;
            this.duration = duration;
            this.mediaSequence = mediaSequence;
        }
    }

    static final class Playlist {
        final List<Variant> variants = new ArrayList<>();
        final List<Segment> segments = new ArrayList<>();
        double targetDuration = 6d;
        boolean endList = false;
        long mediaSequence = 0L;
        String baseUrl;
        /** fMP4 initialization segment from #EXT-X-MAP (used by DASH and CMAF HLS). */
        String initUrl = "";

        boolean isMaster() {
            return !variants.isEmpty();
        }

        /** Variant to play: highest bandwidth, then best resolution, capped so 4K does not melt a TV stick. */
        Variant bestVariant() {
            Variant best = null;
            for (Variant v : variants) {
                if (v.bandwidth > 12_000_000L) {
                    continue;
                }
                if (best == null
                        || v.bandwidth > best.bandwidth
                        || (v.bandwidth == best.bandwidth && compareResolution(v.resolution, best.resolution) > 0)) {
                    best = v;
                }
            }
            if (best == null && !variants.isEmpty()) {
                best = variants.get(0);
            }
            return best;
        }

        /** Newest segments that the caller has not played yet. */
        List<Segment> tailFrom(long lastSequence) {
            List<Segment> out = new ArrayList<>();
            for (Segment s : segments) {
                if (s.mediaSequence > lastSequence) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    private HlsPlaylist() {
    }

    static boolean looksLikePlaylist(String url) {
        String lower = url == null ? "" : url.toLowerCase();
        return lower.contains(".m3u8") || lower.contains(".m3u");
    }

    /**
     * Fetch and parse a playlist. Throws {@link IOException} for network problems so the caller can
     * retry with backoff.
     */
    /** Parse a playlist body that is already in memory (used for DASH manifests). */
    static Playlist fromBody(String body, String baseUrl) {
        return parse(body, baseUrl);
    }

    static Playlist load(String playlistUrl) throws IOException {
        String body = fetch(playlistUrl);
        Playlist playlist = parse(body, playlistUrl);
        if (playlist.isMaster()) {
            Variant variant = playlist.bestVariant();
            if (variant == null) {
                throw new IOException("master playlist has no variants");
            }
            Playlist media = parse(fetch(variant.url), variant.url);
            media.baseUrl = variant.url;
            return media;
        }
        return playlist;
    }

    static Playlist parse(String body, String url) {
        Playlist playlist = new Playlist();
        playlist.baseUrl = url;
        String[] lines = body.split("\r?\n");
        double pendingDuration = 0d;
        long index = 0L;
        long mediaSequence = 0L;
        long bandwidth = 0L;
        String resolution = "";

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = parseLong(line.substring(22));
                playlist.mediaSequence = mediaSequence;
                index = mediaSequence;
            } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                playlist.targetDuration = parseDouble(line.substring(22), 6d);
            } else if (line.startsWith("#EXTINF:")) {
                String value = line.substring(8);
                int comma = value.indexOf(',');
                pendingDuration = parseDouble(comma >= 0 ? value.substring(0, comma) : value, 6d);
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                bandwidth = extractLong(line, "BANDWIDTH=");
                resolution = extractString(line, "RESOLUTION=");
            } else if (line.startsWith("#EXT-X-MAP:")) {
                String map = extractQuoted(line, "URI=\"");
                if (!map.isEmpty()) {
                    playlist.initUrl = resolve(url, map);
                }
            } else if (line.startsWith("#EXT-X-ENDLIST")) {
                playlist.endList = true;
            } else if (!line.startsWith("#")) {
                String absolute = resolve(url, line);
                if (bandwidth > 0 || resolution.length() > 0) {
                    playlist.variants.add(new Variant(absolute, bandwidth, resolution));
                    bandwidth = 0L;
                    resolution = "";
                } else {
                    playlist.segments.add(new Segment(absolute, pendingDuration, index));
                    index++;
                    pendingDuration = 0d;
                }
            }
        }
        return playlist;
    }

    /** Resolve a possibly relative segment URI against the playlist URL. */
    static String resolve(String base, String ref) {
        try {
            if (ref.startsWith("http://") || ref.startsWith("https://")) {
                return ref;
            }
            if (ref.startsWith("//")) {
                return base.startsWith("https") ? "https:" + ref : "http:" + ref;
            }
            URL b = new URL(base);
            if (ref.startsWith("/")) {
                return b.getProtocol() + "://" + b.getHost() + (b.getPort() > 0 ? ":" + b.getPort() : "") + ref;
            }
            String path = b.getPath() == null ? "" : b.getPath();
            int slash = path.lastIndexOf('/');
            String dir = slash >= 0 ? path.substring(0, slash + 1) : "/";
            return b.getProtocol() + "://" + b.getHost() + (b.getPort() > 0 ? ":" + b.getPort() : "") + dir + ref;
        } catch (Exception e) {
            return ref;
        }
    }

    static String fetch(String url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(true);
            for (Map.Entry<String, String> header : Net.headers().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            connection.setRequestProperty("Accept", "*/*");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            java.io.InputStream in = connection.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > 2 * 1024 * 1024) {
                    throw new IOException("playlist too large");
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("cancelled");
                }
            }
            return out.toString("UTF-8");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot read " + url, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------ parsing helpers

    static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long extractLong(String line, String key) {
        String value = extractString(line, key);
        return value.isEmpty() ? 0L : parseLong(value.replace(",", ""));
    }

    /** Reads a quoted attribute value ("URI=\"https://…\"") without stopping at ':'. */
    private static String extractQuoted(String line, String key) {
        int at = line.indexOf(key);
        if (at < 0) {
            return "";
        }
        int start = at + key.length();
        int end = line.indexOf('"', start);
        return end < 0 ? line.substring(start) : line.substring(start, end);
    }

    private static String extractString(String line, String key) {
        int at = line.indexOf(key);
        if (at < 0) {
            return "";
        }
        int start = at + key.length();
        int end = start;
        while (end < line.length() && line.charAt(end) != ',' && line.charAt(end) != ':' && line.charAt(end) != ' ') {
            end++;
        }
        String value = line.substring(start, end);
        if (value.endsWith("\"")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.startsWith("\"")) {
            value = value.substring(1);
        }
        return value;
    }

    private static int compareResolution(String a, String b) {
        long[] ra = resolutionParts(a);
        long[] rb = resolutionParts(b);
        long pa = ra[0] * ra[1];
        long pb = rb[0] * rb[1];
        return Long.compare(pa, pb);
    }

    private static long[] resolutionParts(String value) {
        try {
            String[] parts = value.toLowerCase().split("x");
            return new long[]{Long.parseLong(parts[0].trim()), Long.parseLong(parts[1].trim())};
        } catch (Exception e) {
            return new long[]{0, 0};
        }
    }

    /** Small helper used by the player to remember which sequence numbers it already queued. */
    static Map<String, Long> emptyCursor() {
        return new LinkedHashMap<>();
    }
}
