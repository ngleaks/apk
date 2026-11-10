package ph.pbb.live.util

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Lays its single child out centered inside itself at [aspectRatio] (width/height),
 * letterboxed, so video is never stretched. Default ratio is 16:9 until the first
 * videoSizeChanged callback arrives.
 */
class AspectFitFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var aspectRatio: Float = 16f / 9f
        set(value) {
            if (value.isFinite() && value in 0.2f..6f) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val (cw, ch) = fittedSize(w, h, aspectRatio)
        val child = getChildAt(0)
        if (child != null) {
            child.measure(
                MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = getChildAt(0) ?: return
        val w = r - l
        val h = b - t
        val (cw, ch) = fittedSize(w, h, aspectRatio)
        val cl = (w - cw) / 2
        val ct = (h - ch) / 2
        child.layout(cl, ct, cl + cw, ct + ch)
    }

    private fun fittedSize(w: Int, h: Int, ratio: Float): Pair<Int, Int> {
        var cw = w
        var ch = (w / ratio).toInt().coerceAtLeast(1)
        if (ch > h) {
            ch = h
            cw = (h * ratio).toInt().coerceAtLeast(1)
        }
        return cw to ch
    }
}
