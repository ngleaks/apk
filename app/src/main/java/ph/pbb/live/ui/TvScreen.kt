package ph.pbb.live.ui

import android.view.View

/** Contract every top-level screen implements. MainActivity owns only this interface. */
interface TvScreen {
    val view: View

    /** Called when the screen becomes visible. (Re)attach players. */
    fun onShown()

    /** Called when the screen is hidden or the overlay opens. Release players. */
    fun onHidden()

    /** Consume Back? (Overlays return true; section screens let the activity exit/show nav.) */
    fun handlesBack(): Boolean = false

    /** OK pressed while nothing inside the screen consumed it (e.g. toggle player chrome). */
    fun onOk(): Boolean = false

    /** Move remote focus to the screen's primary control. */
    fun focusContent()
}
