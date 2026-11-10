package ph.pbb.live.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import ph.pbb.live.R
import ph.pbb.live.config.CameraFeed
import ph.pbb.live.player.LivePlayer
import ph.pbb.live.player.MediaLivePlayer
import ph.pbb.live.player.PlaybackState
import ph.pbb.live.util.AspectFitFrameLayout
import ph.pbb.live.util.dp
import ph.pbb.live.util.onTvClick

/**
 * One 16:9 camera tile in the ALL multi-view grid.
 *
 * Grid audio policy: every playing tile is muted except the one with focus, so at most
 * one feed is audible at a time — the rest render silently, CCTV-style.
 */
class CameraTileView @JvmOverloads constructor(
    context: Context,
    private val showPlaceholderWhenIdle: Boolean = true
) : FrameLayout(context) {

    var feed: CameraFeed? = null
        private set

    /** Fullscreen request from the host (OK pressed on a live tile). */
    var onOpenFullscreen: ((CameraTileView) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val aspectFit = AspectFitFrameLayout(context)
    private val surfaceView = SurfaceView(context)

    private val labelBar = LinearLayout(context)
    private val statusDot = View(context)
    private val labelView = TextView(context)

    private val placeholder = LinearLayout(context)
    private val placeholderTitle = TextView(context)
    private val placeholderSub = TextView(context)
    private val spinner = ProgressBar(context)

    private var player: MediaLivePlayer? = null
    private var surfaceReady = false
    private var surface: Surface? = null
    private var playbackStarted = false
    private var focused = false

    init {
        aspectFit.addView(
            surfaceView,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        addView(aspectFit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Bottom label strip over the video.
        labelBar.orientation = LinearLayout.HORIZONTAL
        labelBar.gravity = Gravity.CENTER_VERTICAL
        labelBar.setPadding(dp(context, 10f), dp(context, 6f), dp(context, 10f), dp(context, 6f))
        labelBar.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#D90E0E12"))
        )
        statusDot.layoutParams = LinearLayout.LayoutParams(dp(context, 8f), dp(context, 8f))
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#8E8E98"))
        }
        labelView.textSize = 12.5f
        labelView.setTextColor(Color.WHITE)
        labelView.maxLines = 1
        labelView.setPadding(dp(context, 8f), 0, 0, 0)
        labelBar.addView(statusDot)
        labelBar.addView(labelView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(labelBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        buildPlaceholder()

        isFocusable = true
        isFocusableInTouchMode = false
        onTvClick { onOpenFullscreen?.invoke(this) }
        applyFocusVisual()

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                surface = holder.surface
                val f = feed
                if (f != null && f.isConfigured && player == null) attachPlayer(f)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                surface = null
                playbackStarted = false
                player?.release()
                player = null
            }
        })
    }

    fun bind(feed: CameraFeed) {
        this.feed = feed
        playbackStarted = false
        player?.release()
        player = null
        labelView.text = feed.shortLabel.ifBlank { feed.displayName() }
        updateStatus(feed)
        if (feed.isConfigured) {
            showPlaceholder(show = false)
            spinner.visibility = VISIBLE
            if (surfaceReady) attachPlayer(feed)
        } else {
            spinner.visibility = GONE
            showUnconfiguredState(feed)
        }
        applyFocusVisual()
    }

    fun releasePlayer() {
        player?.release()
        player = null
        playbackStarted = false
    }

    fun isPlayable(): Boolean = feed?.isConfigured == true

    /** [multi] = true when the host allows simultaneous playback; tiles handle their own mute. */
    fun setGridActive(multi: Boolean) {
        if (!multi) {
            // Single-stream mode: only the focused tile keeps a live player.
            if (hasFocus()) attachPlayerIfNeeded() else releasePlayer()
            showPlaceholder(show = !hasFocus() && feed?.isConfigured == true, standby = true)
        } else {
            attachPlayerIfNeeded()
            showPlaceholder(show = false)
        }
        applyMutePolicy()
    }

    fun applyMutePolicy() {
        player?.mute(!focused) // focused tile carries the audio
    }

    private fun attachPlayerIfNeeded() {
        val f = feed ?: return
        if (!f.isConfigured) return
        if (player == null && surfaceReady) attachPlayer(f)
    }

    private fun attachPlayer(feed: CameraFeed) {
        if (!surfaceReady || !feed.isConfigured) return
        val p = player ?: MediaLivePlayer().also { mp ->
            player = mp
            mp.listener = object : LivePlayer.Listener {
                override fun onState(state: PlaybackState) {
                    when (state) {
                        PlaybackState.PLAYING, PlaybackState.BUFFERING -> {
                            playbackStarted = true
                            spinner.visibility = GONE
                            showPlaceholder(show = false)
                        }
                        PlaybackState.CONNECTING -> {
                            spinner.visibility = VISIBLE
                        }
                        else -> Unit
                    }
                }

                override fun onError(message: String) {
                    spinner.visibility = GONE
                    showErrorState()
                }

                override fun onVideoSizeChanged(width: Int, height: Int) {
                    if (height > 0) aspectFit.aspectRatio = width.toFloat() / height.toFloat()
                }
            }
        }
        surface?.let { p.setSurface(it) }
        p.mute(!focused)
        p.load(feed.url)
    }

    private fun buildPlaceholder() {
        placeholder.orientation = LinearLayout.VERTICAL
        placeholder.gravity = Gravity.CENTER
        placeholder.setBackgroundColor(Color.parseColor("#15151B"))

        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_camera)
            setColorFilter(Color.parseColor("#4C4C58"))
        }
        placeholder.addView(icon, LinearLayout.LayoutParams(dp(context, 44f), dp(context, 44f)))

        placeholderTitle.textSize = 11.5f
        placeholderTitle.setTextColor(Color.parseColor("#C9C9D1"))
        placeholderTitle.letterSpacing = 0.14f
        placeholderTitle.setPadding(0, dp(context, 8f), 0, dp(context, 3f))
        placeholder.addView(placeholderTitle)

        placeholderSub.textSize = 10.5f
        placeholderSub.setTextColor(Color.parseColor("#75757F"))
        placeholder.addView(placeholderSub)

        addView(placeholder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(spinner, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        spinner.visibility = GONE
    }

    private fun showPlaceholder(show: Boolean, standby: Boolean = false) {
        placeholder.visibility = if (show) VISIBLE else GONE
        if (show) {
            placeholderTitle.text = "STANDBY"
            placeholderSub.text = "Focused tile plays"
        }
        if (standby) placeholder.alpha = 0.85f else placeholder.alpha = 1f
        labelBar.visibility = VISIBLE
    }

    private fun showUnconfiguredState(feed: CameraFeed) {
        placeholder.visibility = VISIBLE
        placeholderTitle.text = when {
            feed.hasDrm -> "DRM PROTECTED"
            !feed.enabled -> "DISABLED"
            else -> "NOT CONFIGURED"
        }
        placeholderSub.text = if (feed.demo) "Demo unavailable" else "Set this feed in streams.json"
        placeholder.alpha = 1f
    }

    private fun showErrorState() {
        placeholder.visibility = VISIBLE
        placeholderTitle.text = "NO SIGNAL"
        placeholderSub.text = "OK to retry in fullscreen"
        placeholder.alpha = 1f
    }

    private fun updateStatus(feed: CameraFeed) {
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                when {
                    feed.isConfigured && feed.demo -> Color.parseColor("#B58900")
                    feed.isConfigured -> Color.parseColor("#FF3B30")
                    else -> Color.parseColor("#8E8E98")
                }
            )
        }
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gained, direction, previouslyFocusedRect)
        focused = gained
        applyMutePolicy()
        applyFocusVisual()
    }

    private fun applyFocusVisual() {
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 10f).toFloat()
            if (focused) {
                setColor(Color.parseColor("#1E1E26"))
                setStroke(dp(context, 3f), resources.getColor(R.color.focus_ring, null))
            } else {
                setColor(Color.parseColor("#15151B"))
                setStroke(dp(context, 1.5f), resources.getColor(R.color.border, null))
            }
        }
    }
}
