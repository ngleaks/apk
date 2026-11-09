package com.pbb.streamtv;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

/**
 * Progressive files (MP4 / WebM / MP3) and DASH-ish single URLs that MediaPlayer handles natively.
 *
 * <p>Unlike {@link SegmentPlayer} this one can seek and knows its duration, which is what makes
 * catch-up playback of a recording feel normal on a TV.
 */
final class FilePlayer implements StreamEngine {

    private final Handler main = new Handler(Looper.getMainLooper());

    private Listener listener;
    private Surface surface;
    private MediaPlayer player;
    private boolean wantPlay = true;
    private boolean prepared = false;
    private State state = State.IDLE;

    FilePlayer(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void setSurface(Surface surface) {
        this.surface = surface;
        if (player != null && surface != null && surface.isValid()) {
            player.setSurface(surface);
        }
    }

    @Override
    public void prepare(String url) {
        stop();
        prepared = false;
        wantPlay = true;
        emit(State.BUFFERING, "opening");
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mp.setOnPreparedListener(p -> {
                prepared = true;
                if (surface != null && surface.isValid()) {
                    p.setSurface(surface);
                }
                if (wantPlay) {
                    p.start();
                }
                emit(State.PLAYING, "");
            });
            mp.setOnCompletionListener(p -> emit(State.ENDED, ""));
            mp.setOnErrorListener((p, what, extra) -> {
                emit(State.ERROR, "media " + what + "/" + extra);
                return true;
            });
            mp.setDataSource(url);
            mp.prepareAsync();
        } catch (Exception e) {
            emit(State.ERROR, e.getMessage() == null ? "cannot open" : e.getMessage());
        }
    }

    @Override
    public void play() {
        wantPlay = true;
        if (player != null && prepared) {
            try {
                player.start();
                emit(State.PLAYING, "");
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    @Override
    public void pause() {
        wantPlay = false;
        if (player != null && prepared) {
            try {
                player.pause();
                emit(State.PAUSED, "");
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    @Override
    public boolean isPlaying() {
        try {
            return player != null && prepared && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long positionMs() {
        try {
            return player != null && prepared ? player.getCurrentPosition() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public long durationMs() {
        try {
            return player != null && prepared ? player.getDuration() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public boolean seekTo(long positionMs) {
        if (player == null || !prepared) {
            return false;
        }
        try {
            player.seekTo((int) Math.max(0L, positionMs));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isLive() {
        return durationMs() <= 0L;
    }

    @Override
    public void stop() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
                // not started yet
            }
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
        state = State.IDLE;
    }

    @Override
    public void release() {
        stop();
    }

    private void emit(State newState, String detail) {
        state = newState;
        if (listener != null) {
            main.post(() -> listener.onState(newState, detail));
        }
    }
}
