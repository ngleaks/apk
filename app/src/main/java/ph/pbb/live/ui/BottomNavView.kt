package ph.pbb.live.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ph.pbb.live.R
import ph.pbb.live.config.FeedSection
import ph.pbb.live.util.capsLabel
import ph.pbb.live.util.dp
import ph.pbb.live.util.onTvClick

/**
 * Bottom navigation bar: exactly four sections — MAIN LIVE, DINING, EXTERNAL, ALL.
 *
 * Auto-hide behavior (see README "Auto-hiding navigation"):
 *  - shown on launch, hidden with a fade + slide after 4 s without interaction;
 *  - D-pad DOWN on the content reveals it and moves focus to the current section;
 *  - while a nav item has focus it never hides;
 *  - OK selects the focused section; LEFT/RIGHT move between items;
 *  - the bar is an overlay (content is always full-screen), so hiding it never leaves a gap.
 */
class BottomNavView(context: Context) : FrameLayout(context) {

    var onSectionSelected: ((Int) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val row = LinearLayout(context)
    private val items = mutableListOf<NavItemView>()
    private var selectedIndex = 0
    private var shown = true
    private val autoHide = Runnable { hide(animated = true) }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        // Translucent dark scrim behind the bar; content underneath stays full-bleed.
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#E60E0E12"), Color.parseColor("#F20E0E12")))
        setPadding(0, dp(context, 26f), 0, 0)

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        }
        addView(row)

        val sections = FeedSection.entries
        val icons = intArrayOf(R.drawable.ic_nav_main, R.drawable.ic_nav_dining, R.drawable.ic_nav_external, R.drawable.ic_nav_all)
        sections.forEachIndexed { index, section ->
            val item = NavItemView(context, section.navLabel, icons[index]) {
                onSectionSelected?.invoke(index)
            }
            item.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(context, 6f), 0, dp(context, 6f), dp(context, 10f))
            }
            row.addView(item)
            items += item
        }
        setSelectedIndex(0)
    }

    fun setSelectedIndex(index: Int) {
        selectedIndex = index.coerceIn(items.indices)
        items.forEachIndexed { i, item -> item.setActive(i == selectedIndex) }
    }

    fun selected(): Int = selectedIndex

    fun isBarShown(): Boolean = shown

    fun isNavFocused(): Boolean = hasFocus()

    /** Show the bar; resets the auto-hide countdown. Focus is NOT stolen unless requested. */
    fun show(animated: Boolean = true, focusItem: Boolean = false) {
        handler.removeCallbacks(autoHide)
        shown = true
        if (animated) {
            alpha = 0f
            translationY = dp(context, 24f).toFloat()
            visibility = VISIBLE
            animate().alpha(1f).translationY(0f).setDuration(220).start()
        } else {
            visibility = VISIBLE
            alpha = 1f
            translationY = 0f
            animate().cancel()
        }
        if (focusItem) focusedItem().requestFocus()
        scheduleAutoHide()
    }

    /** Hide the bar (after 4 s of inactivity or explicit dismissal). */
    fun hide(animated: Boolean = true) {
        handler.removeCallbacks(autoHide)
        if (!shown) return
        shown = false
        val finish: () -> Unit = {
            visibility = GONE
            translationY = 0f
            alpha = 1f
        }
        if (animated) {
            animate().alpha(0f).translationY(dp(context, 24f).toFloat()).setDuration(200)
                .withEndAction(finish).start()
        } else {
            visibility = GONE
        }
    }

    /** Called on every remote interaction. Keeps the bar up; never hides while focused. */
    fun noteInteraction() {
        if (!shown) return
        if (hasFocus()) {
            handler.removeCallbacks(autoHide)
            return
        }
        scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHide)
        if (hasFocus()) return // never auto-hide under the user's finger on the remote
        handler.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    fun focusSelected() = focusedItem().requestFocus()

    private fun focusedItem(): NavItemView = items[selectedIndex]

    fun release() {
        handler.removeCallbacks(autoHide)
    }

    // ---- one navigation item --------------------------------------------------------

    private inner class NavItemView(
        context: Context,
        label: String,
        iconRes: Int,
        onClick: () -> Unit
    ) : LinearLayout(context) {

        private val icon = ImageView(context)
        private val text = TextView(context)
        private val underline = View(context)
        private var selected = false

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 8f), dp(context, 8f), dp(context, 8f), dp(context, 4f))
            isFocusable = true
            isFocusableInTouchMode = false
            onTvClick(onClick)

            icon.setImageResource(iconRes)
            icon.layoutParams = LayoutParams(dp(context, 26f), dp(context, 26f))
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            addView(icon)

            text.text = label
            text.capsLabel(11.5f, Color.parseColor("#B9B9C2"), 0.12f)
            text.gravity = Gravity.CENTER
            text.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 5f)
            }
            addView(text)

            underline.layoutParams = LayoutParams(dp(context, 34f), dp(context, 3f)).apply {
                topMargin = dp(context, 5f)
            }
            underline.background = rounded(Color.TRANSPARENT, 2f)
            addView(underline)

            applyVisualState()
        }

        fun setActive(value: Boolean) {
            selected = value
            applyVisualState()
        }

        override fun onFocusChanged(gained: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
            super.onFocusChanged(gained, direction, previouslyFocusedRect)
            // Leaving the bar: restart the 4-second countdown.
            if (hasFocus()) handler.removeCallbacks(autoHide) else scheduleAutoHide()
            applyVisualState()
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            // LEFT/RIGHT stay inside the bar (default focus search does this, but be explicit).
            return super.dispatchKeyEvent(event)
        }

        private fun applyVisualState() {
            val focused = hasFocus()
            val accent = resources.getColor(R.color.accent, null)
            val primary = resources.getColor(R.color.text_primary, null)
            val dim = resources.getColor(R.color.text_secondary, null)

            background = rounded(
                if (focused) Color.parseColor("#26262E") else Color.TRANSPARENT,
                12f
            )
            val tint = when {
                focused -> primary
                selected -> primary
                else -> dim
            }
            icon.setColorFilter(tint)
            text.setTextColor(tint)
            underline.background = rounded(
                when {
                    focused -> primary
                    selected -> accent
                    else -> Color.TRANSPARENT
                },
                2f
            )
        }

        private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
            GradientDrawable().apply {
                cornerRadius = dp(context, radiusDp).toFloat()
                setColor(color)
            }
    }

    companion object {
        const val AUTO_HIDE_MS = 4_000L
    }
}
