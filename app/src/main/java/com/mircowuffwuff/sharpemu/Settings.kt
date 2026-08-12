package com.mircowuffwuff.sharpemu

import android.content.Context
import android.content.SharedPreferences

/**
 * What the user chose, and — just as importantly — what they did not.
 *
 * **"Unset" is a value here, distinct from "set to the default", and that distinction is the whole
 * design.** A launch merges three sources in this order, last winning:
 *
 * ```
 * the build's own env  <  these settings  <  the launch intent's extras
 * ```
 *
 * which is the order `docs/build-format.md` already documents for the environment. The trap it
 * exists to avoid is precise: if a row that has never been touched reported its default as a
 * *choice*, the app would start sending that value on every launch — and `am start` omitting the
 * matching extra would no longer reach the default, because a default it can no longer get to is
 * not a default. So a row that has never been touched contributes nothing to the argument vector,
 * and [MainActivity] sees an absence this file had no hand in.
 *
 * That is what keeps `scripts/run.py` uncompromised. It is also why every accessor here answers a
 * nullable, and why there is a [clear] rather than only a setter: "use the default" has to be
 * reachable from the UI, or a row is a one-way door.
 *
 * **A `SharedPreferences` line, like [GameLibrary]'s folder list.** Nothing here is big enough to
 * want a file of its own, and the platform's own store is what survives an app update without a
 * migration of ours.
 */
class Settings private constructor(private val prefs: SharedPreferences) {

    /** True once the user has touched this row, whatever they set it to. */
    fun isSet(key: String): Boolean = prefs.contains(key)

    /** Back to "the app decides", which is not the same as writing the default in. */
    fun clear(key: String) = prefs.edit().remove(key).apply()

    /**
     * Every row back to untouched, at once.
     *
     * **Through the store rather than by deleting its file**, and that is not a style preference:
     * `SharedPreferences` is cached per process, so a file removed underneath the framework is one
     * the framework rewrites from memory the moment anything else is set. `clear()` is the only
     * removal that both the disk and the copy in memory agree about.
     *
     * **It does not touch the all-files permission, and there is nothing here that could.** That row
     * stores no key — [AllFiles] reads the platform live on every look — so it is an exception to
     * this by construction rather than by an exclusion somebody has to remember.
     */
    fun clearAll() = prefs.edit().clear().apply()

    /**
     * How many rows are on something other than their default.
     *
     * **It compares values rather than counting what is set, and the two are not the same number.**
     * A row the user opened and put back on the value it already had is set — it reaches a launch,
     * an export carries it, and *Reset* clears it — but it is not a row that differs from the
     * default, and a card reporting it would tell somebody three things had changed when the app
     * behaves exactly as it shipped. What the User data screen states is the *state*, so this
     * answers about the state.
     *
     * **The driver has two spellings of one answer** — absent and [GpuDriver.SYSTEM] both mean the
     * driver the device shipped with — so it is asked rather than compared. Everything else has one
     * default and one way to say it.
     */
    fun changedFromDefault(): Int {
        var count = 0
        if (theme?.let { it != THEME_DEFAULT } == true) count++
        if (customColour?.let { it != CUSTOM_COLOUR_DEFAULT } == true) count++
        if (fullscreen == true) count++
        if (strictDynlib == true) count++
        if (fexPreset?.let { it != FexPreset.DEFAULT } == true) count++
        if (renderScale?.let { it != RENDER_SCALES[0] } == true) count++
        // **absence is the default and the bundled build is a choice, even though a launch resolves
        // the two to the same payload.** what separates them is that nothing writes this key by
        // itself: a fresh install stores nothing, and BuildsActivity.select returns early on the row
        // that is already selected - so the only way to hold [SharpEmuBuild.BUNDLED] here is to have
        // been on another build and come back, which is a choice like any other.
        if (build != null) count++
        if (!GpuDriver.isSystem(driver)) count++
        return count
    }

    // ------------------------------------------------------------------------------------------
    // App

    /**
     * Which palette the app's own screens wear. Never reaches the guest and never reaches the
     * argument vector — it is the one row here that is purely the frontend's.
     */
    var theme: String?
        get() = prefs.getString(KEY_THEME, null)
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /**
     * The seed colour a *Custom* theme is generated from.
     *
     * **One colour, and the scheme is derived from it** — Material's own generator produces the
     * accent, the surfaces, the outlines and every text colour that goes with them, which is why
     * this is a single `Int` rather than the four a scheme would otherwise need. See [Theme].
     */
    var customColour: Int?
        get() = if (prefs.contains(KEY_CUSTOM_COLOUR)) {
            prefs.getInt(KEY_CUSTOM_COLOUR, CUSTOM_COLOUR_DEFAULT)
        } else {
            null
        }
        set(value) = prefs.edit().putInt(KEY_CUSTOM_COLOUR, value!!).apply()

    /**
     * Whether the app's own screens hide the system bars.
     *
     * Off — the default — is the ordinary android screen: the layout pads itself by whatever the
     * status and navigation bars occupy. On is the same edge-to-edge window with the bars hidden and
     * a swipe bringing them back transiently.
     *
     * **This is the app's screens and never a guest's.** [MainActivity] hides its bars whatever this
     * says, because the surface is what decides the extent the guest presents at and a visible bar
     * would shrink it.
     */
    var fullscreen: Boolean?
        get() = if (prefs.contains(KEY_FULLSCREEN)) prefs.getBoolean(KEY_FULLSCREEN, false) else null
        set(value) = prefs.edit().putBoolean(KEY_FULLSCREEN, value!!).apply()

    // ------------------------------------------------------------------------------------------
    // Emulation

    /**
     * The build the user chose, as its on-device folder name.
     *
     * **A concrete identity and never a pointer.** The folder name is derived from `meta.json` —
     * `<id>-<sharpemuVersion>-<packagedAt>` — so it names one build rather than a family, and a
     * choice survives a newer build of the same id arriving. A stored id would quietly become a
     * different payload the day one did.
     *
     * **Null means the bundled build**, the one that ships inside this APK. That is a real answer
     * rather than an absence, which is why no row here has to describe a rule instead of naming a
     * build. A debug app bundles none, so there it falls back to the most recently staged build —
     * the behaviour the deploy loop has always had.
     */
    var build: String?
        get() = prefs.getString(KEY_BUILD, null)
        set(value) = prefs.edit().putString(KEY_BUILD, value).apply()

    /**
     * `--strict` on the payload's own command line, which fails a launch when an imported symbol
     * cannot be resolved rather than continuing without it.
     *
     * **This is a guest argument and not a host-layer flag.** The host layer takes
     * `<elf> [guest args...]` and has always passed the rest through; nothing below the payload
     * knows or cares what this means.
     */
    var strictDynlib: Boolean?
        get() = if (prefs.contains(KEY_STRICT)) prefs.getBoolean(KEY_STRICT, false) else null
        set(value) = prefs.edit().putBoolean(KEY_STRICT, value!!).apply()

    /**
     * The FEXCore JIT preset, as one of [FexPreset.ALL].
     *
     * **It is not in [guestEnvironment] and must not become so.** A preset reaches FEXCore as
     * `--fex` flags on the host layer's own command line, beside `--smc` — see [FexPreset] for why
     * the environment route does not exist here at all, and [MainActivity] for the rule that keeps
     * a build's `env` out of anything governing the host layer's correctness.
     *
     * Stored as the id rather than as an index, so a store survives a rung being inserted into the
     * ladder.
     */
    var fexPreset: String?
        get() = prefs.getString(KEY_FEX_PRESET, null)
        set(value) = prefs.edit().putString(KEY_FEX_PRESET, value).apply()

    // ------------------------------------------------------------------------------------------
    // Graphics

    /**
     * `SHARPEMU_RENDER_SCALE`, the guest environment variable `VulkanVideoPresenter` reads: render
     * offscreen targets below native resolution and upscale on present.
     *
     * Stored as the string the payload parses rather than as an index, so the store stays readable
     * and a value the desktop UI grows later does not need a table here to be expressible. The four
     * offered are the four the desktop UI offers.
     */
    var renderScale: String?
        get() = prefs.getString(KEY_RENDER_SCALE, null)
        set(value) = prefs.edit().putString(KEY_RENDER_SCALE, value).apply()

    /**
     * The GPU driver the user chose, as its on-device folder name.
     *
     * **[GpuDriver.SYSTEM] is a value here and so is absence**, and they mean the same thing: the
     * driver the device shipped with. Storing it is what lets the manager's radio mark a choice
     * rather than an empty store, and it costs nothing to a launch — [MainActivity] reads both as
     * "no custom driver" and passes no flags at all, which is the run every measurement in this
     * project was taken on.
     *
     * A folder name rather than a display name, because two packages can call themselves the same
     * thing and only one directory can be loaded.
     */
    var driver: String?
        get() = prefs.getString(KEY_DRIVER, null)
        set(value) = prefs.edit().putString(KEY_DRIVER, value).apply()

    /**
     * The guest environment these settings contribute, in the order it should be applied.
     *
     * **Only what was actually chosen.** An untouched row puts nothing in the map, so the guest's
     * environment is byte-for-byte one this file had no hand in.
     *
     * The launcher's own four — the host window, its size, the host audio selector and the .NET one
     * — are written by [MainActivity] *after* this map and are deliberately not expressible here.
     * They are the contract a payload is run under rather than a preference, which is the same rule
     * `docs/build-format.md` states for a build's `env`.
     *
     * **[fexPreset] is absent for a stronger reason than that**, and adding it here would be a
     * defect rather than a style choice: this map has a build's own `env` merged underneath it, so
     * anything expressible here is expressible by a payload — and a JIT preset is not a payload's to
     * choose. It travels as host-layer flags instead.
     */
    fun guestEnvironment(): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        renderScale?.let { env["SHARPEMU_RENDER_SCALE"] = it }
        return env
    }

    companion object {
        const val KEY_THEME = "theme"
        const val KEY_CUSTOM_COLOUR = "custom_colour"
        const val KEY_FULLSCREEN = "fullscreen"
        const val KEY_BUILD = "build"
        const val KEY_STRICT = "strict_dynlib"
        const val KEY_FEX_PRESET = "fex_preset"
        const val KEY_RENDER_SCALE = "render_scale"
        const val KEY_DRIVER = "driver"

        /** The four the desktop UI offers, as the payload parses them. */
        val RENDER_SCALES = arrayOf("1.0", "0.75", "0.5", "0.25")

        /** Theme ids, matching the order of `R.array.theme_entries`. */
        const val THEME_SHARPEMU = "sharpemu"
        const val THEME_LIGHT = "light"

        const val THEME_DARK = "dark"
        const val THEME_MATERIAL_YOU = "materialyou"
        const val THEME_CUSTOM = "custom"

        /**
         * **The order the dropdown shows, and the values the store holds.**
         *
         * `Theme.SharpEmu` — the application style, Material3 following the platform — is not among
         * them: an explicit Light and an explicit Dark cover it. It stays as a style because the
         * manifest names it and because it is what an activity wears for the moment before [Theme]
         * sets the chosen one.
         */
        val THEMES =
            arrayOf(THEME_SHARPEMU, THEME_LIGHT, THEME_DARK, THEME_MATERIAL_YOU, THEME_CUSTOM)

        /**
         * **Every custom seed is at this tone and the picker has no control for it.**
         *
         * Tone is HCT's perceptual lightness — CIE L\* — and pinning it is what locks a seed's
         * brightness. Locking HSV's *value* does not: measured on four seeds picked at value 0.75,
         * the tone runs from **37 for a violet to 71 for a green**.
         *
         * **It is not free.** The generator does read a seed's tone — `SchemeContent` is a content
         * scheme and its accent tracks it — but the
         * tracking is clamped: measured on this device, seed tones of 40 and 60 both produce an
         * accent at tone 80, and only above about 60 does the accent drift lighter and lose chroma.
         * So this is the top of the useful range rather than an arbitrary middle, and [HctColour]
         * slides *below* it per hue where that buys chroma.
         */
        const val CUSTOM_TONE = 60f

        /**
         * The seed a Custom theme starts at, before the user has picked one.
         *
         * **The maintainer's own** — so that selecting *Custom* and changing nothing lands somewhere
         * deliberate rather than on whatever a null would generate.
         *
         * It is a literal rather than a call into [HctColour] for two reasons: the picker produced
         * it, so it is already at that hue's tone and round-trips through the field unchanged, and a
         * constant that needs a gamut search to evaluate would drag the whole per-hue table into the
         * first screen that reads any setting at all.
         */
        const val CUSTOM_COLOUR_DEFAULT: Int = 0xFFA446D8.toInt()

        /**
         * **SharpEmu is the default**: a fresh install should look like this emulator rather than
         * like a stock android app, and the palette is the desktop build's own. It is also the fallback for an id this build does not know, so a store
         * written by a later version degrades to the app's own look rather than to a theme that is no
         * longer offered.
         */
        const val THEME_DEFAULT = THEME_SHARPEMU

        @JvmStatic
        fun of(context: Context): Settings =
            Settings(context.getSharedPreferences("settings", Context.MODE_PRIVATE))
    }
}
