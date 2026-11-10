package ph.pbb.live.player

import android.view.Surface

/**
 * Player abstraction used by every screen.
 *
 * The built-in implementation is [MediaLivePlayer], backed by the platform MediaPlayer
 * (zero dependencies, plays HLS/MPEG-DASH/progressive where the device supports them).
 * To add Media3/ExoPlayer — e.g. for ClearKey/Widevine or LL-HLS — implement this
 * interface in one new class and swap it in `PlayerStageView`; the UI never changes.
 */
interface LivePlayer {

    interface Listener {
        fun onState(state: PlaybackState)
        /** Human-readable, token-free error text. Never contains URLs. */
        fun onError(message: String)
        /** Called once the surface reports the decoded video size. */
        fun onVideoSizeChanged(width: Int, height: Int) {}
    }

    var listener: Listener?

    /** Attach (or detach with null) the render surface. Detaching releases the underlying player. */
    fun setSurface(surface: Surface?)

    /** Stop any current playback and load [url]. Safe to call repeatedly. */
    fun load(url: String)

    fun play()
    fun pause()
    fun toggle()

    /** Grid tiles start muted; the focused tile may be unmuted by the screen. */
    fun mute(muted: Boolean)
    val isMuted: Boolean
    val isPlaying: Boolean

    /** Release everything; the instance must not be used afterwards. */
    fun release()
}
