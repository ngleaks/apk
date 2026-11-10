package ph.pbb.live.ui.screens

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import ph.pbb.live.R
import ph.pbb.live.config.CameraFeed
import ph.pbb.live.config.FeedSection
import ph.pbb.live.config.Prefs
import ph.pbb.live.ui.CameraTileView
import ph.pbb.live.ui.TvScreen
import ph.pbb.live.util.dp

/**
 * ALL — professional CCTV-style multi-view.
 *
 *  - 2×2 grid, 16:9 tiles wherever the screen allows, up to four feeds per page
 *    (extra feeds become pages: LEFT/RIGHT at the row edge flips pages);
 *  - up to four simultaneous players unless the device is low-RAM or single-stream mode was
 *    selected in prefs (grid_mode=single);
 *  - only the focused tile plays with audio; the rest are muted, CCTV-style;
 *  - OK opens the highlighted feed fullscreen; Back returns here with focus restored;
 *  - the last focused tile is remembered across launches (Prefs.last_all_index).
 */
class AllGridScreen(
    context: Context,
    feeds: List<CameraFeed>,
    private val prefs: Prefs,
    private val lowRam: Boolean,
    onOpenFeed: (feed: CameraFeed, absoluteIndex: Int) -> Unit
) : FrameLayout(context), TvScreen {

    override val view: FrameLayout get() = this

    private val gridFeeds: List<CameraFeed> = feeds.filter { it.section == FeedSection.ALL }
    private val pages = ((gridFeeds.size + 3) / 4).coerceAtLeast(1)

    private val gridRoot = LinearLayout(context)      // vertical: rows + hint row
    private val rows = LinearLayout(context)          // two weighted rows
    private val row1 = LinearLayout(context)
    private val row2 = LinearLayout(context)
    private val hint = TextView(context)
    private val pageDots = LinearLayout(context)
    private val pageText = TextView(context)

    private val tiles = mutableListOf<CameraTileView>()

    private var pageIndex = 0

    init {
        setBackgroundColor(resources.getColor(R.color.background, null))

        gridRoot.orientation = LinearLayout.VERTICAL
        gridRoot.setPadding(dp(context, 18f), dp(context, 14f), dp(context, 18f), dp(context, 14f))
        addView(gridRoot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        rows.orientation = LinearLayout.VERTICAL
        gridRoot.addView(rows, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        row1.orientation = LinearLayout.HORIZONTAL
        row2.orientation = LinearLayout.HORIZONTAL
        rows.addView(row1, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        rows.addView(row2, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(context, 10f) })

        repeat(4) { index ->
            val tile = CameraTileView(context)
            tile.onOpenFullscreen = { t ->
                val absolute = absoluteIndexOfTile(t)
                if (absolute != null) {
                    prefs.setLastAllIndex(absolute)
                    gridFeeds.getOrNull(absolute)?.let { onOpenFeed(it, absolute) }
                }
            }
            // Edge page flip: LEFT on a left-column tile / RIGHT on a right-column tile.
            tile.setOnKeyListener { view, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val col = if (index % 2 == 0) 0 else 1
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (col == 0 && pageIndex > 0) { changePage(pageIndex - 1, col = 0, row = index / 2); true } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (col == 1 && pageIndex < pages - 1) { changePage(pageIndex + 1, col = 1, row = index / 2); true } else false
                    else -> false
                }
            }
            tile.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    absoluteIndexOfTile(tile)?.let { prefs.setLastAllIndex(it) }
                    applyGridPlaybackPolicy()
                }
            }
            tiles += tile
        }

        // Bottom hint + page indicator, always subtle.
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 10f), 0, 0)
        }
        hint.text = "D-PAD — MOVE    OK — FULLSCREEN    DOWN — MENU"
        hint.setTextColor(resources.getColor(R.color.text_tertiary, null))
        hint.textSize = 10.5f
        hint.letterSpacing = 0.08f
        bottom.addView(hint, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        pageText.textSize = 10.5f
        pageText.setTextColor(resources.getColor(R.color.text_tertiary, null))
        pageText.setPadding(0, 0, dp(context, 8f), 0)
        bottom.addView(pageText)
        pageDots.orientation = LinearLayout.HORIZONTAL
        bottom.addView(pageDots)
        gridRoot.addView(bottom)

        pageIndex = 0
        renderPage(restoreFocus = false)
    }

    // ------------------------------------------------------------------ page map

    private fun absoluteIndexOfTile(tile: CameraTileView): Int? {
        val i = tiles.indexOf(tile)
        if (i < 0) return null
        val absolute = pageIndex * 4 + i
        return if (absolute < gridFeeds.size) absolute else null
    }

    private fun tilesForPage(page: Int): List<CameraFeed?> {
        val start = page * 4
        return (0 until 4).map { i -> gridFeeds.getOrNull(start + i) }
    }

    private fun changePage(newPage: Int, col: Int, row: Int) {
        pageIndex = newPage.coerceIn(0, pages - 1)
        renderPage(restoreFocus = false)
        val spot = row * 2 + col
        tiles.getOrNull(spot)?.takeIf { it.visibility == VISIBLE }?.requestFocus()
    }

    private fun renderPage(restoreFocus: Boolean) {
        val slice = tilesForPage(pageIndex)
        row1.removeAllViews()
        row2.removeAllViews()
        slice.forEachIndexed { i, feed ->
            val tile = tiles[i]
            val targetRow = if (i < 2) row1 else row2
            tile.layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                if (i % 2 == 1) leftMargin = dp(context, 10f)
            }
            targetRow.addView(tile)
            if (feed != null) {
                tile.visibility = VISIBLE
                tile.bind(feed)
            } else {
                tile.visibility = INVISIBLE // keep 2×2 geometry; empty slot is inert
            }
        }
        renderDots()
    }

    private fun renderDots() {
        pageDots.removeAllViews()
        pageText.text = if (pages > 1) "PAGE ${pageIndex + 1}/$pages" else ""
        pageDots.visibility = if (pages > 1) VISIBLE else GONE
        pageText.visibility = if (pages > 1) VISIBLE else GONE
        repeat(pages) { i ->
            val dot = View(context)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == pageIndex) Color.WHITE else Color.parseColor("#4A4A55"))
            }
            pageDots.addView(dot, LinearLayout.LayoutParams(dp(context, 7f), dp(context, 7f)).apply {
                leftMargin = dp(context, 6f)
            })
        }
    }

    private fun gridMultiAllowed(): Boolean {
        return when (prefs.gridMode()) {
            "multi" -> true
            "single" -> false
            else -> !lowRam // auto: capable devices play up to 4, low-RAM boxes use single-active mode
        }
    }

    private fun applyGridPlaybackPolicy() {
        val multi = gridMultiAllowed()
        for (tile in tiles) {
            if (tile.visibility != VISIBLE) continue
            tile.setGridActive(multi)
        }
        for (tile in tiles) tile.applyMutePolicy()
    }

    // ------------------------------------------------------------------ screen

    override fun onShown() {
        // Restore last-focused camera (page + tile), then apply playback policy.
        val last = prefs.lastAllIndex().coerceIn(0, (gridFeeds.size - 1).coerceAtLeast(0))
        pageIndex = last / 4
        renderPage(restoreFocus = false)
        val spot = last % 4
        tiles.getOrNull(spot)?.takeIf { it.visibility == VISIBLE }?.requestFocus()
        applyGridPlaybackPolicy()
    }

    override fun onHidden() {
        for (tile in tiles) tile.releasePlayer()
    }

    override fun onOk(): Boolean = false // tiles handle OK via click listener

    override fun focusContent() {
        val last = prefs.lastAllIndex().coerceIn(0, (gridFeeds.size - 1).coerceAtLeast(0))
        pageIndex = last / 4
        renderPage(restoreFocus = false)
        tiles.getOrNull(last % 4)?.takeIf { it.visibility == VISIBLE }?.requestFocus()
        applyGridPlaybackPolicy()
    }
}
