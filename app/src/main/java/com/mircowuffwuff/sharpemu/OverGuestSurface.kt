package com.mircowuffwuff.sharpemu

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/**
 * a container for something drawn over the guest's surface, shown and hidden through here.
 *
 * **the whole of it is the layout pass, and that is a measured claim rather than a design.** a view
 * over a `SurfaceView` that goes from `INVISIBLE` to `VISIBLE` does not reach the display until
 * something asks for a layout -- `View.setVisibility` requests one when a view leaves or enters
 * `GONE` and not when it merely stops being invisible, and until it does, the view is laid out at
 * the right size, draws frames, reports itself visible at full alpha, and is not on screen. every
 * reading is true and none of them is the answer, which is why this exists rather than two lines at
 * each call site.
 *
 * the alternative to `INVISIBLE` is `GONE`, which needs none of this because it requests the layout
 * itself -- and it costs the thing this buys: a `GONE` view is never laid out, so a panel that slides
 * in has no width to slide in from on its first showing.
 *
 * ### what this is not
 *
 * **it is not a fix for the compositor dropping the window's layer**, which is what it looked like
 * and is not what it is. a window whose whole area is a `SurfaceView` does report its whole area as
 * transparent, and a container that claims otherwise -- by emptying the region in
 * `gatherTransparentRegion` -- does make an overlay appear. it also appears without that, with the
 * layout request alone, on the same device in the same sitting. so the region override was two
 * changes made at once and the other one was doing the work; it is not here, and reaching for it
 * again would be treating a coincidence as a mechanism.
 *
 * **and it is not needed by every view over the surface.** the unpacking bar is a plain layout
 * toggled through `GONE`, and it draws over the guest correctly -- proven against a build carrying
 * this class, in the same sitting, with the bar unchanged.
 */
class OverGuestSurface(context: Context) : LinearLayout(context) {

    init {
        visibility = View.INVISIBLE
    }

    /** makes the contents visible **and asks for the layout pass that puts them on screen**. */
    fun show() {
        visibility = View.VISIBLE
        requestLayout()
    }

    /** takes them off screen again, staying laid out so the next [show] has its measurements. */
    fun hide() {
        visibility = View.INVISIBLE
        requestLayout()
    }
}
