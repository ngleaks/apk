package ph.pbb.live.player

/** Lifecycle states every playback surface in the app renders from. */
enum class PlaybackState {
    IDLE,        // nothing loaded
    CONNECTING,  // setDataSource done, waiting for onPrepared
    BUFFERING,   // playing but stalled (MediaInfo 701)
    PLAYING,
    PAUSED,
    ENDED,       // stream delivered an end marker (rare for live); retry makes sense
    ERROR        // unrecoverable for now; retry reloads
}
