package ph.pbb.live.ui.screens

import android.content.Context
import android.widget.FrameLayout
import ph.pbb.live.config.CameraFeed
import ph.pbb.live.config.FeedSection
import ph.pbb.live.ui.PlayerStageView
import ph.pbb.live.ui.TvScreen

/**
 * MAIN LIVE — the launch screen. Plays the catalog's main feed; when none is configured it
 * shows the not-configured panel with a one-key path to the public demo stream.
 */
class MainLiveScreen(
    context: Context,
    feeds: List<CameraFeed>,
    onDemoRequested: (CameraFeed) -> Unit
) : FrameLayout(context), TvScreen {

    override val view: FrameLayout get() = this

    private val stage = PlayerStageView(context)
    private val mainFeed: CameraFeed =
        feeds.firstOrNull { it.section == FeedSection.MAIN }
            ?: CameraFeed("main_live", "PBB Main Live", "MAIN LIVE", FeedSection.MAIN, "")
    private val demoFeed: CameraFeed? = feeds.firstOrNull { it.demo && it.isConfigured }

    init {
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stage.onDemoRequested = { demoFeed?.let(onDemoRequested) }
    }

    override fun onShown() {
        stage.bind(mainFeed)
        stage.visibility = VISIBLE
        focusContent()
    }

    override fun onHidden() {
        stage.releasePlayer()
    }

    override fun onOk(): Boolean = stage.onOk()

    override fun focusContent() {
        if (stage.visibility == VISIBLE) {
            // If a blocking panel is up, focus its primary action; otherwise the stage itself.
            stage.focusPrimaryAction()
            if (!stage.hasFocus()) stage.requestFocus()
        }
    }

    fun toggleChrome() = stage.toggleChrome()
    fun isChromeVisible(): Boolean = stage.isChromeVisible()
}
