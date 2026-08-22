package com.mircowuffwuff.sharpdroid

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

/**
 * the app's palette, chosen in the settings scene.
 *
 * **a user-defined scheme is one colour, and Material generates the rest.**
 * `DynamicColorsOptions.Builder().setContentBasedSource(seed)` is the same machinery Material You
 * uses here, pointed at a colour the user picked instead of at the wallpaper -- so the accent, the
 * surfaces, the outlines and every text colour come out of tonal palettes rather than out of four
 * values somebody has to keep in balance by hand. it applies *over* whatever theme is current and
 * **never reads `themes.xml`**, which is worth knowing before restructuring that file on its
 * account: a generated scheme is not built from anything in it, so nothing there can be a
 * prerequisite for one.
 *
 * **it needs dynamic colour, so it is offered exactly where Material You is** -- API 31 against a
 * `minSdk` of 28. below that the row is not in the list at all, which is the same guard and the same
 * reason. a hand-written generator for older devices stays possible and is not here.
 *
 * **a screen wears a theme and a run borrows one.** [MainActivity] names the framework's fullscreen
 * theme in the manifest -- its window is a surface a guest renders into, and a themed background
 * behind an opaque `SurfaceView` would be one more thing composited for nothing -- so what is drawn
 * over a running game takes the chosen scheme from [overlayContext] instead of from its window.
 *
 * **called before `setContentView` or it does nothing**, which is not a convention but how the
 * platform works: a theme is resolved while the view hierarchy is inflated, so a theme applied after
 * inflation leaves every already-inflated view on the old one.
 */
object Theme {

    /** what is stored, or the default. never null, so a comparison against it cannot be ambiguous. */
    @JvmStatic
    fun chosen(activity: Activity): String = Settings.of(activity).theme ?: Settings.THEME_DEFAULT

    /**
     * what a screen was drawn with, for [recreateIfStale] to compare against.
     *
     * **not the theme's name**, because a Custom theme can change without its name changing: picking
     * a new seed colour leaves the id at `custom` and every screen behind the picker would decide it
     * was already up to date. the seed is part of the identity of what was drawn, so it is part of
     * this string.
     */
    @JvmStatic
    fun signature(activity: Activity): String {
        val chosen = chosen(activity)
        if (chosen != Settings.THEME_CUSTOM) return chosen
        return chosen + ":" + (Settings.of(activity).customColour ?: Settings.CUSTOM_COLOUR_DEFAULT)
    }

    /**
     * restarts [activity] if the theme was changed on some screen it was not looking at.
     *
     * **every screen that can be underneath the settings scene needs this, not just the one below
     * it.** the scene where the theme is picked restarts itself; the section list behind it, and the
     * game list behind that, are already inflated with the old palette and cannot be repainted. with
     * only the game list checking, backing out of a theme change lands on a stale section list, and
     * it takes leaving and re-entering to get the new one.
     *
     * @return true if a restart was started, in which case the caller should do nothing else.
     */
    @JvmStatic
    fun recreateIfStale(activity: Activity, drawnWith: String): Boolean {
        if (signature(activity) == drawnWith) return false
        activity.recreate()
        return true
    }

    /**
     * applies the stored theme, or the default when nothing was chosen.
     *
     * Material You and Custom are the two that can be unavailable -- both are dynamic colour, which
     * is API 31 against a floor of 28 -- so each degrades to the default rather than to something
     * arbitrary, and neither row is offered where it cannot work. that is the same guard the
     * all-files row already has, for the same reason.
     */
    @JvmStatic
    fun apply(activity: Activity) {
        val chosen = chosen(activity)
        when (chosen) {
            Settings.THEME_MATERIAL_YOU ->
                if (dynamicColourAvailable()) {
                    DynamicColors.applyToActivityIfAvailable(activity)
                }
            // **the same generator, seeded by a colour instead of by the wallpaper.** everything a
            // scheme needs -- the accent, the surfaces, the outlines, every text colour -- is derived
            // from that one value by Material's own tonal palettes, which is why a custom theme is a
            // single Int in the store rather than the four colours a hand-written scheme wants.
            Settings.THEME_CUSTOM ->
                if (dynamicColourAvailable()) {
                    DynamicColors.applyToActivityIfAvailable(activity, seeded(activity))
                }
            else -> activity.setTheme(style(chosen))
        }
    }

    /**
     * a [Context] carrying the chosen scheme, for anything drawn over a running game.
     *
     * **the window it is drawn on is not themed and does not become themed.** a view built from this
     * -- or a layout inflated with `LayoutInflater.from(overlayContext(activity))` -- resolves
     * `?attr/colorSurfaceContainer` and every other role exactly as the app's own screens do, while
     * [MainActivity] keeps the framework fullscreen theme its `SurfaceView` wants. that is the whole
     * of the difference: a scheme reaches the overlay through the context it is built from rather
     * than through the window it sits over.
     *
     * **Material You and Custom come out of the same generator [apply] uses, over the same base**, so
     * a generated overlay is the settings scene's scheme rather than a second one that happens to
     * share a seed. where dynamic colour is unavailable each falls back the way its row does.
     */
    @JvmStatic
    fun overlayContext(activity: Activity): Context {
        val chosen = chosen(activity)
        val base = ContextThemeWrapper(activity, style(chosen))
        if (!dynamicColourAvailable()) return base
        return when (chosen) {
            Settings.THEME_MATERIAL_YOU -> DynamicColors.wrapContextIfAvailable(base)
            Settings.THEME_CUSTOM -> DynamicColors.wrapContextIfAvailable(base, seeded(activity))
            else -> base
        }
    }

    /**
     * the style a scheme is drawn with.
     *
     * **Material You and Custom name the application theme**, which is what they land on: both are
     * `DynamicColors` applied over whichever theme is already current, and on one of the app's own
     * screens that is the theme the manifest gave it. [overlayContext] has no such window to inherit
     * from, so it names the same one here.
     *
     * SharpEmu, and anything a future build wrote that this one does not know, take the app's own
     * look rather than the application theme, which is the day/night one the list does not offer.
     */
    private fun style(chosen: String): Int = when (chosen) {
        Settings.THEME_LIGHT -> R.style.Theme_SharpDroid_Light
        Settings.THEME_DARK -> R.style.Theme_SharpDroid_Dark
        Settings.THEME_MATERIAL_YOU, Settings.THEME_CUSTOM -> R.style.Theme_SharpDroid
        else -> R.style.Theme_SharpDroid_Desktop
    }

    /** the Custom scheme's generator input, in one place so a screen and an overlay cannot differ. */
    private fun seeded(activity: Activity): DynamicColorsOptions =
        DynamicColorsOptions.Builder()
            .setContentBasedSource(
                Settings.of(activity).customColour ?: Settings.CUSTOM_COLOUR_DEFAULT
            )
            .build()

    /** whether the Material You and Custom rows are worth offering on this device. */
    @JvmStatic
    fun dynamicColourAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DynamicColors.isDynamicColorAvailable()

    /**
     * restarts an activity so a theme change is visible immediately.
     *
     * `recreate()` rather than a manual relaunch: it keeps the task's back stack, and the settings
     * scene is deliberately a screen the user is standing on when they change this.
     */
    @JvmStatic
    fun reapply(activity: Activity) = activity.recreate()
}
