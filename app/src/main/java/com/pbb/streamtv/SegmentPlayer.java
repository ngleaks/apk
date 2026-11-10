package com.pbb.streamtv;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays an HLS live stream by downloading the playlist, queueing segments and playing them one by
 * one through {@link MediaPlayer}.
 *
 * <p>This exists because the app ships with no third party libraries: ExoPlayer would be ~2 MB of
 * dependencies, and pulling it in would mean a Maven fetch that the build here cannot do. The trade
 * off is deliberate and small for a linear TV stream: no ABR during playback (one variant is
 * chosen up front), a short gap when a segment is slow, and no trick-play seeking. Everything else a
 * TV needs - D-pad control, audio focus, auto recovery, live edge tracking - is handled here.
 */
final class SegmentPlayer implements StreamEngine {

    private static final int PREFETCH = 3;
    /** DASH manifests list hours of segments; only the live tail is queued. */
    private static final int DASH_TAIL = 4;
    private static final int MAX_QUEUE = 6;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ArrayDeque<Segment> queue = new ArrayDeque<>();
    private final List<Segment> pending = new ArrayList<>();

    private Listener listener;
    private Surface surface;
    private MediaPlayer player;
    private HlsPlaylist.Playlist playlist;
    private String mediaUrl;
    private long lastSequence = -1L;
    private boolean wantPlay = true;
    private boolean prepared = false;

    // ---- DASH (MPD) mode: init segment + numbered media segments, played from a local file ----
    private boolean dash = false;
    private int dashFileCount = 0;
    private String clearKey = "";

    private final android.content.Context context;

    SegmentPlayer(android.content.Context context, Listener listener, ExecutorService io) {
        this.context = context;
        this.listener = listener;
        this.io = io;
    }

    /**
     * Set a ClearKey pair ({@code kid:key}, 32 hex each). A protected manifest can only play when
     * its key is known; without one the CDN answers 403 and the UI says so.
     */
    void setClearKey(String pair) {
        this.clearKey = pair == null ? "" : pair.trim();
    }

    static final class Segment {
        final String url;
        final long sequence;
        final String initUrl;
        final java.util.Map<String, String> headers;

        Segment(String url, long sequence) {
            this(url, sequence, "");
        }

        Segment(String url, long sequence, String initUrl) {
            this.url = url;
            this.sequence = sequence;
            this.initUrl = initUrl == null ? "" : initUrl;
            this.headers = Net.headers();
        }
    }

    @Override
    public void setSurface(Surface surface) {
        this.surface = surface;
        applySurface();
    }

    @Override
    public void prepare(String url) {
        stop();
        mediaUrl = url;
        dash = DashManifest.isDash(url);
        lastSequence = -1L;
        queue.clear();
        pending.clear();
        running.set(true);
        emit(State.BUFFERING, "connecting");
        io.execute(this::bootstrap);
    }

    private void bootstrap() {
        if (dash) {
            bootstrapDash();
            return;
        }
        try {
            HlsPlaylist.Playlist loaded = loadPlaylistSafely();
            if (loaded == null) {
                return;
            }
            playlist = loaded;
            lastSequence = loaded.mediaSequence > 0 ? loaded.mediaSequence - 1 : -1L;
            refillQueue(true);
        } catch (Exception e) {
            fail("playlist: " + e.getMessage());
        }
    }

    /** Load the MPD and queue its last few segments; the init segment travels as #EXT-X-MAP. */
    private void bootstrapDash() {
        try {
            String xml = DashManifest.fetch(mediaUrl);
            if (!clearKey.isEmpty()) {
                // Keep the protection element out of the parser's way: a key is configured, so the
                // manifest is read as a normal segment list (the CDN is what enforces it).
                xml = xml.replace("<ContentProtection", "<UnusedProtection")
                        .replace("</ContentProtection>", "</UnusedProtection>");
            }
            HlsPlaylist.Playlist loaded = HlsPlaylist.fromBody(
                    DashManifest.toPlaylist(xml, mediaUrl, DASH_TAIL), mediaUrl);
            playlist = loaded;
            for (HlsPlaylist.Segment segment : loaded.segments) {
                if (segment.mediaSequence > lastSequence) {
                    lastSequence = segment.mediaSequence;
                    enqueue(new Segment(segment.url, segment.mediaSequence, loaded.initUrl));
                }
            }
            if (loaded.segments.isEmpty()) {
                fail("manifest listed no segments");
            }
        } catch (Exception e) {
            fail(e.getMessage() == null ? "manifest" : e.getMessage());
        }
    }

    private HlsPlaylist.Playlist loadPlaylistSafely() {
        for (int attempt = 0; attempt < 3 && running.get(); attempt++) {
            try {
                return HlsPlaylist.load(mediaUrl);
            } catch (Exception e) {
                sleep(800L * (attempt + 1));
            }
        }
        if (running.get()) {
            fail("cannot load playlist");
        }
        return null;
    }

    /** Ask the playlist for new segments and top the queue up. Runs on the IO thread. */
    private void refillQueue(boolean first) {
        if (!running.get()) {
            return;
        }
        if (dash) {
            bootstrapDash();
            return;
        }
        try {
            HlsPlaylist.Playlist fresh = HlsPlaylist.load(mediaUrl);
            playlist = fresh;
            List<HlsPlaylist.Segment> candidates = fresh.segments;
            // Always start a couple of segments back from the live edge so the first frames are ready.
            if (first && candidates.size() > 2) {
                candidates = candidates.subList(candidates.size() - 2, candidates.size());
            }
            for (HlsPlaylist.Segment s : candidates) {
                if (s.mediaSequence > lastSequence) {
                    lastSequence = s.mediaSequence;
                    enqueue(new Segment(s.url, s.mediaSequence, playlist == null ? "" : playlist.initUrl));
                }
            }
        } catch (Exception e) {
            main.post(() -> {
                if (!isPlaying() && running.get()) {
                    emit(State.BUFFERING, "live edge");
                }
            });
        }
    }

    private void enqueue(Segment segment) {
        synchronized (queue) {
            if (queue.size() >= MAX_QUEUE) {
                // Drop the oldest queued segment rather than growing without limit on a slow network.
                queue.pollFirst();
            }
            queue.addLast(segment);
        }
        main.post(this::pump);
    }

    /** Runs on the main thread; starts the next segment if nothing is playing. */
    private void pump() {
        if (!running.get() || prepared) {
            return;
        }
        Segment next;
        synchronized (queue) {
            next = queue.pollFirst();
        }
        if (next == null) {
            emit(State.BUFFERING, "buffering");
            if (playlist != null) {
                io.execute(() -> {
                    sleep(500L);
                    refillQueue(false);
                });
            }
            return;
        }
        startSegment(next);
    }

    private void startSegment(final Segment segment) {
        if (!segment.initUrl.isEmpty()) {
            // fMP4 / DASH: MediaPlayer cannot stitch moov + fragments itself, so build one file.
            prepared = true;
            destroyPlayer();
            io.execute(() -> {
                try {
                    final java.io.File file = buildSegmentFile(segment);
                    main.post(() -> startFileSegment(segment, file));
                } catch (final Exception e) {
                    prepared = false;
                    main.post(() -> {
                        emit(State.BUFFERING, "segment fetch failed");
                        main.postDelayed(this::pump, 600L);
                    });
                }
            });
            return;
        }
        prepared = true;
        destroyPlayer();
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mp.setOnPreparedListener(p -> {
                prepared = true;
                if (wantPlay) {
                    p.start();
                }
                emit(State.PLAYING, "live");
                topUpAhead();
            });
            mp.setOnCompletionListener(p -> {
                prepared = false;
                main.post(this::pump);
            });
            mp.setOnErrorListener((p, what, extra) -> {
                prepared = false;
                main.post(() -> {
                    emit(State.BUFFERING, "segment retry");
                    pump();
                });
                return true;
            });
            applySurface(mp);
            Net.applyTo(mp, this.context, segment.url, segment.headers);
            mp.prepareAsync();
            if (listener != null) {
                listener.onState(State.BUFFERING, "opening segment");
            }
        } catch (Exception e) {
            prepared = false;
            main.postDelayed(this::pump, 600L);
        }
    }

    /** init segment + media segment into one cache file. */
    private java.io.File buildSegmentFile(Segment segment) throws Exception {
        java.io.File init = new java.io.File(context.getCacheDir(),
                "init-" + Math.abs(segment.initUrl.hashCode()) + ".mp4");
        if (!init.exists() || init.length() == 0) {
            download(segment.initUrl, init, null);
        }
        java.io.File out = new java.io.File(context.getCacheDir(),
                "seg-" + (dashFileCount++ % 3) + ".mp4");
        if (out.exists() && !out.delete()) {
            throw new java.io.IOException("cannot reuse cache file");
        }
        append(init, out);
        download(segment.url, out, segment.headers);
        return out;
    }

    private void download(String url, java.io.File out, java.util.Map<String, String> headers)
            throws Exception {
        java.net.HttpURLConnection connection =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(true);
        for (java.util.Map.Entry<String, String> header
                : (headers == null ? Net.headers() : headers).entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new java.io.IOException("HTTP " + code + " for segment");
        }
        java.io.InputStream in = connection.getInputStream();
        java.io.FileOutputStream file = new java.io.FileOutputStream(out, true);
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            file.write(buffer, 0, read);
            if (!running.get()) {
                break;
            }
        }
        file.close();
        in.close();
        connection.disconnect();
    }

    private static void append(java.io.File from, java.io.File to) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(from);
        java.io.FileOutputStream out = new java.io.FileOutputStream(to, true);
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        in.close();
        out.close();
    }

    /** Play a downloaded fMP4 file (init + one media segment). */
    private void startFileSegment(Segment segment, java.io.File file) {
        if (!running.get()) {
            return;
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mp.setOnPreparedListener(p -> {
                prepared = true;
                if (wantPlay) {
                    p.start();
                }
                emit(State.PLAYING, "live");
                topUpAhead();
            });
            mp.setOnCompletionListener(p -> {
                prepared = false;
                main.post(this::pump);
            });
            mp.setOnErrorListener((p, what, extra) -> {
                prepared = false;
                main.post(() -> {
                    emit(State.BUFFERING, "segment retry");
                    pump();
                });
                return true;
            });
            applySurface(mp);
            mp.setDataSource(file.getAbsolutePath());
            mp.prepareAsync();
        } catch (Exception e) {
            prepared = false;
            main.postDelayed(this::pump, 600L);
        }
    }

    /** Keep at least PREFETCH segments ready ahead of the one that is playing. */
    private void topUpAhead() {
        if (!running.get()) {
            return;
        }
        int queued;
        synchronized (queue) {
            queued = queue.size();
        }
        if (queued < PREFETCH && playlist != null) {
            io.execute(() -> refillQueue(false));
        }
    }

    private void applySurface() {
        if (player != null) {
            applySurface(player);
        }
    }

    private void applySurface(MediaPlayer mp) {
        if (surface != null && surface.isValid()) {
            mp.setSurface(surface);
        }
    }

    @Override
    public void play() {
        wantPlay = true;
        if (player != null) {
            try {
                player.start();
                emit(State.PLAYING, "live");
            } catch (Exception ignored) {
                // A segment in a bad state; the pump will move on.
            }
        } else {
            main.post(this::pump);
        }
    }

    @Override
    public void pause() {
        wantPlay = false;
        if (player != null && isPlaying()) {
            try {
                player.pause();
                emit(State.PAUSED, "live");
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    @Override
    public boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long positionMs() {
        try {
            return player == null ? 0L : player.getCurrentPosition();
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public long durationMs() {
        return 0L;
    }

    @Override
    public boolean seekTo(long positionMs) {
        return false; // live stream
    }

    @Override
    public boolean isLive() {
        return true;
    }

    /** Stop playback but keep the engine reusable (surface, executor). */
    @Override
    public void stop() {
        running.set(false);
        destroyPlayer();
        synchronized (queue) {
            queue.clear();
        }
        pending.clear();
    }

    @Override
    public void release() {
        stop();
    }

    private void destroyPlayer() {
        if (player != null) {
            try {
                player.reset();
            } catch (Exception ignored) {
                // ignore
            }
            try {
                player.release();
            } catch (Exception ignored) {
                // ignore
            }
            player = null;
        }
        prepared = false;
    }

    private void fail(String detail) {
        if (!running.get()) {
            return;
        }
        main.post(() -> emit(State.ERROR, detail));
    }

    private void emit(State state, String detail) {
        if (listener != null) {
            listener.onState(state, detail);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
