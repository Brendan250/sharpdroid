package com.mircowuffwuff.sharpemu

import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import com.google.android.material.color.MaterialColors

/**
 * the three things every manager card does to itself, in one place.
 *
 * the SharpEmu build manager and the GPU driver manager draw the same card -- `item_manager_card.xml`
 * -- from two adapters, because what a card *says* differs and what it *is* does not. this holds the
 * second half: the badge, the marquee and the theme lookup they both need, so a change to any of the
 * three cannot land on one screen and miss the other.
 *
 * **nothing about a build or a driver is in here.** which badge, which words and which line is red
 * are decisions each adapter makes and each has to keep making; this only carries them out.
 */
object ManagerCard {

    /** a badge's word and its two colours. provenance is always shown; only the values change. */
    fun badge(view: TextView, text: Int, background: Int, foreground: Int) {
        view.setText(text)
        view.backgroundTintList = ColorStateList.valueOf(colour(view, background))
        view.setTextColor(colour(view, foreground))
    }

    /**
     * makes an over-long line scroll instead of ellipsizing -- **on the chosen card only**.
     *
     * a marquee animates while its view is selected, and android's own meaning for that is the one
     * used here: the card the radio is on. every card scrolling at once is legible and is a great
     * deal of movement on a screen of six, and the card whose full name is worth reading is the one
     * that is about to run.
     *
     * it is set per bind because a recycled view keeps whatever the previous card left on it --
     * without that, scrolling would stay behind on whichever card the selected one was recycled
     * from. it costs nothing on a line that fits: android animates a marquee only when the text is
     * wider than the view, so a short card is still even when it is the chosen one.
     */
    fun scrollLongLines(chosen: Boolean, vararg lines: TextView) {
        for (line in lines) line.isSelected = chosen
    }

    /** a theme attribute resolved against the view that will wear it. */
    fun colour(view: View, attr: Int): Int = MaterialColors.getColor(view, attr)
}
