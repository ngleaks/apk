package ph.pbb.live.ui.screens

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import ph.pbb.live.R
import ph.pbb.live.config.CameraFeed
import ph.pbb.live.ui.PlayerStageView
import ph.pbb.live.ui.TvScreen
import ph.pbb.live.util.dp
import ph.pbb.live.util.onTvClick

/**
 * Fullscreen player overlay: used for Dining/External feeds and for fullscreen from ALL.
 * Backdrop is a dimmed scrim; Back (or the close button) returns to the previous screen
 * with its selection restored.
 */
class PlayerOverlayScreen(
    context: Context,
    private val feed: CameraFeed,
    private val demoFeed: CameraFeed?,
    onClose: () -> Unit,
    onDemoRequested: (CameraFeed) -> Unit,
    onStateChanged: ((ph.pbb.live.player.PlaybackState) -> Unit)? = null
) : FrameLayout(context), TvScreen {

    override val view: FrameLayout get() = this

    private val stage = PlayerStageView(context)
    private val closeButton: ImageButton

    init {
        setBackgroundColor(Color.parseColor("#F20E0E12"))
        isFocusable = false

        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stage.onDemoRequested = { demoFeed?.let(onDemoRequested) }
        stage.onStateChanged = onStateChanged

        closeButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 24f).toFloat()
                setColor(Color.parseColor("#6626262E"))
            }
            setPadding(dp(context, 11f), dp(context, 11f), dp(context, 11f), dp(context, 11f))
            contentDescription = "Close fullscreen"
            onTvClick(onClose)
        }
        addView(closeButton, LayoutParams(dp(context, 48f), dp(context, 48f), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(context, 14f), dp(context, 16f), 0)
        })
    }

    override fun onShown() {
        stage.bind(feed)
        focusContent()
    }

    override fun onHidden() {
        stage.releasePlayer()
    }

    override fun handlesBack(): Boolean = true

    override fun onOk(): Boolean = stage.onOk()

    override fun focusContent() {
        stage.requestFocus()
        // If a blocking panel (error / not configured) is up, move focus to its action.
        stage.focusPrimaryAction()
    }
}
