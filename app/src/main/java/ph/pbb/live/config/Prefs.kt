package ph.pbb.live.config

import android.content.Context

/** Small SharedPreferences wrapper; everything here is "nice to remember", never required. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("pbb_live", Context.MODE_PRIVATE)

    /** Index (into the ALL-section feed list) of the tile to re-focus when ALL is shown. */
    fun lastAllIndex(): Int = sp.getInt(KEY_LAST_ALL, 0).coerceAtLeast(0)

    fun setLastAllIndex(index: Int) {
        sp.edit().putInt(KEY_LAST_ALL, index.coerceAtLeast(0)).apply()
    }

    /** "auto" (multi unless low-RAM), "multi" (up to 4 simultaneous), "single" (only focused tile). */
    fun gridMode(): String = sp.getString(KEY_GRID_MODE, "auto") ?: "auto"

    fun setGridMode(mode: String) {
        sp.edit().putString(KEY_GRID_MODE, mode).apply()
    }

    companion object {
        private const val KEY_LAST_ALL = "last_all_index"
        private const val KEY_GRID_MODE = "grid_mode"
    }
}
