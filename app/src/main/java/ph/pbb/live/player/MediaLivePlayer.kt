package ph.pbb.live.player

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface

/**
 * Platform MediaPlayer-backed [LivePlayer].
 *
 * Supports whatever the device decodes natively: HLS (.m3u8), MPEG-DASH (.mpd) on API 21+
 * with the platform's DASH support, and progressive MP4/WebM. Suitable for small
 * live-edge CCTV feeds; not suitable for DRM (see LivePlayer docs / README).
 *
 * Threading: all public methods may be called on the main thread (they are; TV screens are
 * main-thread by design). Listener callbacks are always posted to the main looper.
 */
class MediaLivePlayer : LivePlayer {

    override var listener: LivePlayer.Listener? = null

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var pendingUrl: String? = null
    private var muted: Boolean = false
    @Volatile private var released: Boolean = false

    override val isMuted: Boolean get() = muted
    override val isPlaying: Boolean get() = player?.isPlaying == true

    override fun setSurface(surface: Surface?) {
        this.surface = surface
        if (surface == null) {
            // Surface is gone: stop decoding immediately. Screens call load() again on recreate.
            releaseInternal()
            return
        }
        pendingUrl?.let { url ->
            pendingUrl = null
            load(url)
        }
    }

    override fun load(url: String) {
        check(!released) { "player released" }
        if (surface == null) {
            pendingUrl = url
            emit(PlaybackState.CONNECTING)
            return
        }
        releaseInternal()
        pendingUrl = null
        emit(PlaybackState.CONNECTING)
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        )
        mp.setSurface(surface)
        mp.setOnPreparedListener {
            if (player !== mp || released) return@setOnPreparedListener
            applyVolume(it)
            try {
                it.start()
                emit(PlaybackState.PLAYING)
            } catch (e: Exception) {
                emitError(mp, "Playback could not be started (${e.javaClass.simpleName}).")
            }
        }
        mp.setOnInfoListener { _, what, _ ->
            if (player !== mp || released) return@setOnInfoListener true
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> emit(PlaybackState.BUFFERING)
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> emit(PlaybackState.PLAYING)
                else -> Unit
            }
            true
        }
        mp.setOnVideoSizeChangedListener { _, w, h ->
            if (player !== mp || released) return@setOnVideoSizeChangedListener
            if (w > 0 && h > 0) main.post { listener?.onVideoSizeChanged(w, h) }
        }
        mp.setOnBufferingUpdateListener { _, _ -> /* percent ignored; OnInfo drives the spinner */ }
        mp.setOnCompletionListener {
            if (player !== mp || released) return@setOnCompletionListener
            emit(PlaybackState.ENDED)
        }
        mp.setOnErrorListener { _, what, extra ->
            if (player !== mp || released) return@setOnErrorListener true
            emitError(mp, errorText(what, extra))
            true
        }
        try {
            mp.setDataSource(url)
            mp.prepareAsync()
        } catch (e: Exception) {
            emitError(mp, "Stream could not be opened (${e.javaClass.simpleName}). Check the URL and network.")
        }
    }

    override fun play() {
        val mp = player ?: return
        try {
            if (!mp.isPlaying) {
                mp.start()
                emit(PlaybackState.PLAYING)
            }
        } catch (_: Exception) { /* player mid-prepare or dead; ignore */ }
    }

    override fun pause() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) {
                mp.pause()
                emit(PlaybackState.PAUSED)
            }
        } catch (_: Exception) { /* ignore */ }
    }

    override fun toggle() {
        if (isPlaying) pause() else play()
    }

    override fun mute(muted: Boolean) {
        this.muted = muted
        player?.let { applyVolume(it) }
    }

    private fun applyVolume(mp: MediaPlayer) {
        val v = if (muted) 0f else 1f
        try {
            mp.setVolume(v, v)
        } catch (_: Exception) { /* ignore */ }
    }

    override fun release() {
        released = true
        listener = null
        releaseInternal()
    }

    private fun releaseInternal() {
        val mp = player ?: return
        player = null
        try {
            mp.setOnPreparedListener(null)
            mp.setOnInfoListener(null)
            mp.setOnBufferingUpdateListener(null)
            mp.setOnVideoSizeChangedListener(null)
            mp.setOnCompletionListener(null)
            mp.setOnErrorListener(null)
            mp.reset()
        } catch (_: Exception) { /* ignore */ }
        try {
            mp.release()
        } catch (_: Exception) { /* ignore */ }
    }

    private fun emit(state: PlaybackState) {
        main.post { listener?.onState(state) }
    }

    private fun emitError(mp: MediaPlayer, message: String) {
        releaseInternal()
        main.post { listener?.onError(message) }
    }

    /** Error text must never leak the stream URL (it can carry signed tokens). */
    private fun errorText(what: Int, extra: Int): String =
        when (extra) {
            MediaPlayer.MEDIA_ERROR_IO -> "Network error while reading the stream. Check the connection and try again."
            MediaPlayer.MEDIA_ERROR_MALFORMED -> "The stream data could not be decoded (malformed playlist or manifest)."
            MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "This device cannot play the stream's format or codec."
            MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "The stream timed out. The server may be offline or the token expired."
            MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK -> "The stream is not valid for playback."
            else -> when (what) {
                MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "The streaming service dropped the connection."
                MediaPlayer.MEDIA_ERROR_UNKNOWN -> "Playback error ($what/$extra)."
                else -> "Playback error ($what/$extra)."
            }
        }
}
