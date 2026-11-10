package com.pbb.livetv.media;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Minimal HLS (RFC 8216) master/media playlist parser.
 *
 * <p>Covers what live TV uses: master variants (picked by resolution then bandwidth), rolling media
 * playlists with {@code EXT-X-MEDIA-SEQUENCE}, {@code EXT-X-TARGETDURATION} and {@code EXT-X-ENDLIST}.
 * Deliberately ignores encryption tags – a stream that needs keys cannot be played by the platform
 * decoder without a DRM stack, and this class never pretends otherwise.
 */
public final class HlsPlaylist {

    public static final class Variant {
        public final String url;
        public final long bandwidth;
        public final int width;
        public final int height;

        Variant(String url, long bandwidth, int width, int height) {
            this.url = url;
            this.bandwidth = bandwidth;
            this.width = width;
            this.height = height;
        }

        public String label() {
            return height > 0 ? height + "p" : (bandwidth / 1000) + " kbps";
        }
    }

    public static final class Segment {
        public final String url;
        public final double duration;
        public final long sequence;

        Segment(String url, double duration, long sequence) {
            this.url = url;
            this.duration = duration;
            this.sequence = sequence;
        }
    }

    public final List<Variant> variants = new ArrayList<>();
    public final List<Segment> segments = new ArrayList<>();
    public double targetDuration = 6d;
    public long mediaSequence = 0L;
    public boolean endList = false;
    public boolean encrypted = false;
    public String baseUrl;

    public boolean isMaster() {
        return !variants.isEmpty();
    }

    /**
     * Pick a variant: prefer the highest resolution at or below {@code maxHeight}, then the lowest
     * bandwidth among those (TV sticks struggle with 8 Mbps 1080p more than with 4K decode).
     */
    public Variant pickVariant(int maxHeight) {
        if (variants.isEmpty()) {
            return null;
        }
        List<Variant> eligible = new ArrayList<>();
        for (Variant v : variants) {
            if (v.height <= 0 || v.height <= maxHeight) {
                eligible.add(v);
            }
        }
        if (eligible.isEmpty()) {
            // Every rendition is above the cap (small screen / weak decoder): use the smallest one.
            List<Variant> all = new ArrayList<>(variants);
            Collections.sort(all, (a, b) -> {
                if (a.height != b.height) {
                    return Integer.compare(a.height, b.height);
                }
                return Long.compare(a.bandwidth, b.bandwidth);
            });
            return all.get(0);
        }
        Collections.sort(eligible, (a, b) -> {
            if (a.height != b.height) {
                return Integer.compare(b.height, a.height);
            }
            return Long.compare(a.bandwidth, b.bandwidth);
        });
        return eligible.get(0);
    }

    public static HlsPlaylist parse(String text, String playlistUrl) {
        HlsPlaylist result = new HlsPlaylist();
        result.baseUrl = playlistUrl;
        if (text == null) {
            return result;
        }
        String[] lines = text.split("\r?\n");
        double pendingDuration = -1d;
        long sequence = 0L;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                result.targetDuration = parseDouble(line.substring(22), 6d);
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                result.mediaSequence = (long) parseDouble(line.substring(22), 0d);
                sequence = result.mediaSequence;
            } else if (line.startsWith("#EXTINF:")) {
                String value = line.substring(8);
                int comma = value.indexOf(',');
                pendingDuration = parseDouble(comma >= 0 ? value.substring(0, comma) : value, 0d);
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                String attrs = line.substring(18);
                long bandwidth = (long) attrNumber(attrs, "BANDWIDTH=", 0d);
                int width = 0;
                int height = 0;
                String res = attr(attrs, "RESOLUTION=");
                if (res != null) {
                    int x = res.toLowerCase(Locale.US).indexOf('x');
                    if (x > 0) {
                        width = (int) parseDouble(res.substring(0, x), 0d);
                        height = (int) parseDouble(res.substring(x + 1), 0d);
                    }
                }
                // The variant URI is the next non-comment line; stash the attributes for it.
                result.pendingVariant = new long[]{bandwidth, width, height};
            } else if (line.startsWith("#EXT-X-KEY:")) {
                String method = attr(line.substring(11), "METHOD=");
                if (method != null && !"NONE".equalsIgnoreCase(method)) {
                    result.encrypted = true;
                }
            } else if (line.startsWith("#EXT-X-ENDLIST")) {
                result.endList = true;
            } else if (!line.startsWith("#")) {
                if (result.pendingVariant != null) {
                    long[] v = result.pendingVariant;
                    result.variants.add(new Variant(resolve(playlistUrl, line), v[0], (int) v[1], (int) v[2]));
                    result.pendingVariant = null;
                } else {
                    result.segments.add(new Segment(resolve(playlistUrl, line),
                            pendingDuration < 0 ? result.targetDuration : pendingDuration, sequence));
                    sequence++;
                    pendingDuration = -1d;
                }
            }
        }
        return result;
    }

    private long[] pendingVariant;

    private static String resolve(String base, String ref) {
        try {
            return URI.create(base).resolve(ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String attr(String attributes, String key) {
        String[] parts = attributes.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.toUpperCase(Locale.US).startsWith(key)) {
                String v = trimmed.substring(key.length());
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() > 1) {
                    return v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        return null;
    }

    private static double attrNumber(String attributes, String key, double fallback) {
        String v = attr(attributes, key);
        return v == null ? fallback : parseDouble(v, fallback);
    }
}
