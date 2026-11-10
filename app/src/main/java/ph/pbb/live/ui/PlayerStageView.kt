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
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
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
 * The reusable "big video" surface used by Main Live and the fullscreen overlay.
 *
 * Owns its MediaPlayer; screens call [bind] to (re)point it at a feed and [releasePlayer]
 * when hidden. Chrome (title, LIVE badge, mute) appears on OK and auto-hides after 4 s.
 */
class PlayerStageView @JvmOverloads constructor(
    context: Context
) : FrameLayout(context) {

    /** Hosts wire this to a demo action when a public demo feed exists in the catalog. */
    var onDemoRequested: (() -> Unit)? = null

    /** Forwarded player state, e.g. so the host can log/measure. */
    var onStateChanged: ((PlaybackState) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val chromeHide = Runnable { hideChrome(animated = true) }

    private val aspectFit = AspectFitFrameLayout(context)
    private val surfaceView = SurfaceView(context)

    // Chrome (top overlay)
    private val chrome = LinearLayout(context)
    private val liveBadge = TextView(context)
    private val titleView = TextView(context)
    private val muteButton = ImageButton(context)
    private var chromeVisible = true

    // Center states
    private val loadingPanel = LinearLayout(context)
    private val loadingText = TextView(context)
    private val pausedPanel = LinearLayout(context)
    private val errorPanel = LinearLayout(context)
    private val errorMessage = TextView(context)
    private val unconfiguredPanel = LinearLayout(context)
    private val unconfiguredTitle = TextView(context)
    private val unconfiguredBody = TextView(context)
    private val demoButton = TextView(context)
    private val retryButton = TextView(context)

    private var player: MediaLivePlayer? = null
    private var currentFeed: CameraFeed? = null
    private var surfaceReady = false
    private var surface: Surface? = null

    // ------------------------------------------------------------------ setup

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = false

        // Video layer
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                surface = holder.surface
                // (Re)attach the current feed — covers re-attach after page swaps.
                val feed = currentFeed
                if (feed != null && feed.isConfigured) startLoad(feed) else Unit
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                surface = null
                player?.release()
                player = null
            }
        })
        aspectFit.addView(
            surfaceView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        addView(aspectFit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        buildChrome()
        buildLoading()
        buildPaused()
        buildError()
        buildUnconfigured()
        showChrome(animated = false)
    }

    // ------------------------------------------------------------- public API

    fun bind(feed: CameraFeed) {
        if (currentFeed?.id == feed.id && player != null) return // no-op rebind
        currentFeed = feed
        player?.release()
        player = null
        aspectFit.aspectRatio = 16f / 9f
        updateBadge(feed)
        titleView.text = feed.displayName()

        when {
            feed.hasDrm -> showUnconfigured(
                title = "DRM PROTECTED FEED",
                body = "This camera is encrypted (ClearKey/Widevine).\n" +
                    "The lightweight built-in player cannot decrypt it.\n" +
                    "Build the app with AndroidX Media3 ExoPlayer and your provider's\n" +
                    "license callback — see README, “DRM-protected feeds”.",
                showDemo = onDemoRequested != null
            )
            !feed.enabled || feed.url.isBlank() -> showUnconfigured(
                title = "NOT CONFIGURED",
                body = "No authorized stream URL is set for this camera.\n" +
                    "Add your HLS/DASH URL to assets/streams.json (or the app's\n" +
                    "streams.json override on the device), then relaunch the app.\n" +
                    "Deep link:  pbblivetv://play?url=<https-url>&title=<name>",
                showDemo = onDemoRequested != null
            )
            else -> {
                hideAllPanels()
                showLoading("Connecting to stream…", scrim = true)
                if (surfaceReady) startLoad(feed) // surface already alive: load now
                showChrome(animated = false)
                scheduleChromeAutoHide()
            }
        }
    }

    fun currentFeed(): CameraFeed? = currentFeed

    fun releasePlayer() {
        main.removeCallbacks(chromeHide)
        player?.release()
        player = null
    }

    /** OK pressed while the stage itself has focus. Toggles play/pause + chrome.
     *  Returns false when a blocking panel is up, so the focused Retry/Demo button gets OK. */
    fun onOk(): Boolean {
        if (errorPanel.visibility == VISIBLE || unconfiguredPanel.visibility == VISIBLE) return false
        val feed = currentFeed
        if (feed == null || !feed.isConfigured) return false
        player?.toggle()
        toggleChrome()
        return true
    }

    fun toggleChrome() {
        if (chromeVisible) hideChrome(animated = true) else showChrome(animated = true)
    }

    fun isChromeVisible(): Boolean = chromeVisible

    fun setChromeVisibleForHost(visible: Boolean) {
        if (visible) showChrome(animated = true) else hideChrome(animated = true)
    }

    fun toggleMute() {
        val p = player ?: return
        p.mute(!p.isMuted)
        applyMuteIcon()
    }

    fun isMuted(): Boolean = player?.isMuted ?: false

    /** Force-focus the primary action when a panel is showing (Retry / Demo). */
    fun focusPrimaryAction() {
        when {
            errorPanel.visibility == VISIBLE -> retryButton.requestFocus()
            unconfiguredPanel.visibility == VISIBLE ->
                if (demoButton.visibility == VISIBLE) demoButton.requestFocus() else unconfiguredPanel.requestFocus()
        }
    }

    // -------------------------------------------------------------- internals

    private fun startLoad(feed: CameraFeed) {
        val p = player ?: MediaLivePlayer().also {
            player = it
            it.listener = object : LivePlayer.Listener {
                override fun onState(state: PlaybackState) {
                    when (state) {
                        PlaybackState.CONNECTING -> showLoading("Connecting to stream…", scrim = true)
                        PlaybackState.BUFFERING -> showLoading("Buffering…", scrim = true)
                        PlaybackState.PLAYING -> { hideAllPanels(); scheduleChromeAutoHide() }
                        PlaybackState.PAUSED -> showPaused()
                        PlaybackState.ENDED -> showError("The stream ended unexpectedly. Check that the live source is still broadcasting.")
                        PlaybackState.IDLE -> Unit
                        PlaybackState.ERROR -> Unit // onError carries the message
                    }
                    onStateChanged?.invoke(state)
                }

                override fun onError(message: String) {
                    showError(message)
                }

                override fun onVideoSizeChanged(width: Int, height: Int) {
                    if (height > 0) aspectFit.aspectRatio = width.toFloat() / height.toFloat()
                }
            }
        }
        surface?.let { p.setSurface(it) }
        p.load(feed.url)
    }

    private fun buildChrome() {
        chrome.orientation = LinearLayout.HORIZONTAL
        chrome.gravity = Gravity.CENTER_VERTICAL
        chrome.setPadding(dp(context, 20f), dp(context, 12f), dp(context, 16f), dp(context, 40f))
        chrome.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#C80E0E12"))
        )
        // Note: gradient here is a scrim, not decoration; keeps text legible over any frame.
        liveBadge.text = "● LIVE"
        liveBadge.setTextColor(Color.WHITE)
        liveBadge.textSize = 11f
        liveBadge.setPadding(dp(context, 12f), dp(context, 4f), dp(context, 12f), dp(context, 4f))
        liveBadge.background = GradientDrawable().apply {
            cornerRadius = dp(context, 20f).toFloat()
            setColor(resources.getColor(R.color.live_red, null))
        }
        chrome.addView(liveBadge)

        titleView.textSize = 17f
        titleView.setTextColor(Color.WHITE)
        titleView.maxLines = 1
        titleView.setPadding(dp(context, 16f), 0, dp(context, 16f), 0)
        chrome.addView(titleView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        muteButton.setImageResource(R.drawable.ic_volume)
        muteButton.background = focusBackground(
            focusedColor = Color.parseColor("#33FFFFFF"),
            normalColor = Color.TRANSPARENT,
            radiusDp = 10f
        )
        muteButton.isFocusable = true
        muteButton.setPadding(dp(context, 10f), dp(context, 10f), dp(context, 10f), dp(context, 10f))
        muteButton.onTvClick {
            toggleMute()
            scheduleChromeAutoHide()
        }
        chrome.addView(muteButton, LinearLayout.LayoutParams(dp(context, 52f), dp(context, 52f)))

        addView(chrome, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    private fun buildLoading() {
        loadingPanel.orientation = LinearLayout.VERTICAL
        loadingPanel.gravity = Gravity.CENTER
        loadingPanel.setBackgroundColor(Color.parseColor("#66000000"))
        val spinner = ProgressBar(context)
        loadingPanel.addView(spinner, LinearLayout.LayoutParams(dp(context, 46f), dp(context, 46f)))
        loadingText.text = "Connecting to stream…"
        loadingText.setTextColor(Color.WHITE)
        loadingText.textSize = 15f
        loadingText.setPadding(0, dp(context, 14f), 0, 0)
        loadingPanel.addView(loadingText)
        loadingPanel.isFocusable = false
        addView(loadingPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        loadingPanel.visibility = GONE
    }

    private fun buildPaused() {
        pausedPanel.orientation = LinearLayout.VERTICAL
        pausedPanel.gravity = Gravity.CENTER
        pausedPanel.setBackgroundColor(Color.parseColor("#55000000"))
        val chip = TextView(context).apply {
            text = "PAUSED"
            setTextColor(Color.WHITE)
            textSize = 15f
            letterSpacing = 0.14f
            setPadding(dp(context, 26f), dp(context, 10f), dp(context, 26f), dp(context, 10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 26f).toFloat()
                setColor(Color.parseColor("#CC1B1B22"))
            }
        }
        pausedPanel.addView(chip)
        val hint = TextView(context).apply {
            text = "OK to resume"
            setTextColor(Color.parseColor("#C9C9D1"))
            textSize = 12.5f
            setPadding(0, dp(context, 12f), 0, 0)
        }
        pausedPanel.addView(hint)
        addView(pausedPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        pausedPanel.visibility = GONE
    }

    private fun buildError() {
        errorPanel.orientation = LinearLayout.VERTICAL
        errorPanel.gravity = Gravity.CENTER
        errorPanel.setBackgroundColor(Color.parseColor("#CC0E0E12"))
        errorPanel.setPadding(dp(context, 48f), dp(context, 24f), dp(context, 48f), dp(context, 24f))

        val title = TextView(context).apply {
            text = "STREAM UNAVAILABLE"
            setTextColor(Color.WHITE)
            textSize = 15f
            letterSpacing = 0.16f
        }
        errorPanel.addView(title)

        errorMessage.text = ""
        errorMessage.setTextColor(Color.parseColor("#C9C9D1"))
        errorMessage.textSize = 13.5f
        errorMessage.gravity = Gravity.CENTER
        errorMessage.maxWidth = dp(context, 560f)
        errorMessage.setPadding(0, dp(context, 10f), 0, dp(context, 22f))
        errorPanel.addView(errorMessage)

        retryButton.text = "RETRY"
        styleActionButton(retryButton, primary = true)
        retryButton.onTvClick { retry() }
        errorPanel.addView(retryButton, LinearLayout.LayoutParams(dp(context, 260f), dp(context, 52f)))
        addView(errorPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        errorPanel.visibility = GONE
    }

    private fun buildUnconfigured() {
        unconfiguredPanel.orientation = LinearLayout.VERTICAL
        unconfiguredPanel.gravity = Gravity.CENTER
        unconfiguredPanel.setBackgroundColor(Color.parseColor("#E60E0E12"))
        unconfiguredPanel.setPadding(dp(context, 48f), dp(context, 24f), dp(context, 48f), dp(context, 24f))

        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_camera)
            setColorFilter(Color.parseColor("#5A5A66"))
            alpha = 0.9f
        }
        unconfiguredPanel.addView(icon, LinearLayout.LayoutParams(dp(context, 72f), dp(context, 72f)))

        unconfiguredTitle.text = "NOT CONFIGURED"
        unconfiguredTitle.setTextColor(Color.WHITE)
        unconfiguredTitle.textSize = 15f
        unconfiguredTitle.letterSpacing = 0.16f
        unconfiguredTitle.setPadding(0, dp(context, 16f), 0, dp(context, 10f))
        unconfiguredPanel.addView(unconfiguredTitle)

        unconfiguredBody.text = ""
        unconfiguredBody.setTextColor(Color.parseColor("#C9C9D1"))
        unconfiguredBody.textSize = 13f
        unconfiguredBody.gravity = Gravity.CENTER
        unconfiguredBody.maxWidth = dp(context, 640f)
        unconfiguredBody.setLineSpacing(dp(context, 2f).toFloat(), 1f)
        unconfiguredPanel.addView(unconfiguredBody)

        demoButton.text = "PLAY PUBLIC DEMO STREAM"
        styleActionButton(demoButton, primary = false)
        demoButton.onTvClick { onDemoRequested?.invoke() }
        unconfiguredPanel.addView(
            demoButton,
            LinearLayout.LayoutParams(dp(context, 400f), dp(context, 52f)).apply { topMargin = dp(context, 22f) }
        )
        addView(unconfiguredPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        unconfiguredPanel.visibility = GONE
    }

    private fun styleActionButton(view: TextView, primary: Boolean) {
        view.gravity = Gravity.CENTER
        view.isFocusable = true
        view.textSize = 14f
        view.letterSpacing = 0.12f
        view.setTextColor(if (primary) Color.WHITE else Color.parseColor("#EDEDF2"))
        view.background = focusBackground(
            focusedColor = if (primary) Color.parseColor("#FF5A4E") else Color.parseColor("#2E2E38"),
            normalColor = if (primary) Color.parseColor("#E04A3F") else Color.parseColor("#23232C"),
            radiusDp = 10f,
            strokeColor = if (primary) Color.TRANSPARENT else Color.parseColor("#3A3A46")
        )
    }

    private fun focusBackground(focusedColor: Int, normalColor: Int, radiusDp: Float, strokeColor: Int = Color.TRANSPARENT): android.graphics.drawable.Drawable {
        val normal = GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(normalColor)
            if (strokeColor != Color.TRANSPARENT) setStroke(dp(context, 1f), strokeColor)
        }
        val focused = GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(focusedColor)
            setStroke(dp(context, 2.5f), Color.WHITE)
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun retry() {
        val feed = currentFeed ?: return
        if (!feed.isConfigured) return
        hideAllPanels()
        showLoading("Reconnecting…", scrim = true)
        player?.release()
        player = null
        if (surfaceReady) startLoad(feed)
    }

    private fun updateBadge(feed: CameraFeed) {
        when {
            feed.demo -> {
                liveBadge.text = "● DEMO"
                liveBadge.background = pill(Color.parseColor("#B58900"))
            }
            feed.isConfigured -> {
                liveBadge.text = "● LIVE"
                liveBadge.background = pill(resources.getColor(R.color.live_red, null))
            }
            else -> {
                liveBadge.text = "○ NO SIGNAL"
                liveBadge.background = pill(Color.parseColor("#3A3A46"))
            }
        }
    }

    private fun pill(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(context, 20f).toFloat()
        setColor(color)
    }

    private fun applyMuteIcon() {
        muteButton.setImageResource(if (isMuted()) R.drawable.ic_volume_mute else R.drawable.ic_volume)
    }

    private fun showLoading(text: String, scrim: Boolean) {
        pausedPanel.visibility = GONE
        errorPanel.visibility = GONE
        unconfiguredPanel.visibility = GONE
        loadingText.text = text
        loadingPanel.setBackgroundColor(if (scrim) Color.parseColor("#66000000") else Color.parseColor("#CC0E0E12"))
        loadingPanel.visibility = VISIBLE
    }

    private fun showPaused() {
        loadingPanel.visibility = GONE
        errorPanel.visibility = GONE
        unconfiguredPanel.visibility = GONE
        pausedPanel.visibility = VISIBLE
    }

    private fun showError(message: String) {
        loadingPanel.visibility = GONE
        pausedPanel.visibility = GONE
        unconfiguredPanel.visibility = GONE
        errorMessage.text = message
        errorPanel.visibility = VISIBLE
        retryButton.requestFocus()
    }

    private fun showUnconfigured(title: String, body: String, showDemo: Boolean) {
        loadingPanel.visibility = GONE
        pausedPanel.visibility = GONE
        errorPanel.visibility = GONE
        unconfiguredTitle.text = title
        unconfiguredBody.text = body
        demoButton.visibility = if (showDemo) VISIBLE else GONE
        unconfiguredPanel.visibility = VISIBLE
        if (showDemo) demoButton.requestFocus()
    }

    private fun hideAllPanels() {
        loadingPanel.visibility = GONE
        pausedPanel.visibility = GONE
        errorPanel.visibility = GONE
        unconfiguredPanel.visibility = GONE
    }

    private fun showChrome(animated: Boolean) {
        main.removeCallbacks(chromeHide)
        chromeVisible = true
        if (animated) {
            chrome.visibility = VISIBLE
            chrome.animate().alpha(1f).translationY(0f).setDuration(180)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
        } else {
            chrome.animate().cancel()
            chrome.visibility = VISIBLE
            chrome.alpha = 1f
            chrome.translationY = 0f
        }
        applyMuteIcon()
    }

    private fun hideChrome(animated: Boolean) {
        main.removeCallbacks(chromeHide)
        chromeVisible = false
        val finish = {
            chrome.visibility = GONE
            if (muteButton.hasFocus()) requestFocus()
        }
        if (animated) {
            chrome.animate().alpha(0f).translationY(-dp(context, 14f).toFloat()).setDuration(180)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction(finish).start()
        } else {
            chrome.animate().cancel()
            finish()
        }
    }

    private fun scheduleChromeAutoHide() {
        main.removeCallbacks(chromeHide)
        if (!chromeVisible) return
        main.postDelayed(chromeHide, CHROME_HIDE_MS)
    }

    companion object {
        const val CHROME_HIDE_MS = 4_000L
    }
}
