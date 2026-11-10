package ph.pbb.live.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ph.pbb.live.R
import ph.pbb.live.config.CameraFeed
import ph.pbb.live.util.dp
import ph.pbb.live.util.onTvClick

/**
 * A selectable camera row/card for the DINING and EXTERNAL sections.
 * 16:9 thumbnail-style left panel + label and status text + focus ring.
 */
class FeedCardView(context: Context, val feed: CameraFeed) : FrameLayout(context) {

    var onOpen: ((FeedCardView) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        onTvClick { onOpen?.invoke(this) }

        val card = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        // Thumbnail placeholder (camera never gets a fake preview; it shows status instead).
        val thumb = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 8f).toFloat()
                setColor(Color.parseColor("#15151B"))
                setStroke(dp(context, 1f), resources.getColor(R.color.border, null))
            }
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_camera)
            setColorFilter(if (feed.isConfigured) Color.parseColor("#8A8A96") else Color.parseColor("#4C4C58"))
        }
        thumb.addView(icon, LayoutParams(dp(context, 40f), dp(context, 40f), Gravity.CENTER))
        if (feed.isConfigured) {
            val badge = TextView(context).apply {
                text = if (feed.demo) "DEMO" else "LIVE"
                setTextColor(Color.WHITE)
                textSize = 9.5f
                letterSpacing = 0.12f
                setPadding(dp(context, 8f), dp(context, 2f), dp(context, 8f), dp(context, 2f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 12f).toFloat()
                    setColor(if (feed.demo) Color.parseColor("#B58900") else resources.getColor(R.color.live_red, null))
                }
            }
            thumb.addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(context, 8f), dp(context, 8f), 0)
            })
        }
        card.addView(thumb, LinearLayout.LayoutParams(dp(context, 168f), dp(context, 112f)).apply {
            setMargins(dp(context, 14f), dp(context, 14f), dp(context, 16f), dp(context, 14f))
        })

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = feed.displayName()
            setTextColor(resources.getColor(R.color.text_primary, null))
            textSize = 17f
            maxLines = 2
        }
        texts.addView(title)
        val sub = TextView(context).apply {
            text = when {
                feed.demo -> "Public test stream · ${feed.format.uppercase()}"
                feed.isConfigured -> "Authorized stream · ${feed.format.uppercase()}"
                feed.hasDrm -> "DRM-protected — requires the Media3 build"
                else -> "Not configured — see README “Configuring streams”"
            }
            setTextColor(resources.getColor(R.color.text_secondary, null))
            textSize = 12f
            setPadding(0, dp(context, 5f), 0, 0)
        }
        texts.addView(sub)
        card.addView(texts, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        val chevron = TextView(context).apply {
            text = "›"
            setTextColor(resources.getColor(R.color.text_secondary, null))
            textSize = 34f
            gravity = Gravity.CENTER
        }
        card.addView(chevron, LinearLayout.LayoutParams(dp(context, 44f), LayoutParams.MATCH_PARENT))

        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyState()
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gained, direction, previouslyFocusedRect)
        applyState()
    }

    private fun applyState() {
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 12f).toFloat()
            if (hasFocus()) {
                setColor(Color.parseColor("#23232C"))
                setStroke(dp(context, 3f), resources.getColor(R.color.focus_ring, null))
            } else {
                setColor(Color.parseColor("#1B1B22"))
                setStroke(dp(context, 1.5f), resources.getColor(R.color.border, null))
            }
        }
    }
}
