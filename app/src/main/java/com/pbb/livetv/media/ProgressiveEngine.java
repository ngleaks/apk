package com.pbb.livetv.media;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

/**
 * Progressive file / single-URL playback (MP4, WebM, MKV). Also handles servers that hand back a
 * plain media file when asked for a stream. One MediaPlayer, prepared asynchronously, with the same
 * state machine as the HLS engine so the UI cannot tell them apart.
 */
public final class ProgressiveEngine implements PlayerEngine {

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Surface surface;
    private float volume = 1f;
    private String url;
    private boolean wantPlay = true;
    private volatile State state = State.IDLE;

    private final Listener listener;

    public ProgressiveEngine(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void prepare(String newUrl) {
        releasePlayer();
        url = newUrl;
        wantPlay = true;
        emit(State.CONNECTING, "opening");
        MediaPlayer mp = null;
        try {
            mp = new MediaPlayer();
            player = mp;
            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mp.setOnPreparedListener(p -> {
                p.setVolume(volume, volume);
                if (wantPlay) {
                    p.start();
                }
                listener.onVideoSize(this, p.getVideoWidth(), p.getVideoHeight());
                emit(State.PLAYING, null);
            });
            mp.setOnBufferingUpdateListener((p, percent) -> {
                if (state == State.CONNECTING) {
                    emit(State.BUFFERING, "buffering");
                }
            });
            mp.setOnCompletionListener(p -> emit(State.ENDED, null));
            mp.setOnErrorListener((p, what, extra) -> {
                emit(State.ERROR, "decode error " + what + "/" + extra);
                return true;
            });
            if (surface != null && surface.isValid()) {
                mp.setSurface(surface);
            }
            mp.setDataSource(newUrl);
            mp.prepareAsync();
        } catch (Exception e) {
            safeRelease(mp);
            player = null;
            emit(State.ERROR, e.getMessage() == null ? "cannot open" : e.getMessage());
        }
    }

    @Override
    public void setSurface(Surface newSurface) {
        this.surface = newSurface;
        if (player != null) {
            try {
                player.setSurface(newSurface);
            } catch (Exception ignored) {
                // next prepare will attach it
            }
        }
    }

    @Override
    public void setVolume(float newVolume) {
        this.volume = Math.max(0f, Math.min(1f, newVolume));
        if (player != null) {
            try {
                player.setVolume(this.volume, this.volume);
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    @Override
    public void play() {
        wantPlay = true;
        if (player != null) {
            try {
                player.start();
                emit(State.PLAYING, null);
            } catch (Exception ignored) {
                // ignore
            }
        } else if (url != null) {
            prepare(url);
        }
    }

    @Override
    public void pause() {
        wantPlay = false;
        if (player != null) {
            try {
                player.pause();
                emit(State.PAUSED, null);
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    @Override
    public void retry() {
        if (url != null) {
            prepare(url);
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
        releasePlayer();
        state = State.IDLE;
    }

    private void releasePlayer() {
        MediaPlayer p = player;
        player = null;
        safeRelease(p);
    }

    private void safeRelease(MediaPlayer p) {
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

    private void emit(State newState, String detail) {
        state = newState;
        main.post(() -> {
            if (listener != null) {
                listener.onEngineState(this, newState, detail);
            }
        });
    }
}
