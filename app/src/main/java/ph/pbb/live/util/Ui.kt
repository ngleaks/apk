package ph.pbb.live.util

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.TextView

fun dp(context: Context, value: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

fun sp(context: Context, value: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics).toInt()

/**
 * Fires [action] for DPAD_CENTER / ENTER / NUMPAD_ENTER on the focused view — the TV "OK" key.
 * Attach to every clickable control; touch tapping uses the normal OnClickListener too.
 */
fun View.onTvClick(action: () -> Unit) {
    isClickable = true
    setOnClickListener { action() }
    setOnKeyListener { _, keyCode, event ->
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            action()
            true
        } else {
            false
        }
    }
}

/** Letter-spaced caps label (small gray section headers etc.). */
fun TextView.capsLabel(sizeSp: Float, color: Int, spacing: Float = 0.18f) {
    textSize = sizeSp
    setTextColor(color)
    letterSpacing = spacing
    isAllCaps = true
}
