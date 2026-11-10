package ph.pbb.live.ui.screens

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ph.pbb.live.R
import ph.pbb.live.config.CameraFeed
import ph.pbb.live.config.FeedSection
import ph.pbb.live.ui.FeedCardView
import ph.pbb.live.ui.TvScreen
import ph.pbb.live.util.capsLabel
import ph.pbb.live.util.dp
import ph.pbb.live.util.onTvClick

/**
 * DINING / EXTERNAL: selectable camera cards for the feeds assigned to a section.
 * When nothing is configured (the shipping default), shows an honest empty state that points
 * at streams.json plus a one-key demo path.
 */
class SectionListScreen(
    context: Context,
    private val section: FeedSection,
    feeds: List<CameraFeed>,
    onOpenFeed: (CameraFeed) -> Unit,
    onDemoRequested: (CameraFeed) -> Unit
) : FrameLayout(context), TvScreen {

    override val view: FrameLayout get() = this

    private val root = LinearLayout(context)
    private val cards = mutableListOf<FeedCardView>()
    private var emptyDemoButton: android.view.View? = null

    init {
        setBackgroundColor(resources.getColor(R.color.background, null))

        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(context, 28f), dp(context, 18f), dp(context, 28f), dp(context, 84f))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Header
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(context).apply {
            text = section.title
            capsLabel(15f, resources.getColor(R.color.text_primary, null), 0.2f)
        }
        header.addView(title)
        val count = feeds.size
        val pill = TextView(context).apply {
            text = "$count"
            setTextColor(resources.getColor(R.color.text_secondary, null))
            textSize = 12f
            setPadding(dp(context, 10f), dp(context, 2f), dp(context, 10f), dp(context, 2f))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 14f).toFloat()
                setColor(Color.parseColor("#23232C"))
            }
        }
        header.addView(pill, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(context, 12f)
        })
        root.addView(header)

        val divider = android.view.View(context).apply { setBackgroundColor(resources.getColor(R.color.border, null)) }
        root.addView(divider, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(context, 1f)).apply {
            topMargin = dp(context, 12f)
            bottomMargin = dp(context, 16f)
        })

        val sectionFeeds = feeds.filter { it.section == section }
        if (sectionFeeds.isEmpty()) {
            root.addView(buildEmptyState(context, onDemoRequested, feeds))
        } else {
            val scroll = ScrollView(context).apply { isFillViewport = false }
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            for (feed in sectionFeeds) {
                val card = FeedCardView(context, feed)
                card.onOpen = { onOpenFeed(feed) }
                card.layoutParams = LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(context, 140f)
                ).apply { bottomMargin = dp(context, 14f) }
                list.addView(card)
                cards += card
            }
            scroll.addView(list)
            root.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun buildEmptyState(
        context: Context,
        onDemoRequested: (CameraFeed) -> Unit,
        allFeeds: List<CameraFeed>
    ): android.view.View {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 24f), dp(context, 48f), dp(context, 24f), dp(context, 48f))
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_camera)
            setColorFilter(Color.parseColor("#4C4C58"))
        }
        panel.addView(icon, LinearLayout.LayoutParams(dp(context, 64f), dp(context, 64f)))

        val title = TextView(context).apply {
            text = "NO CAMERAS CONFIGURED"
            capsLabel(15f, resources.getColor(R.color.text_primary, null), 0.16f)
        }
        panel.addView(title, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 18f)
        })

        val body = TextView(context).apply {
            text = "PBB LIVE does not ship with ABS-CBN stream URLs.\n" +
                "Add your authorized HLS/DASH URL to assets/streams.json,\n" +
                "or drop streams.json in the app's external files directory,\n" +
                "or open  pbblivetv://play?url=<https-url>&title=<name>\n" +
                "Then relaunch. See README — “Configuring streams”."
            setTextColor(resources.getColor(R.color.text_secondary, null))
            textSize = 13f
            gravity = Gravity.CENTER
            setLineSpacing(dp(context, 2f).toFloat(), 1f)
        }
        panel.addView(body, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 10f)
        })

        allFeeds.firstOrNull { it.demo && it.isConfigured }?.let { demo ->
            val button = TextView(context).apply {
                text = "PLAY PUBLIC DEMO STREAM"
                setTextColor(Color.WHITE)
                textSize = 13f
                letterSpacing = 0.12f
                gravity = Gravity.CENTER
                isFocusable = true
                setPadding(dp(context, 18f), 0, dp(context, 18f), 0)
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 10f).toFloat()
                    setColor(Color.parseColor("#23232C"))
                    setStroke(dp(context, 1.5f), Color.parseColor("#3A3A46"))
                }
                onTvClick { onDemoRequested(demo) }
            }
            panel.addView(button, LinearLayout.LayoutParams(dp(context, 360f), dp(context, 52f)).apply {
                topMargin = dp(context, 22f)
            })
            emptyDemoButton = button
        }
        return panel
    }

    override fun onShown() {
        // No players on this screen; nothing to attach.
    }

    override fun onHidden() = Unit

    override fun onOk(): Boolean = false

    override fun focusContent() {
        val target = cards.firstOrNull { it.hasFocus() }
            ?: cards.firstOrNull()
            ?: emptyDemoButton
        target?.requestFocus() ?: requestFocus()
    }
}
