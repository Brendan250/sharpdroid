package com.mircowuffwuff.sharpemu

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mircowuffwuff.sharpemu.databinding.ActivitySettingsBinding
import com.mircowuffwuff.sharpemu.databinding.ItemSettingsSectionBinding

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
        );

        companion object {
            /**
             * The sections that have rows, in the order the grid draws them.
             *
             * **Not called `entries`**, which is kotlin's own name for every constant of an enum
             * since 1.9 — a member shadowing it compiles with a deprecation warning and then means
             * something different from what it reads as.
             */
            val shown = listOf(APP, EMULATION, GRAPHICS, GAME_FILES, USER_DATA)
        }
    }

    private class SectionAdapter(
        private val sections: List<Section>,
        private val onClick: (Section) -> Unit,
    ) : RecyclerView.Adapter<SectionAdapter.Holder>() {

        class Holder(val binding: ItemSettingsSectionBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemSettingsSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun getItemCount() = sections.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val section = sections[position]
            holder.binding.icon.setImageResource(section.icon)
            holder.binding.title.setText(section.title)
            holder.binding.summary.setText(section.summary)
            holder.binding.root.setOnClickListener { onClick(section) }
        }
    }
}
