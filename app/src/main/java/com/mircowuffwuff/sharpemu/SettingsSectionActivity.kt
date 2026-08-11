package com.mircowuffwuff.sharpemu

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mircowuffwuff.sharpemu.databinding.ActivitySettingsSectionBinding
import com.mircowuffwuff.sharpemu.databinding.DialogColourPickerBinding
import java.io.File

/**
 * One section's rows.
 *
 * One activity for every section rather than one per section, because the rows differ and nothing
 * else does — the toolbar, the list, the store and the "Use default" long press are the same screen
 * four times over.
 *
 * **A subsection is a label above a run of rows, not another button press.** Eden's *Advanced
 * settings → Graphics* is the shape.
 */
class SettingsSectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsSectionBinding
    private lateinit var settings: Settings
    private lateinit var adapter: SettingsAdapter
    private lateinit var section: SettingsActivity.Section
    private lateinit var drawnWith: String

    override fun onCreate(state: Bundle?) {
        Theme.apply(this)
        drawnWith = Theme.signature(this)
        super.onCreate(state)
        settings = Settings.of(this)
        section = runCatching {
            SettingsActivity.Section.valueOf(intent.getStringExtra(EXTRA_SECTION).orEmpty())
        }.getOrElse {
            // nothing but a hand-written intent reaches this, and finishing is a better answer than
            // an empty screen that looks like a section with no settings in it.
            finish()
            return
        }

        binding = ActivitySettingsSectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)
        binding.toolbar.setTitle(section.title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = SettingsAdapter(
            settings,
            rows(),
            onChanged = this::changed,
        )
        binding.settings.layoutManager = LinearLayoutManager(this)
        binding.settings.adapter = adapter
    }

    /**
     * Redraws after a write.
     *
     * **The theme is the one row that changes the screen it is on**, so it restarts the activity
     * rather than redrawing a list — a palette applied at inflation cannot be swapped underneath
     * views that have already been inflated with the old one.
     */
    private fun changed(row: SettingRow) {
        if (row.key == Settings.KEY_THEME || row.key == Settings.KEY_CUSTOM_COLOUR) {
            Theme.reapply(this)
            return
        }
        // the bars, if that is what moved. it takes effect on the screen the toggle is on and needs
        // no restart, because the behaviour is read inside the inset listener rather than baked in
        // when the screen was built.
        if (row.key == Settings.KEY_FULLSCREEN) {
            SystemBars.apply(this, binding.root)
        }
        adapter.submit(rows())
    }

    override fun onResume() {
        super.onResume()
        if (!::adapter.isInitialized) return
        if (Theme.recreateIfStale(this, drawnWith)) return
        // the bars may have been toggled on another section, and the all-files switch is changed on
        // the platform's own screen -- both come back through here.
        SystemBars.apply(this, binding.root)
        adapter.submit(rows())
    }

    // ----------------------------------------------------------------------------------------------
    // the rows

    private fun rows(): List<SettingRow> = when (section) {
        SettingsActivity.Section.APP -> appRows()
        SettingsActivity.Section.EMULATION -> emulationRows()
        SettingsActivity.Section.GRAPHICS -> graphicsRows()
        SettingsActivity.Section.DATA -> dataRows()
    }

    private fun appRows(): List<SettingRow> {
        val rows = mutableListOf<SettingRow>()
        // **Material You and Custom are dropped from the list where dynamic colour does not exist**,
        // rather than shown and refused. both are the same generator - one seeded by the wallpaper
        // and one by a colour - so both need API 31, and minSdk is 28. this is the version guard
        // that keeping the floor where it is implies.
        val entries = resources.getStringArray(R.array.theme_entries)
        val dynamic = setOf(Settings.THEME_MATERIAL_YOU, Settings.THEME_CUSTOM)
        val keep = Settings.THEMES.indices.filter {
            Settings.THEMES[it] !in dynamic || Theme.dynamicColourAvailable()
        }
        rows += SettingRow.Dropdown(
            key = Settings.KEY_THEME,
            title = R.string.setting_theme,
            summary = R.string.setting_theme_summary,
            entries = keep.map { entries[it] }.toTypedArray(),
            values = keep.map { Settings.THEMES[it] }.toTypedArray(),
            default = Settings.THEME_DEFAULT,
        )
        // **only while a Custom theme is chosen.** a seed colour with no scheme to generate would be
        // a control that changes nothing, and it sits directly under the row that put it there so
        // the two read as one choice rather than as two.
        if (Theme.chosen(this) == Settings.THEME_CUSTOM) {
            rows += SettingRow.Colour(
                key = Settings.KEY_CUSTOM_COLOUR,
                title = R.string.setting_custom_colour,
                summary = R.string.setting_custom_colour_summary,
                // **the accent rather than the seed, and taken from the theme rather than computed.**
                // this screen is already wearing the scheme the seed generated, so its colorPrimary
                // *is* the accent — no generator call, no chance of the row and the theme disagreeing.
                // it needs no live updating either: a colour change restarts the screen.
                colour = themeColour(com.google.android.material.R.attr.colorPrimary),
            ) { pickColour() }
        }
        rows += SettingRow.Switch(
            key = Settings.KEY_FULLSCREEN,
            title = R.string.setting_fullscreen,
            summary = R.string.setting_fullscreen_summary,
            default = false,
        )
        return rows
    }

    /** A colour out of the theme this screen was inflated with. */
    private fun themeColour(attr: Int): Int {
        val typed = android.util.TypedValue()
        theme.resolveAttribute(attr, typed, true)
        return typed.data
    }

    /**
     * The Emulation section.
     *
     * **The build row has no toggle above it.** Exactly one build ships per APK, so there is no
     * recommendation to follow and nothing for a switch to govern — the row simply names what a
     * launch will run, and tapping it opens the manager. A toggle there would need a constant, a
     * stored key and a badge behind it, and every one of those is a way for the screen to say one
     * thing while the launch does another.
     */
    private fun emulationRows(): List<SettingRow> = listOf(
        // **no strict dynlib resolution row.** the payload parses `--strict` and carries it as far as
        // the options the dispatcher is handed, and nothing reads it from there — so a switch here
        // would promise that a launch fails on an unresolved import when a launch does no such thing.
        // it is also a diagnostic rather than a setting: what it offers a user is a game that refuses
        // to start. the flag stays reachable, and `--ez strict true` on a launch still passes it,
        // because the merge reads the intent before it reads the store.
        SettingRow.Header(R.string.settings_group_sharpemu),
        SettingRow.Screen(
            key = Settings.KEY_BUILD,
            title = R.string.setting_build,
            summary = R.string.setting_build_summary,
            value = chosenBuildLabel(),
        ) {
            startActivity(Intent(this, BuildsActivity::class.java))
        },
        // no FEXCore version row beside this one. exactly one FEXCore is linked into the host layer
        // and there is nothing for a choice to select between, so a dropdown with one entry would
        // offer a capability the app does not have.
        SettingRow.Header(R.string.settings_group_fexcore),
        // **a slider rather than a list, because these are a ladder and not alternatives.** each rung
        // trades faithfulness for speed against the one before it, and a control with an order in it
        // says so without the row having to. it also makes the middle — which is FEXCore's own
        // defaults — somewhere a user can aim rather than a position they have to count out.
        SettingRow.Slider(
            key = Settings.KEY_FEX_PRESET,
            title = R.string.setting_fex_preset,
            summary = R.string.setting_fex_preset_summary,
            entries = resources.getStringArray(R.array.fex_preset_entries),
            detail = resources.getStringArray(R.array.fex_preset_details),
            values = FexPreset.ALL,
            default = FexPreset.DEFAULT,
            low = R.string.setting_fex_preset_low,
            high = R.string.setting_fex_preset_high,
        ),
    )

    /**
     * What the build row shows underneath itself.
     *
     * **It always names a concrete build**, which is what shipping exactly one buys: nothing stored
     * means the bundled build, so the row reads its name rather than describing a rule. A subtitle
     * along the lines of "none chosen, the most recent is used" would explain the launcher instead
     * of answering the question the row is there to answer.
     *
     * **The name alone, without the SharpEmu version beside it.** Every row on this screen answers
     * with one thing, and a version is a second: it is what tells two builds of one name apart, which
     * is a question the manager behind this row exists for and this row cannot answer anyway — it has
     * room for one line and there may be several.
     *
     * **A build that is not there says so rather than showing its name.** A folder can go —
     * deleted from a PC, or the external volume wiped — and a row that went on naming it would leave
     * the game failing to start as the only place a user could find out.
     *
     * It reads a `meta.json` on the main thread, which is one small file: the alternative is a row
     * that draws empty and fills in a frame later, on a screen that is otherwise synchronous.
     */
    private fun chosenBuildLabel(): String {
        val internalRoot = AppStorage.installedBuilds(filesDir)
        val staged = AppStorage.stagedBuilds(getExternalFilesDir(null)!!)
        // **the bundled build is named from the APK's own asset**, which is what lets this row be
        // right on a fresh install: nothing extracts it until a game is launched with it selected,
        // so the directory it will live in does not exist yet, and reading the disk would report the
        // one build that is certainly there as missing.
        val bundled = BundledBuild.identity(this, internalRoot)
        val folder = settings.build
            ?: bundled?.folder
            // a debug app bundles none, so it falls back to what a launch would run: the most
            // recently staged build. naming it beats describing the rule that found it.
            ?: return SharpEmuBuild.mostRecent(staged, internalRoot)?.name
                ?: getString(R.string.setting_build_none)
        val build = bundled?.takeIf { it.folder == folder }
            ?: SharpEmuBuild.read(File(internalRoot, folder))
            ?: SharpEmuBuild.read(File(staged, folder))
            ?: return getString(R.string.setting_build_missing, folder)
        return build.name
    }

    private fun graphicsRows(): List<SettingRow> = listOf(
        SettingRow.Dropdown(
            key = Settings.KEY_RENDER_SCALE,
            title = R.string.setting_internal_resolution,
            summary = R.string.setting_internal_resolution_summary,
            entries = resources.getStringArray(R.array.render_scale_entries),
            values = Settings.RENDER_SCALES,
            default = Settings.RENDER_SCALES[0],
        ),
        SettingRow.Header(R.string.settings_group_vulkan),
        SettingRow.Screen(
            key = Settings.KEY_DRIVER,
            title = R.string.setting_driver,
            summary = R.string.setting_driver_summary,
            value = chosenDriverLabel(),
        ) {
            startActivity(Intent(this, DriversActivity::class.java))
        },
    )

    /**
     * What the driver row shows underneath itself.
     *
     * **It always names something concrete**, because the system driver is always there — so the row
     * reads a name rather than describing what happens when nothing is chosen.
     *
     * **A package that is not there says so rather than showing its name.** A staged directory can go
     * — deleted from a PC, or the external volume wiped — and a row that went on naming it would
     * leave a launch quietly falling back to the system driver as the only place to find out.
     *
     * It reads a `meta.json`, which is one small file, on the main thread: the alternative is a row
     * that draws empty and fills in a frame later on a screen that is otherwise synchronous.
     */
    private fun chosenDriverLabel(): String {
        val folder = settings.driver
        if (GpuDriver.isSystem(folder)) return getString(R.string.driver_system)
        val internalRoot = AppStorage.installedDrivers(filesDir)
        val staged = AppStorage.stagedDrivers(getExternalFilesDir(null)!!)
        val driver = GpuDriver.resolve(folder!!, internalRoot, staged)
            ?: return getString(R.string.setting_driver_missing, folder)
        return driver.name
    }

    /**
     * The Data section: where games are read from, and how.
     *
     * **All-files access is here rather than in App**: App is the look and behaviour of the app, and
     * how it reaches a game's files is a data concern. The folder manager is the other half of that
     * same question — which folders the app may read — so the two share a subsection.
     *
     * **The folder row is unconditional and the all-files row is not, and that ordering is the point.**
     * `MANAGE_EXTERNAL_STORAGE` needs API 30 and `minSdk` is 28, so this section builds a list rather
     * than returning one: an early return on the permission would take the folder manager with it, and
     * on a device below 30 that is a device with no way to add a game at all.
     *
     * The all-files row is an [SettingRow.External] rather than a [SettingRow.Switch] because this app
     * cannot set it: it is granted in android's own settings and nowhere else, so the row shows the
     * state and a tap opens the screen that changes it.
     */
    private fun dataRows(): List<SettingRow> {
        // **a count rather than the folders themselves.** the build row names one build and the
        // driver row one package, because there is one of each; there is any number of folders, and
        // a row that listed them would be the screen behind it drawn badly on one line.
        //
        // it reads the store on the main thread, which is a `SharedPreferences` line cross-checked
        // against the grants android holds - the same read FoldersActivity does on a worker, where
        // it is followed by drawing a list rather than a number.
        val folders = GameLibrary.trees(this).size
        val rows = mutableListOf<SettingRow>(
            SettingRow.Header(R.string.settings_group_game_files),
            SettingRow.Screen(
                // nothing stored: this is a place to go rather than a value that was chosen.
                key = null,
                title = R.string.setting_folders,
                summary = R.string.setting_folders_summary,
                value = if (folders == 0) {
                    getString(R.string.setting_folders_empty)
                } else {
                    resources.getQuantityString(R.plurals.setting_folders_count, folders, folders)
                },
                chosen = folders > 0,
            ) {
                startActivity(Intent(this, FoldersActivity::class.java))
            },
        )
        if (AllFiles.supported()) {
            rows += SettingRow.External(
                title = R.string.setting_all_files,
                summary = R.string.setting_all_files_summary,
                checked = AllFiles.granted(),
            ) {
                if (!AllFiles.request(this)) {
                    Toast.makeText(this, R.string.all_files_unavailable, Toast.LENGTH_LONG).show()
                }
            }
        }
        return rows
    }

    /**
     * The colour picker behind the Custom theme's swatch.
     *
     * **One rectangle: hue across, chroma down, at a pinned tone.** Three sliders were the first
     * version, a shade square with a hue bar the second, hue and *saturation* the third. These are
     * the generator's own axes rather than a conversion into them — [HctColour] has why that matters
     * and what it cost to make the vertical one behave.
     *
     * **The preview beside it is the generated scheme, live** — [SchemePreview] asks the same
     * generator the theme is built with, so it is the theme drawn small rather than an impression of
     * it. The screens *around* the dialog cannot repaint as the knob moves: a scheme is resolved
     * while a hierarchy is inflated, so repainting them means recreating the activity, which would
     * close the dialog doing the picking. Saving does exactly that, which is the same path a theme
     * change already takes.
     */
    private fun pickColour() {
        val picker = DialogColourPickerBinding.inflate(LayoutInflater.from(this))
        val stored = settings.customColour ?: Settings.CUSTOM_COLOUR_DEFAULT
        val hue = HctColour.hueOf(stored)
        picker.field.hue = hue
        // **the stored colour's tone is read and discarded**, and its chroma is expressed as a
        // fraction of what its hue can reach. a seed saved by an earlier build opens where it belongs
        // and is normalised the moment it is saved again.
        picker.field.chromaFraction =
            (HctColour.chromaOf(stored) / HctColour.ceiling(hue).coerceAtLeast(1f)).coerceIn(0f, 1f)

        fun chosen(): Int = HctColour.colour(picker.field.hue, picker.field.chroma())

        /** Redraws the swatch, the hex and the whole preview from whatever is picked right now. */
        fun redraw() {
            val colour = chosen()
            // **the preview is the generated scheme rather than the seed painted about.** the roles
            // come from the same generator the theme is built with, so what is drawn here is what
            // arrives on Save. it is also the only readout the dialog needs: a swatch and a hex under
            // the field would say the same thing with less of the theme around it.
            val scheme = SchemePreview.of(colour)
            picker.previewBackground.background = GradientDrawable().apply {
                cornerRadius = 16f * resources.displayMetrics.density
                setColor(scheme.background)
            }
            picker.previewCard.background = GradientDrawable().apply {
                cornerRadius = 16f * resources.displayMetrics.density
                setColor(scheme.surfaceContainer)
                setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), scheme.outline)
            }
            picker.previewHeading.setTextColor(scheme.onSurfaceVariant)
            picker.previewTitle.setTextColor(scheme.onSurface)
            picker.previewSummary.setTextColor(scheme.onSurfaceVariant)
            picker.previewAccent.setTextColor(scheme.primary)
        }
        redraw()

        picker.field.onPicked = { _, _ -> redraw() }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setting_custom_colour)
            .setView(picker.root)
            .setPositiveButton(R.string.save) { _, _ ->
                settings.customColour = chosen()
                // the scheme is generated from this at inflation, so the screen has to be built
                // again to wear it. same path the theme row itself takes.
                Theme.reapply(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_SECTION = "section"
    }
}
