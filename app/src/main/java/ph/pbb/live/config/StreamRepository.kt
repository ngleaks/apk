package ph.pbb.live.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader

/**
 * Loads the camera catalog.
 *
 * Resolution order (first that parses wins, per-field tolerant):
 *   1. <externalFilesDir>/streams.json  — drop-in override, no rebuild or root required
 *      (`adb push my-streams.json "$(adb shell pm path ph.pbb.live)"`-style, see README)
 *   2. assets/streams.json              — built-in catalog
 *
 * The built-in catalog intentionally ships with EMPTY authorized URLs (PBB's DASH endpoints
 * are ABS-CBN's property and are not licensed for rebroadcast). Missing URLs are first-class:
 * every screen has a "NOT CONFIGURED" state. The one enabled entry is a clearly labelled
 * public Apple bipbop HLS demo so the playback pipeline can be verified immediately.
 */
object StreamRepository {

    /** Non-null when the last load had to fall back to built-in defaults. Shown in empty states. */
    @Volatile
    var lastWarning: String? = null
        private set

    private const val OVERRIDE_NAME = "streams.json"
    private const val ASSET_NAME = "streams.json"

    fun load(context: Context): List<CameraFeed> {
        val external = context.getExternalFilesDir(null)?.let { File(it, OVERRIDE_NAME) }
        if (external != null && external.isFile) {
            try {
                val parsed = parse(external.readText(Charsets.UTF_8), source = OVERRIDE_NAME)
                if (parsed != null) {
                    lastWarning = null
                    return parsed
                }
            } catch (e: Exception) {
                lastWarning = "Override streams.json unreadable (${e.message}); using built-in catalog."
            }
        }
        try {
            context.assets.open(ASSET_NAME).use { input ->
                val parsed = parse(InputStreamReader(input, Charsets.UTF_8).readText(), source = ASSET_NAME)
                if (parsed != null) {
                    if (lastWarning == null) lastWarning = null
                    return parsed
                }
            }
        } catch (e: Exception) {
            lastWarning = "Built-in streams.json unreadable (${e.message}); using emergency defaults."
        }
        return emergencyDefaults()
    }

    private fun parse(text: String, source: String): List<CameraFeed>? {
        return try {
            val root = JSONObject(text)
            val arr: JSONArray? = root.optJSONArray("cameras")
            if (arr == null) {
                lastWarning = "$source: missing 'cameras' array; using built-in catalog."
                return null
            }
            val out = ArrayList<CameraFeed>(arr.length())
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val rawUrl = o.optString("url", "").trim()
                if (rawUrl.isNotEmpty() &&
                    !rawUrl.startsWith("https://", ignoreCase = true) &&
                    !rawUrl.startsWith("http://", ignoreCase = true)
                ) {
                    // Reject non-HTTP(S) schemes outright: never hand file:/content:/data: URLs to the player.
                    lastWarning = "$source: camera #${i + 1} has a non-HTTP(S) URL and was skipped."
                    continue
                }
                val id = o.optString("id", "cam_$i").ifBlank { "cam_$i" }
                if (!seen.add(id)) continue // de-dup on id
                val label = o.optString("label", "Camera ${i + 1}").ifBlank { "Camera ${i + 1}" }
                out += CameraFeed(
                    id = id,
                    label = label,
                    shortLabel = o.optString("shortLabel", label).ifBlank { label }.take(28),
                    section = FeedSection.fromId(o.optString("section")),
                    url = rawUrl,
                    format = o.optString("format", "auto").lowercase(),
                    enabled = o.optBoolean("enabled", true),
                    demo = o.optBoolean("demo", false),
                    hasDrm = o.optJSONObject("drm") != null
                )
            }
            if (out.isEmpty()) {
                lastWarning = "$source: no usable cameras; using built-in catalog."
                null
            } else {
                out
            }
        } catch (e: Exception) {
            lastWarning = "$source: parse error (${e.message}); using built-in catalog."
            null
        }
    }

    /** Kept in sync with assets/streams.json; used only if the asset itself is corrupt. */
    private fun emergencyDefaults(): List<CameraFeed> = listOf(
        CameraFeed("main_live", "PBB Main Live", "MAIN LIVE", FeedSection.MAIN, ""),
        CameraFeed("dining", "PBB Dining Area", "DINING", FeedSection.DINING, ""),
        CameraFeed("external_den", "PBB External Den", "EXTERNAL", FeedSection.EXTERNAL, ""),
        CameraFeed("all_access", "PBB All Access", "ALL ACCESS", FeedSection.ALL, ""),
        CameraFeed(
            "demo_bipbop",
            "Demo · Apple bipbop (public HLS test stream)",
            "DEMO · BIPBOP",
            FeedSection.ALL,
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
            format = "hls",
            demo = true
        )
    )

    /** The demo feed, if present and playable; used by the "Play public demo" fallback buttons. */
    fun demoFeed(feeds: List<CameraFeed>): CameraFeed? =
        feeds.firstOrNull { it.demo && it.isConfigured }

    fun bySection(feeds: List<CameraFeed>, section: FeedSection): List<CameraFeed> =
        feeds.filter { it.section == section }
}
