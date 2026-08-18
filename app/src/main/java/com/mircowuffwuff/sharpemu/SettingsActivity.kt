package com.mircowuffwuff.sharpemu

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.mircowuffwuff.sharpemu.databinding.ActivitySettingsBinding

/**
 * The global settings scene: large section buttons, each with a line saying what is behind it.
 *
 * The shape is Eden's settings menu — buttons with a brief explanation, and a scrolling list of rows
 * behind each one.
 *
 * **Only the sections that have something in them are here.** Controls and Logging are sections this
 * app will grow, and every row in both is a later piece of work; a button that opens an empty screen
 * is worse than a button that is not there yet, because the empty screen looks like a bug in the one
 * that is. A section arrives with its rows.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** The theme this screen was drawn with, so a change made behind it is noticed on the way back. */
    private lateinit var drawnWith: String

    override fun onCreate(state: Bundle?) {
        // before setContentView, or the theme is resolved after the views are already inflated.
        Theme.apply(this)
        drawnWith = Theme.signature(this)
        super.onCreate(state)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        // **one column upright, two on a wide screen** — Eden's shape, and the reason is that a
        // section button is a title and one line, so a single column in landscape wastes two thirds
        // of the width. the count is a resource with a `-land` qualifier rather than a measurement:
        // android already resolves it, and an activity is recreated across a rotation, so it is
        // re-read without anything here watching for one.
        binding.sections.layoutManager =
            GridLayoutManager(this, resources.getInteger(R.integer.settings_section_columns))
        binding.sections.adapter = SectionAdapter(Section.shown) { section ->
            // **a section is usually a list of rows and does not have to be.** User data is a manager
            // screen, the shape the build, driver and folder managers already use, so its card opens
            // that directly rather than a list holding one row that opens it.
            val screen = section.screen
            startActivity(
                if (screen == null) {
                    Intent(this, SettingsSectionActivity::class.java)
                        .putExtra(SettingsSectionActivity.EXTRA_SECTION, section.name)
                } else {
                    Intent(this, screen)
                }
            )
        }
    }

    /**
     * **The theme is changed on the screen this one opens**, so coming back has to notice. Without
     * it, backing out of a theme change lands here on the old palette, and only leaving the settings
     * scene entirely and re-entering produces the new one.
     */
    override fun onResume() {
        super.onResume()
        if (Theme.recreateIfStale(this, drawnWith)) return
        // the fullscreen toggle is one screen further in, and this screen is what it comes back to.
        SystemBars.apply(this, binding.root)
    }

    /**
     * A section, its button's two lines, and what it is called on the way to the next screen.
     *
     * The label and the summary are string resources, so what appears on screen is the app's own
     * cased text rather than the enum's name.
     */
    enum class Section(
        val title: Int,
        val summary: Int,
        val icon: Int,
        /** The screen this card opens, or null for the ordinary list of rows. */
        val screen: Class<out AppCompatActivity>? = null,
    ) {
        APP(R.string.settings_app, R.string.settings_app_summary, R.drawable.ic_section_app),
        EMULATION(
            R.string.settings_emulation,
            R.string.settings_emulation_summary,
            R.drawable.ic_section_emulation,
        ),
        GRAPHICS(
            R.string.settings_graphics,
            R.string.settings_graphics_summary,
            R.drawable.ic_section_graphics,
        ),
        CONTROLS(
            R.string.settings_controls,
            R.string.settings_controls_summary,
            R.drawable.ic_section_controls,
        ),
        GAME_FILES(
            R.string.settings_game_files,
            R.string.settings_game_files_summary,
            R.drawable.ic_section_game_files,
        ),
        USER_DATA(
            R.string.settings_user_data,
            R.string.settings_user_data_summary,
            R.drawable.ic_section_user_data,
            UserDataActivity::class.java,
        ),

        /**
         * **The one section that is not a setting**, and it is here rather than somewhere of its own
         * because this is where somebody looks for a version number and for what the app is under.
         * Nothing behind it changes what a launch does.
         *
         * It is last for the same reason: the grid is read top to bottom by somebody who came to
         * change something, and this is the card for a different errand entirely.
         */
        ABOUT(
            R.string.settings_about,
            R.string.settings_about_summary,
            R.drawable.ic_section_about,
            AboutActivity::class.java,
        );

        companion object {
            /**
             * The sections that have rows, in the order the grid draws them.
             *
             * **Not called `entries`**, which is kotlin's own name for every constant of an enum
             * since 1.9 — a member shadowing it compiles with a deprecation warning and then means
             * something different from what it reads as.
             */
            val shown =
                listOf(APP, EMULATION, GRAPHICS, CONTROLS, GAME_FILES, USER_DATA, ABOUT)

            /**
             * The sections a single game answers for, in the order [GameSettingsActivity] draws them.
             *
             * **App and Game files are absent because they belong to the install rather than to a
             * title** — a theme and a folder grant are set once and apply to everything — and About
             * because nothing behind it is a setting at all. What is left is the three that describe
             * how one game is run.
             *
             * **User data is not here yet and arrives with a screen of its own.** A game's save data
             * and its shader cache are a different screen from the app-wide one in both meaning and
             * function, so the card waits for it: a button opening an empty screen is worse than one
             * that is not there, because the empty screen looks like a fault in the button.
             */
            val perGame = listOf(EMULATION, GRAPHICS, CONTROLS)
        }
    }
}
