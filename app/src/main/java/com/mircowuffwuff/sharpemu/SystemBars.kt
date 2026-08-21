package com.mircowuffwuff.sharpemu

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding

/**
 * what the app's own screens do about the status and navigation bars.
 *
 * **something has to, and that is `targetSdk` 35 rather than a style choice.** from android 15 the
 * platform draws every window edge to edge and ignores one that does not ask, so a layout with no
 * inset handling puts its toolbar behind the clock -- a title under the status bar, which in a
 * screenshot looks like a slightly tight margin rather than like a bug.
 *
 * **`recreate()` hides it, so verify a screen from a cold start.** a restarted activity receives its
 * insets at a point where the already-measured hierarchy takes them, so a screen reached by changing
 * the theme looks right until it is left and entered again.
 *
 * there are two behaviours and the *Fullscreen mode* row picks between them:
 *
 * | | |
 * | --- | --- |
 * | off, the default | the ordinary android screen -- the root is padded by whatever the bars occupy |
 * | on | the same edge-to-edge window with the bars hidden, and a swipe bringing them back for a moment |
 *
 * **[MainActivity] is deliberately not a caller of either.** its window is a surface a guest renders
 * into and it hides its bars whatever this setting says, because a visible bar would shrink the
 * surface and the surface is what decides the extent the guest presents at.
 */
object SystemBars {

    /**
     * applies whichever of the two behaviours is stored, and keeps the padding right afterwards.
     *
     * **the two halves are deliberately separate, and the first version of this had them tangled.**
     * asking for the bars to be hidden happens *here*, once per call; the listener below only ever
     * pads. that split is the fix for a real bug: with the hide call and a `fullscreen` branch both
     * living inside the listener, bringing the transient bars back with a swipe re-entered it -- the
     * system reported new insets, the listener asked for the bars to be hidden again, and the two
     * fought until the bars stayed up with the content padded as though the toggle were off. the
     * switch was on, the setting was on, and the screen looked like neither.
     *
     * **the listener needs no branch at all**, which is what makes the split possible: android
     * reports insets only for bars that are actually visible, so with them hidden the system-bar
     * inset is already zero and padding by "whatever is there" is correct in both modes. the cutout
     * is asked for in the same breath, because a notch does not go away when a bar hides.
     *
     * a listener rather than a one-time read, because the insets are not final when a view is first
     * attached and they change under a running screen -- a keyboard, a gesture-navigation switch, a
     * device that folds.
     */
    @JvmStatic
    fun apply(activity: Activity, root: View) {
        val controller = WindowCompat.getInsetsController(activity.window, root)
        // a swipe brings the bars back for a moment and the system hides them again on its own,
        // which is the behaviour a fullscreen screen wants: reachable without being a mode you can
        // get stuck out of. it means anything only once something is hidden.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Settings.of(activity).fullscreen == true) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(insets.left, insets.top, insets.right, insets.bottom)
            // consumed here rather than passed on: this is the only view that acts on them, and
            // handing them down would let a child pad itself by the same amount again.
            WindowInsetsCompat.CONSUMED
        }

        // and ask for them now, in case the view is already attached -- which it is whenever this is
        // called from onResume, or straight after the toggle was flipped.
        ViewCompat.requestApplyInsets(root)
    }
}
