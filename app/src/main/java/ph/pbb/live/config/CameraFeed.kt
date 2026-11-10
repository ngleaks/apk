package ph.pbb.live.config

/** Top-level sections, in fixed navigation order. */
enum class FeedSection(val id: String, val navLabel: String, val title: String) {
    MAIN("main", "MAIN LIVE", "Main Live"),
    DINING("dining", "DINING", "Dining cameras"),
    EXTERNAL("external", "EXTERNAL", "External cameras"),
    ALL("all", "ALL", "All cameras");

    companion object {
        fun fromId(id: String?): FeedSection =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: ALL
    }
}

/**
 * One camera feed from the catalog.
 *
 * @param url authorized playback URL (HLS .m3u8, DASH .mpd or progressive file). Blank means
 *            "not configured" — the UI shows a placeholder instead of fake video.
 * @param format auto | hls | dash | progressive (informational; the player sniffs the URL).
 * @param demo   true when this is a public test stream, shown with a DEMO badge.
 * @param hasDrm true when a `drm` object was present in streams.json. This framework-only
 *               build cannot decrypt DRM'd feeds; the UI explains that instead of failing silently.
 */
data class CameraFeed(
    val id: String,
    val label: String,
    val shortLabel: String,
    val section: FeedSection,
    val url: String,
    val format: String = "auto",
    val enabled: Boolean = true,
    val demo: Boolean = false,
    val hasDrm: Boolean = false
) {
    val isConfigured: Boolean get() = enabled && url.isNotBlank() && !hasDrm

    /** What a viewer should read on a tile/card before pressing OK. */
    val statusLabel: String
        get() = when {
            !enabled -> "DISABLED"
            hasDrm -> "DRM — MEDIA3 BUILD REQUIRED"
            url.isBlank() -> "NOT CONFIGURED"
            demo -> "DEMO"
            else -> "LIVE"
        }

    fun displayName(): String = label.ifBlank { id }
}
