package com.pbb.streamtv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal DASH (MPD) support for unencrypted HLS-less streams.
 *
 * <p>Some live sources only publish an MPD with SegmentTemplate + SegmentTimeline. That is the one
 * DASH shape this class reads — enough to number the segments and hand their URLs to
 * {@link SegmentPlayer}, which then plays them exactly like HLS segments.
 *
 * <p>Protected manifests are refused on purpose: shaka-playing pages usually configure
 * {@code drm.clearKeys}, i.e. Widevine/PlayReady ClearKey. This is not a DRM client and will not
 * pretend to be one; {@link #isProtected} is what the UI turns into a readable message.
 */
final class DashManifest {

    static final class Media {
        final String initUrl;
        final List<String> segments = new ArrayList<>();
        long totalDurationMs = 0L;

        Media(String initUrl) {
            this.initUrl = initUrl;
        }
    }

    private DashManifest() {
    }

    /** True when a URL should be handled as DASH rather than HLS. */
    static boolean isDash(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(java.util.Locale.US);
        return lower.contains(".mpd") || lower.contains("/dash/") || lower.contains("format=mpd");
    }

    /** Cheap check on the manifest body (ContentProtection = Widevine/PlayReady). */
    static boolean isProtected(String xml) {
        if (xml == null) {
            return false;
        }
        String lower = xml.toLowerCase(java.util.Locale.US);
        return lower.contains("contentprotection")
                || lower.contains("urn:uuid:edef8ba9") // Widevine
                || lower.contains("urn:uuid:9a04f079") // PlayReady
                || lower.contains("cenc:default_kid");
    }

    /**
     * The manifest's ContentProtection element, or null. Reported to the UI so a protected stream
     * says so in one line instead of failing like a dead link.
     */
    static String protection(String xml) {
        int start = xml.indexOf("<ContentProtection");
        if (start < 0) {
            return null;
        }
        int end = xml.indexOf('>', start);
        String element = end < 0 ? xml.substring(start) : xml.substring(start, end + 1);
        boolean widevine = element.toLowerCase(java.util.Locale.US).contains("edef8ba9");
        boolean playready = element.toLowerCase(java.util.Locale.US).contains("9a04f079");
        String label = widevine ? "Widevine" : (playready ? "PlayReady" : "ContentProtection");
        String defaultKid = attribute(element, "cenc:default_kid");
        return label + (defaultKid == null ? "" : " · kid " + defaultKid);
    }

    static Media parse(String xml, String manifestUrl) throws IOException {
        return parse(xml, manifestUrl, true);
    }

    /** @param requireClear when true, a protected manifest is rejected (no key configured) */
    static Media parse(String xml, String manifestUrl, boolean requireClear) throws IOException {
        if (isProtected(xml) && requireClear) {
            String protection = protection(xml);
            throw new IOException("DRM-protected" + (protection == null ? "" : " (" + protection + ")")
                    + " · add its content key in the app");
        }
        String mvp = first(xml, "<MPD", ">");
        String mpdBase = attribute(mvp, "mediaPresentationDuration");
        long mpdMs = parseIsoDurationMs(mpdBase);

        Adaptation adaptation = pickVideo(xml, manifestUrl);
        if (adaptation == null) {
            throw new IOException("no video adaptation set in manifest");
        }
        String segmentTemplate = adaptation.template;
        if (segmentTemplate == null) {
            String list = adaptation.segmentList;
            if (list != null) {
                return parseSegmentList(list, adaptation.xml, manifestUrl);
            }
            throw new IOException("manifest has no SegmentTemplate/SegmentList (only $Number$/$Time$ DASH)");
        }

        String init = attribute(segmentTemplate, "initialization");
        String media = attribute(segmentTemplate, "media");
        if (init == null || media == null) {
            throw new IOException("SegmentTemplate without initialization/media");
        }
        String repId = attribute(representationTag(adaptation.xml), "id");
        if (repId == null) {
            repId = "0";
        }
        long startNumber = parseLong(attribute(segmentTemplate, "startNumber"), 1L);
        long timescale = parseLong(attribute(segmentTemplate, "timescale"), 1L);
        long periodMs = mpdMs > 0 ? mpdMs : 6L * 60L * 60L * 1000L;

        Media out = new Media(resolveUrl(manifestUrl, substitute(init, repId, startNumber, 0L)));
        String timeline = block(segmentTemplate, "SegmentTimeline");
        if (timeline != null) {
            long time = 0L;
            // <S t="…" d="…" r="…"/>
            int cursor = 0;
            while (true) {
                int sStart = timeline.indexOf("<S ", cursor);
                if (sStart < 0) {
                    int shortForm = timeline.indexOf("<S/>", cursor);
                    if (shortForm < 0) {
                        break;
                    }
                    sStart = shortForm;
                }
                int sEnd = timeline.indexOf('>', sStart);
                if (sEnd < 0) {
                    break;
                }
                String s = timeline.substring(sStart, sEnd + 1);
                long t = parseLong(attribute(s, "t"), time);
                long d = parseLong(attribute(s, "d"), 0L);
                long r = parseLong(attribute(s, "r"), 0L);
                if (d <= 0) {
                    break;
                }
                for (long i = 0; i <= r; i++) {
                    long segmentNumber = startNumber + out.segments.size();
                    out.segments.add(resolveUrl(manifestUrl,
                            substitute(media, repId, segmentNumber, t + i * d)));
                }
                time = t + (r + 1) * d;
                cursor = sEnd + 1;
            }
            out.totalDurationMs = timescale > 0 ? time * 1000L / timescale : 0L;
        } else {
            long duration = parseLong(attribute(segmentTemplate, "duration"), 0L);
            if (duration <= 0 || timescale <= 0) {
                throw new IOException("SegmentTemplate without duration × timescale");
            }
            long total = timescale > 0 ? periodMs * timescale / 1000L : 0L;
            long count = total / duration;
            if (count <= 0) {
                count = 900; // ~2.5 h at 10 s segments; the live loop keeps appending anyway
            }
            for (long i = 0; i < count; i++) {
                long number = startNumber + i;
                out.segments.add(resolveUrl(manifestUrl,
                        substitute(media, repId, number, i * duration)));
            }
            out.totalDurationMs = count * duration * 1000L / timescale;
        }
        if (out.segments.isEmpty()) {
            throw new IOException("manifest listed no segments");
        }
        return out;
    }

    /**
     * Turns the manifest into a normal HLS playlist: the init segment becomes {@code #EXT-X-MAP}
     * and every media segment is listed at the top of a playlist that {@link HlsPlaylist} already
     * knows how to read. That means one segment pipeline for DASH and HLS, capped to the last few
     * segments so a live manifest never queues hours of video.
     */
    static String toPlaylist(String xml, String manifestUrl, int tail) throws IOException {
        Media media = parse(xml, manifestUrl, false);
        int from = Math.max(0, media.segments.size() - Math.max(1, tail));
        StringBuilder out = new StringBuilder();
        out.append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:")
                .append(from).append('\n');
        if (!media.initUrl.isEmpty()) {
            out.append("#EXT-X-MAP:URI=\"").append(media.initUrl).append("\"\n");
        }
        for (int i = from; i < media.segments.size(); i++) {
            out.append("#EXTINF:6.0,\n").append(media.segments.get(i)).append('\n');
        }
        return out.toString();
    }

    /** One video adaptation set: its own XML, the inherited SegmentTemplate and the base URL. */
    private static final class Adaptation {
        String xml = "";
        String template;
        String segmentList;
        String baseUrl;
    }

    /**
     * Find the first video adaptation set and resolve the video one when the manifest interleaves
     * audio (audio sets often sit in the same Period).
     */
    private static Adaptation pickVideo(String xml, String manifestUrl) {
        int cursor = 0;
        while (true) {
            int start = xml.indexOf("<AdaptationSet", cursor);
            if (start < 0) {
                return null;
            }
            int end = xml.indexOf("</AdaptationSet>", start);
            if (end < 0) {
                return null;
            }
            String body = xml.substring(start, end);
            String head = body.substring(0, Math.min(body.length(), body.indexOf('>') + 1));
            boolean video = head.contains("contentType=\"video\"")
                    || head.contains("mimeType=\"video")
                    || body.contains("<Representation") && body.contains("width=");
            if (video) {
                Adaptation adaptation = new Adaptation();
                adaptation.xml = body;
                String template = block(body, "SegmentTemplate");
                if (template == null) {
                    // Adaptation-level template inherited from the Period/MPD is not supported,
                    // but a template placed before the representation is.
                    template = block(head, "SegmentTemplate");
                }
                adaptation.template = template;
                adaptation.segmentList = block(body, "SegmentList");
                String base = block(body, "BaseURL");
                adaptation.baseUrl = base == null ? manifestUrl : resolveUrl(manifestUrl, text(base));
                return adaptation;
            }
            cursor = end + 1;
        }
    }

    /** Element text with tags stripped ("<BaseURL>x</BaseURL>" -> "x"). */
    private static String text(String element) {
        int open = element.indexOf('>');
        int close = element.lastIndexOf("</");
        if (open < 0 || close <= open) {
            return "";
        }
        return element.substring(open + 1, close).trim();
    }

    private static Media parseSegmentList(String list, String representation, String manifestUrl)
            throws IOException {
        String init = attribute(list, "initialization");
        String initUrl = init == null ? "" : resolveUrl(manifestUrl, attributeOf(init, "sourceURL"));
        Media out = new Media(initUrl);
        int cursor = 0;
        while (true) {
            int start = list.indexOf("<SegmentURL", cursor);
            if (start < 0) {
                break;
            }
            int end = list.indexOf('>', start);
            if (end < 0) {
                break;
            }
            String media = attribute(list.substring(start, end), "media");
            if (media != null) {
                out.segments.add(resolveUrl(manifestUrl, media));
            }
            cursor = end + 1;
        }
        if (out.segments.isEmpty()) {
            throw new IOException("empty SegmentList");
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static String substitute(String template, String repId, long number, long time) {
        return template
                .replace("$RepresentationID$", repId)
                .replace("$Number$", Long.toString(number))
                .replace("$Time$", Long.toString(time))
                .replace("$Bandwidth$", "0")
                .replace("$$", "$");
    }

    /** The {@code <Representation …>} tag of a video adaptation set. */
    private static String representationTag(String adaptationXml) {
        int start = adaptationXml.indexOf("<Representation");
        if (start < 0) {
            return "";
        }
        int end = adaptationXml.indexOf('>', start);
        return end < 0 ? adaptationXml.substring(start) : adaptationXml.substring(start, end + 1);
    }

    private static String block(String xml, String tag) {
        int start = xml.indexOf("<" + tag);
        if (start < 0) {
            return null;
        }
        int open = xml.indexOf('>', start);
        if (open < 0) {
            return null;
        }
        if (xml.charAt(open - 1) == '/') {
            return xml.substring(start, open + 1);
        }
        int end = xml.indexOf("</" + tag + ">", open);
        return end < 0 ? null : xml.substring(start, end + tag.length() + 3);
    }

    private static String first(String xml, String from, String to) {
        int start = xml.indexOf(from);
        if (start < 0) {
            return "";
        }
        int end = xml.indexOf(to, start);
        return end < 0 ? "" : xml.substring(start, end);
    }

    private static String attribute(String xml, String name) {
        if (xml == null) {
            return null;
        }
        int i = xml.indexOf(name + "=\"");
        if (i < 0) {
            return null;
        }
        int start = i + name.length() + 2;
        int end = xml.indexOf('"', start);
        return end < 0 ? null : xml.substring(start, end);
    }

    /** Same lookup for elements without the trailing '=' (e.g. {@code <Initialization sourceURL>}). */
    private static String attributeOf(String element, String name) {
        String value = attribute(element, name);
        return value == null ? "" : value;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** ISO-8601 duration "PT2H30M12.5S" -> ms. */
    static long parseIsoDurationMs(String iso) {
        if (iso == null || iso.isEmpty()) {
            return 0L;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?")
                .matcher(iso.trim());
        if (!m.find()) {
            return 0L;
        }
        double days = m.group(1) == null ? 0 : Double.parseDouble(m.group(1));
        double hours = m.group(2) == null ? 0 : Double.parseDouble(m.group(2));
        double minutes = m.group(3) == null ? 0 : Double.parseDouble(m.group(3));
        double seconds = m.group(4) == null ? 0 : Double.parseDouble(m.group(4));
        return (long) (((days * 24 + hours) * 60 + minutes) * 60 + seconds) * 1000L;
    }

    static String resolveUrl(String base, String ref) {
        return HlsPlaylist.resolve(base, ref);
    }

    /** Fetch an MPD body with the app's headers. */
    static String fetch(String url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(true);
            for (java.util.Map.Entry<String, String> header : Net.headers().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for the manifest");
            }
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > 4 * 1024 * 1024) {
                    throw new IOException("manifest too large");
                }
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
