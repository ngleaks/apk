package com.pbb.livetv.media;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.pbb.livetv.util.Net;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live HLS playback on top of {@link android.media.MediaPlayer}.
 *
 * <p>The platform player cannot follow an {@code .m3u8}, so this engine parses the playlist, keeps a
 * small queue of segments ahead of the playhead and hands them to a single reused MediaPlayer
 * instance. Behaviour that matters on a TV:
 * <ul>
 *     <li>starts two segments behind the live edge so the first frame arrives quickly;</li>
 *     <li>tracks {@code EXT-X-MEDIA-SEQUENCE} to skip segments after a stall instead of replaying;</li>
 *     <li>caps the queue so a slow network degrades to "latest available" rather than growing memory;</li>
 *     <li>reopens the playlist periodically, which also recovers rotated CDN tokens;</li>
 *     <li>never mutates DRM state: encrypted playlists report ERROR and the UI says so.</li>
 * </ul>
 */
public final class HlsEngine implements PlayerEngine {

    private static final int PREFETCH = 3;
    private static final int MAX_QUEUE = 5;
    private static final int PREFERRED_MAX_HEIGHT = 1080;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pbb-hls");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Bumped on every prepare(); stale worker steps compare against it and bail out. */
    private final java.util.concurrent.atomic.AtomicInteger generation =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private final ArrayDeque<HlsPlaylist.Segment> queue = new ArrayDeque<>();

    private final Listener listener;
    private MediaPlayer player;
    private Surface surface;
    private float volume = 1f;
    private String playlistUrl;
    private String mediaUrl;
    private HlsPlaylist playlist;
    private long lastSequence = -1L;
    private boolean wantPlay = true;
    private boolean preparing;
    private boolean startedOnce;
    private volatile State state = State.IDLE;

    public HlsEngine(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void prepare(String url) {
        stopInternal();
        final int gen = generation.incrementAndGet();
        playlistUrl = url;
        mediaUrl = null;
        playlist = null;
        lastSequence = -1L;
        prepared = false;
        startedOnce = false;
        wantPlay = true;
        running.set(true);
        emit(State.CONNECTING, "loading playlist");
        io.execute(() -> bootstrap(gen));
    }

    private boolean prepared;
    private int bootstrapAttempts;

    private void bootstrap(final int gen) {
        bootstrapAttempts = 0;
        while (isCurrent(gen) && bootstrapAttempts < 3) {
            bootstrapAttempts++;
            try {
                HlsPlaylist loaded = HlsPlaylist.parse(Net.getText(playlistUrl, 8000), playlistUrl);
                if (loaded.isMaster()) {
                    HlsPlaylist.Variant variant = loaded.pickVariant(PREFERRED_MAX_HEIGHT);
                    if (variant == null) {
                        fail("empty master playlist");
                        return;
                    }
                    mediaUrl = variant.url;
                    loaded = HlsPlaylist.parse(Net.getText(mediaUrl, 8000), mediaUrl);
                } else {
                    mediaUrl = playlistUrl;
                }
                if (loaded.encrypted) {
                    fail("encrypted HLS not supported without a DRM stack");
                    return;
                }
                if (loaded.segments.isEmpty()) {
                    // Live playlists are occasionally empty right after a discontinuity; retry.
                    Net.sleep(900L);
                    continue;
                }
                playlist = loaded;
                lastSequence = Math.max(0L, loaded.mediaSequence - 1L);
                // Start at the live edge minus two segments: fast first frame, avoids replaying.
                int from = Math.max(0, loaded.segments.size() - 3);
                for (int i = from; i < loaded.segments.size(); i++) {
                    HlsPlaylist.Segment s = loaded.segments.get(i);
                    if (s.sequence > lastSequence) {
                        lastSequence = s.sequence;
                        enqueue(s);
                    }
                }
                main.post(this::pump);
                return;
            } catch (Exception e) {
                if (!isCurrent(gen)) {
                    return;
                }
                main.post(() -> emit(State.BUFFERING, "reconnecting"));
                Net.sleep(700L * bootstrapAttempts);
            }
        }
        if (isCurrent(gen)) {
            fail("playlist unreachable");
        }
    }

    private boolean isCurrent(int gen) {
        return running.get() && generation.get() == gen;
    }

    private void enqueue(HlsPlaylist.Segment segment) {
        synchronized (queue) {
            if (queue.size() >= MAX_QUEUE) {
                queue.pollFirst();
            }
            queue.addLast(segment);
        }
    }

    /** Main thread: start the next segment when the previous one finished. */
    private void pump() {
        if (!running.get()) {
            return;
        }
        HlsPlaylist.Segment next;
        synchronized (queue) {
            next = queue.pollFirst();
        }
        if (next == null) {
            emit(State.BUFFERING, "buffering");
            io.execute(() -> {
                Net.sleep(400L);
                refill();
            });
            return;
        }
        startSegment(next);
    }

    private void startSegment(HlsPlaylist.Segment segment) {
        prepared = false;
        preparing = true;
        MediaPlayer mp = null;
        try {
            mp = new MediaPlayer();
            player = mp;
            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mp.setOnPreparedListener(p -> {
                preparing = false;
                prepared = true;
                if (volume <= 0f) {
                    p.setVolume(0f, 0f);
                } else {
                    p.setVolume(volume, volume);
                }
                if (wantPlay) {
                    p.start();
                }
                if (!startedOnce) {
                    startedOnce = true;
                    emit(State.PLAYING, "live");
                } else {
                    emit(State.PLAYING, null);
                }
                if (playlist != null) {
                    io.execute(this::refillAhead);
                }
            });
            mp.setOnCompletionListener(p -> {
                prepared = false;
                main.post(this::pump);
            });
            mp.setOnErrorListener((p, what, extra) -> {
                prepared = false;
                main.postDelayed(this::pump, 500L);
                return true;
            });
            mp.setOnInfoListener((p, what, extra) -> false);
            if (surface != null && surface.isValid()) {
                mp.setSurface(surface);
            }
            mp.setDataSource(segment.url);
            mp.prepareAsync();
        } catch (Exception e) {
            preparing = false;
            safeRelease(mp);
            player = null;
            main.postDelayed(this::pump, 700L);
        }
    }

    private void refill() {
        if (!running.get() || mediaUrl == null) {
            return;
        }
        int queued;
        synchronized (queue) {
            queued = queue.size();
        }
        if (queued >= PREFETCH) {
            return;
        }
        refillAhead();
    }

    private void refillAhead() {
        if (!running.get() || mediaUrl == null) {
            return;
        }
        final int gen = generation.get();
        try {
            HlsPlaylist fresh = HlsPlaylist.parse(Net.getText(mediaUrl, 8000), mediaUrl);
            if (fresh.segments.isEmpty() || !isCurrent(gen)) {
                return;
            }
            playlist = fresh;
            for (HlsPlaylist.Segment s : fresh.segments) {
                if (s.sequence > lastSequence) {
                    lastSequence = s.sequence;
                    enqueue(s);
                }
            }
            if (queue.isEmpty()) {
                main.postDelayed(this::pump, 400L);
            }
        } catch (Exception e) {
            main.post(() -> {
                if (!prepared && startedOnce) {
                    emit(State.BUFFERING, "live edge");
                }
            });
        }
    }

    @Override
    public void setSurface(Surface newSurface) {
        this.surface = newSurface;
        MediaPlayer p = player;
        if (p != null) {
            try {
                p.setSurface(newSurface);
            } catch (Exception ignored) {
                // Player is being swapped; the next segment attaches the surface.
            }
        }
    }

    @Override
    public void setVolume(float newVolume) {
        this.volume = Math.max(0f, Math.min(1f, newVolume));
        MediaPlayer p = player;
        if (p != null) {
            try {
                p.setVolume(this.volume, this.volume);
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    @Override
    public void play() {
        wantPlay = true;
        MediaPlayer p = player;
        if (p != null) {
            try {
                p.start();
                emit(State.PLAYING, null);
                return;
            } catch (Exception ignored) {
                // fall through to restarting the pipeline
            }
        }
        if (!running.get() && playlistUrl != null) {
            prepare(playlistUrl);
        }
    }

    @Override
    public void pause() {
        wantPlay = false;
        MediaPlayer p = player;
        if (p != null) {
            try {
                p.pause();
                emit(State.PAUSED, null);
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    @Override
    public void retry() {
        if (playlistUrl != null) {
            prepare(playlistUrl);
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
    public boolean isLive() {
        return true;
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public void release() {
        running.set(false);
        stopInternal();
        io.shutdownNow();
        state = State.IDLE;
    }

    private void stopInternal() {
        running.set(false);
        MediaPlayer p = player;
        player = null;
        safeRelease(p);
        synchronized (queue) {
            queue.clear();
        }
        prepared = false;
    }

    private void fail(String detail) {
        if (!running.get()) {
            return;
        }
        running.set(false);
        main.post(() -> emit(State.ERROR, detail));
    }

    private void emit(State newState, String detail) {
        state = newState;
        if (listener != null) {
            listener.onEngineState(this, newState, detail);
        }
    }

    private static void safeRelease(MediaPlayer p) {
        if (p == null) {
            return;
        }
        try {
            p.reset();
        } catch (Exception ignored) {
            // ignore
        }
        try {
            p.release();
        } catch (Exception ignored) {
            // ignore
        }
    }
}
