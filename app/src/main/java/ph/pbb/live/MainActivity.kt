package ph.pbb.live

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import ph.pbb.live.config.CameraFeed
import ph.pbb.live.config.FeedSection
import ph.pbb.live.config.Prefs
import ph.pbb.live.config.StreamRepository
import ph.pbb.live.ui.BottomNavView
import ph.pbb.live.ui.TvScreen
import ph.pbb.live.ui.screens.AllGridScreen
import ph.pbb.live.ui.screens.MainLiveScreen
import ph.pbb.live.ui.screens.PlayerOverlayScreen
import ph.pbb.live.ui.screens.SectionListScreen

/**
 * Single-activity host: MAIN LIVE / DINING / EXTERNAL / ALL + fullscreen camera overlay,
 * with the auto-hiding bottom navigation bar described in the README.
 *
 * Remote model (no touch needed, anywhere in the app):
 *  - D-pad DOWN    reveal the navigation bar and focus it (content normally consumes DOWN first)
 *  - D-pad LEFT/RIGHT  move within the focused bar/list/grid
 *  - D-pad UP      leave the bar, back to content (bar hides after 4 s)
 *  - OK            activate the focused control; on video surfaces, toggle chrome + pause
 *  - BACK          close overlay / hide bar / exit
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var container: FrameLayout
    private lateinit var nav: BottomNavView

    private lateinit var prefs: Prefs
    private lateinit var feeds: List<CameraFeed>

    private lateinit var mainScreen: MainLiveScreen
    private lateinit var diningScreen: SectionListScreen
    private lateinit var externalScreen: SectionListScreen
    private lateinit var allScreen: AllGridScreen

    private var overlay: PlayerOverlayScreen? = null
    private var currentSection: FeedSection = FeedSection.MAIN
    private var currentScreen: TvScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = Prefs(this)
        feeds = StreamRepository.load(this)
        StreamRepository.lastWarning?.let {
            Log.w(TAG, it)
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        container = FrameLayout(this)
        nav = BottomNavView(this).apply {
            onSectionSelected = { selectSection(it) }
        }
        root.addView(container, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(nav, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM))
        setContentView(root)

        val demoFeed = StreamRepository.demoFeed(feeds)
        val openDemo: (CameraFeed) -> Unit = { demo -> openFeedOverlay(demo) }
        val openFeed: (CameraFeed) -> Unit = { feed -> openFeedOverlay(feed) }

        mainScreen = MainLiveScreen(this, feeds, openDemo)
        diningScreen = SectionListScreen(this, FeedSection.DINING, feeds, openFeed, openDemo)
        externalScreen = SectionListScreen(this, FeedSection.EXTERNAL, feeds, openFeed, openDemo)
        allScreen = AllGridScreen(this, feeds, prefs, isLowRam()) { feed, _ -> openFeedOverlay(feed) }

        container.addView(mainScreen.view, screenParams())
        container.addView(diningScreen.view, screenParams())
        container.addView(externalScreen.view, screenParams())
        container.addView(allScreen.view, screenParams())

        showSection(FeedSection.MAIN, animated = false)
        // Nav bar is visible at launch; it hides itself after 4 s of inactivity.
        nav.show(animated = false, focusItem = false)

        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleDeepLink(it) }
    }

    // ------------------------------------------------------------- section switching

    private fun showSection(section: FeedSection, animated: Boolean) {
        currentScreen?.onHidden()
        currentSection = section
        val target = screenFor(section)
        for (s in FeedSection.entries) {
            screenFor(s).view.visibility = if (s == section) View.VISIBLE else View.GONE
        }
        if (animated) {
            target.view.alpha = 0f
            target.view.animate().alpha(1f).setDuration(180).start()
        }
        currentScreen = target
        target.onShown()
        nav.setSelectedIndex(section.ordinal)
        target.focusContent()
    }

    fun selectSection(index: Int) {
        val section = FeedSection.entries.getOrNull(index) ?: return
        if (overlay != null) {
            closeOverlay(refocus = false)
        }
        if (section == currentSection && overlay == null) {
            // Re-selecting the current tab: just re-hide the bar and refocus content.
            nav.hide(animated = true)
            currentScreen?.focusContent()
            return
        }
        showSection(section, animated = true)
        nav.noteInteraction() // 4 s countdown restarts; bar hides itself if untouched
    }

    private fun screenFor(section: FeedSection): TvScreen = when (section) {
        FeedSection.MAIN -> mainScreen
        FeedSection.DINING -> diningScreen
        FeedSection.EXTERNAL -> externalScreen
        FeedSection.ALL -> allScreen
    }

    private fun screenParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    // ------------------------------------------------------------- overlay

    private fun openFeedOverlay(feed: CameraFeed) {
        if (overlay != null) return
        currentScreen?.onHidden()
        val demo = StreamRepository.demoFeed(feeds)
        val o = PlayerOverlayScreen(
            context = this,
            feed = feed,
            demoFeed = demo,
            onClose = { closeOverlay(refocus = true) },
            onDemoRequested = { demoFeed ->
                closeOverlay(refocus = false)
                if (demoFeed != null) openFeedOverlay(demoFeed) else showSection(FeedSection.ALL, animated = false)
            }
        )
        container.addView(o.view, screenParams())
        overlay = o
        nav.hide(animated = true)
        o.onShown()
    }

    private fun closeOverlay(refocus: Boolean) {
        val o = overlay ?: return
        o.onHidden()
        container.removeView(o.view)
        overlay = null
        currentScreen?.onShown()
        if (refocus) currentScreen?.focusContent()
    }

    // ------------------------------------------------------------- remote input

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            nav.noteInteraction()
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (nav.hasFocus()) {
                        // DOWN while the bar has focus: hide it and hand focus back to content.
                        hideNav(refocus = true)
                        return true
                    }
                    // Give the content first crack (list rows, grid rows move focus);
                    // if nothing consumed it, reveal the bar.
                    val consumed = super.dispatchKeyEvent(event)
                    if (!consumed) {
                        showNav()
                        return true
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (nav.hasFocus()) {
                        hideNav(refocus = true)
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (nav.isBarShown() || nav.hasFocus()) {
                        hideNav(refocus = true)
                        return true
                    }
                    if (overlay != null) {
                        closeOverlay(refocus = true)
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (nav.hasFocus()) return super.dispatchKeyEvent(event) // bar items handle OK
                    if (currentScreen?.onOk() == true) return true
                    return super.dispatchKeyEvent(event)
                }
                else -> return super.dispatchKeyEvent(event)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showNav() {
        nav.show(animated = true, focusItem = true)
    }

    private fun hideNav(refocus: Boolean) {
        nav.hide(animated = true)
        if (refocus) currentScreen?.focusContent()
    }

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        currentScreen?.onShown()
    }

    override fun onPause() {
        overlay?.let { closeOverlay(refocus = false) }
        currentScreen?.onHidden()
        super.onPause()
    }

    override fun onDestroy() {
        nav.release()
        currentScreen?.onHidden()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
    }

    // ------------------------------------------------------------- deep links

    private fun handleDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "pbblivetv" || data.host != "play") return
        val url = data.getQueryParameter("url")?.take(2000)
            ?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            ?: run {
                Toast.makeText(this, "Deep link ignored: missing or unsupported url parameter", Toast.LENGTH_LONG).show()
                return
            }
        val title = data.getQueryParameter("title")?.take(64) ?: "Stream"
        val feed = CameraFeed(
            id = "deeplink_${url.hashCode()}",
            label = title,
            shortLabel = title.take(24).uppercase(),
            section = FeedSection.ALL,
            url = url
        )
        openFeedOverlay(feed)
    }

    private fun isLowRam(): Boolean =
        (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice ?: false

    companion object {
        private const val TAG = "PBBLive"
    }
}
