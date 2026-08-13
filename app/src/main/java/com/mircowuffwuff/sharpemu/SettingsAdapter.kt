package com.mircowuffwuff.sharpemu

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
// the colour roles are Material's own attributes, and this module's R does not carry them: a
// non-transitive R class holds what the module itself declares and nothing a library does.
import com.google.android.material.R as MaterialR
import com.mircowuffwuff.sharpemu.databinding.DialogMessageBinding
import com.mircowuffwuff.sharpemu.databinding.DialogSliderChoiceBinding
import com.mircowuffwuff.sharpemu.databinding.ItemSettingColourBinding
import com.mircowuffwuff.sharpemu.databinding.ItemSettingHeaderBinding
import com.mircowuffwuff.sharpemu.databinding.ItemSettingSwitchBinding
import com.mircowuffwuff.sharpemu.databinding.ItemSettingValueBinding

/**
 * Draws a section's rows.
 *
 * **A long press on a row the user has set offers to put it back to the default**, and it is the one
 * piece of UI here that exists because of the precedence rule rather than because a screen wanted it
 * — a row with no way back is a one-way door. It is deliberately the only sign of that rule:
 * a mark distinguishing a set row from an untouched one has to hold a space on every row, which
 * indents the whole list to annotate one of them.
 *
 * @param onChanged called after a row writes, so the screen can redraw a row whose subtitle depends
 *   on it and so a theme change can take effect while the user is looking at it.
 */
class SettingsAdapter(
    private val settings: Settings,
    private var rows: List<SettingRow>,
    private val onChanged: (SettingRow) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    fun submit(newRows: List<SettingRow>) {
        rows = newRows
        // the whole list: a section is a screenful of rows, several of them recomputed together
        // when one changes, and there is no animation here worth the bookkeeping of a diff.
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is SettingRow.Header -> TYPE_HEADER
        is SettingRow.Switch -> TYPE_SWITCH
        is SettingRow.Dropdown -> TYPE_VALUE
        is SettingRow.Slider -> TYPE_SLIDER
        is SettingRow.Colour -> TYPE_COLOUR
        is SettingRow.External -> TYPE_EXTERNAL
        is SettingRow.Screen -> TYPE_SCREEN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemSettingHeaderBinding.inflate(inflater, parent, false))
            TYPE_SWITCH -> SwitchHolder(ItemSettingSwitchBinding.inflate(inflater, parent, false))
            TYPE_VALUE -> ValueHolder(ItemSettingValueBinding.inflate(inflater, parent, false))
            TYPE_COLOUR -> ColourHolder(ItemSettingColourBinding.inflate(inflater, parent, false))
            // the dropdown's layout again: the row is a title, an explanation and the chosen rung.
            // that the dialog behind it holds a slider is not something the row shows.
            TYPE_SLIDER -> SliderHolder(ItemSettingValueBinding.inflate(inflater, parent, false))
            // the same layout a dropdown uses: a title, an explanation and the chosen thing under
            // it. what differs is where the tap goes, which is not something a layout knows.
            TYPE_SCREEN -> ScreenHolder(ItemSettingValueBinding.inflate(inflater, parent, false))
            else -> ExternalHolder(ItemSettingSwitchBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is SettingRow.Header -> (holder as HeaderHolder).bind(row)
            is SettingRow.Switch -> (holder as SwitchHolder).bind(row)
            is SettingRow.Dropdown -> (holder as ValueHolder).bind(row)
            is SettingRow.Slider -> (holder as SliderHolder).bind(row)
            is SettingRow.Colour -> (holder as ColourHolder).bind(row)
            is SettingRow.External -> (holder as ExternalHolder).bind(row)
            is SettingRow.Screen -> (holder as ScreenHolder).bind(row)
        }
    }

    /**
     * A row that opens another screen, showing what is currently chosen underneath it.
     *
     * The long press works as on any stored row — a build that was chosen is a value like any other,
     * and *Use default* puts it back to the app deciding, which for the build row means the one that
     * shipped inside this APK.
     */
    private inner class ScreenHolder(val binding: ItemSettingValueBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: SettingRow.Screen) {
            binding.title.setText(row.title)
            binding.summary.setText(row.summary)
            binding.value.text = row.value
            // set on both branches rather than only the plain one, because a holder is recycled and
            // the colour of the row it was last bound to would otherwise stay on it.
            binding.value.setTextColor(
                MaterialColors.getColor(
                    binding.value,
                    if (row.chosen) MaterialR.attr.colorPrimary else MaterialR.attr.colorOnSurfaceVariant,
                )
            )

            binding.root.setOnClickListener { row.onClick() }
            // a screen that stores nothing has no default to go back to, so it has no long press.
            binding.root.setOnLongClickListener { row.key?.let { offerDefault(it, row) } ?: false }
        }
    }

    private class HeaderHolder(val binding: ItemSettingHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: SettingRow.Header) {
            binding.title.setText(row.title)
        }
    }

    private inner class SwitchHolder(val binding: ItemSettingSwitchBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: SettingRow.Switch) {
            binding.title.setText(row.title)
            binding.summary.setText(row.summary)

            // set without the listener attached, or restoring the stored state would read as the
            // user flipping it — which would write the key and make every untouched row "set" the
            // first time its section is opened.
            binding.toggle.setOnCheckedChangeListener(null)
            binding.toggle.isChecked = stored(row.key, row.default)
            binding.toggle.setOnCheckedChangeListener { _, checked ->
                write(row.key, checked)
                onChanged(row)
            }
            binding.root.setOnClickListener { binding.toggle.toggle() }
            binding.root.setOnLongClickListener { offerDefault(row.key, row) }
        }

        private fun stored(key: String, default: Boolean): Boolean = when (key) {
            Settings.KEY_FULLSCREEN -> settings.fullscreen ?: default
            Settings.KEY_STRICT -> settings.strictDynlib ?: default
            Settings.KEY_AUTOMATIC_CONTROLLER_MAPPING -> settings.automaticControllerMapping ?: default
            Settings.KEY_VIBRATE_HANDHELD -> settings.vibrateHandheld ?: default
            else -> default
        }

        private fun write(key: String, value: Boolean) = when (key) {
            Settings.KEY_FULLSCREEN -> settings.fullscreen = value
            Settings.KEY_STRICT -> settings.strictDynlib = value
            Settings.KEY_AUTOMATIC_CONTROLLER_MAPPING -> settings.automaticControllerMapping = value
            Settings.KEY_VIBRATE_HANDHELD -> settings.vibrateHandheld = value
            else -> Unit
        }
    }

    private inner class ValueHolder(val binding: ItemSettingValueBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: SettingRow.Dropdown) {
            binding.title.setText(row.title)

            val current = stored(row.key) ?: row.default
            val index = row.values.indexOf(current).coerceAtLeast(0)
            // the chosen entry, not the explanation. the explanation is what a row says when it has
            // nothing else to say, and a value the user picked is the thing they came back to check.
            binding.value.text = row.entries[index]
            binding.summary.setText(row.summary)

            binding.root.setOnClickListener {
                // **no Cancel button, which is Eden's shape for a single-choice list and is what
                // stops this one scrolling.** a tap on an entry both chooses and dismisses, so the
                // button was only ever a second way to do what the back gesture and a tap outside
                // already do — and its row of padding was the difference between five themes fitting
                // and not. the dialogs that *commit* something, the colour picker and the two
                // confirmations, keep theirs.
                MaterialAlertDialogBuilder(binding.root.context)
                    .setTitle(row.title)
                    .setSingleChoiceItems(row.entries, index) { dialog, which ->
                        write(row.key, row.values[which])
                        dialog.dismiss()
                        onChanged(row)
                    }
                    .show()
            }
            binding.root.setOnLongClickListener { offerDefault(row.key, row) }
        }

        private fun stored(key: String): String? = when (key) {
            Settings.KEY_THEME -> settings.theme
            Settings.KEY_RENDER_SCALE -> settings.renderScale
            else -> null
        }

        private fun write(key: String, value: String) = when (key) {
            Settings.KEY_THEME -> settings.theme = value
            Settings.KEY_RENDER_SCALE -> settings.renderScale = value
            else -> Unit
        }
    }

    /**
     * A row whose dialog is a slider along a ladder.
     *
     * **The dialog commits on a button rather than on a detent**, unlike [ValueHolder]'s
     * single-choice list — which chooses and dismisses on one tap because a tap there *is* the
     * choice. A slider is dragged across every position between where it started and where it is
     * going, so writing as it moves would store four rungs nobody asked for on the way to the fifth,
     * and each of them would be a value the precedence rule then treats as chosen. Cancel has to be
     * a real answer here for the same reason.
     */
    private inner class SliderHolder(val binding: ItemSettingValueBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: SettingRow.Slider) {
            binding.title.setText(row.title)
            binding.summary.setText(row.summary)

            val current = stored(row.key) ?: row.default
            // an unknown stored value opens at the default rather than at position zero. a store
            // written by a later build naming a rung this one does not have would otherwise land the
            // user on the most conservative setting and call it their choice.
            val index = row.values.indexOf(current).let { if (it < 0) row.values.indexOf(row.default) else it }
                .coerceIn(0, row.entries.size - 1)
            binding.value.text = row.entries[index]

            binding.root.setOnClickListener {
                val view = DialogSliderChoiceBinding.inflate(LayoutInflater.from(binding.root.context))
                // **the axis rather than the two end rungs.** naming the ends with their own entries
                // put the same word on screen twice whenever the slider was at one of them — large
                // and accented above, small and grey below — while what the scale is missing is not
                // the names, which the line above always gives, but which way the ladder runs and
                // what it costs to go that way.
                view.lowLabel.setText(row.low)
                view.highLabel.setText(row.high)

                // **the track is widened to the text column rather than the scale being pulled in to
                // meet it.** a slider reserves room at both ends for the thumb to sit at an extreme,
                // so out of the box its track starts inset from the widget's own edge — which leaves
                // it narrower than every other line in this dialog and leaves the two ends of the
                // scale with nothing to line up against.
                //
                // **most of it is taken back and three eighths is left**, because the reservation is
                // not only for the thumb: the halo drawn around it as it is dragged is wider than the
                // bar, and cancelling the whole inset clipped that halo flat against the dialog at
                // both extremes. what is left is the margin that reaches the text column closely
                // enough to read as one edge while the halo is still round at either end.
                //
                // **a fraction of what the widget reports rather than a measured dp**, so it stays
                // the same share of the thumb's own room at every density — a literal number tuned
                // against one panel is a different amount of space on every other one.
                //
                // **the scale is not moved with it.** it sits on the dialog's own text column, which
                // is where the widened track now very nearly begins — near enough that pulling the
                // labels the last few dp to meet it reads as further in rather than as aligned.
                //
                // the inset itself is not in Material's public resource set and is free to move in a
                // version bump, which is the other reason it is asked for rather than repeated.
                //
                // **the right end is held further in than the left, and that is not a symmetry
                // mistake.** the dialog's own buttons sit below it, and a text button's label is
                // inset inside its bounds — so a track running to the content column ends level with
                // nothing, while one held off by slider_scale_end ends level with OK. the high label
                // is moved by the same amount in the layout, so the two stay flush with each other.
                val rest = view.slider.trackSidePadding * 3 / 8
                val pull = view.slider.trackSidePadding - rest
                (view.slider.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    marginStart = -pull
                    marginEnd = -pull +
                        view.slider.resources.getDimensionPixelSize(R.dimen.slider_scale_end)
                }
                view.slider.valueFrom = 0f
                view.slider.valueTo = (row.entries.size - 1).toFloat()
                view.slider.stepSize = 1f

                fun show(at: Int) {
                    view.name.text = row.entries[at]
                    view.detail.text = row.detail[at]
                }
                view.slider.value = index.toFloat()
                show(index)
                view.slider.addOnChangeListener { _, value, _ -> show(value.toInt()) }

                MaterialAlertDialogBuilder(binding.root.context)
                    .setTitle(row.title)
                    .setView(view.root)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        write(row.key, row.values[view.slider.value.toInt()])
                        onChanged(row)
                    }
                    .show()
            }
            binding.root.setOnLongClickListener { offerDefault(row.key, row) }
        }

        private fun stored(key: String): String? = when (key) {
            Settings.KEY_FEX_PRESET -> settings.fexPreset
            else -> null
        }

        private fun write(key: String, value: String) = when (key) {
            Settings.KEY_FEX_PRESET -> settings.fexPreset = value
            else -> Unit
        }
    }

    /**
     * A switch the app cannot flip, over state the platform owns.
     *
     * The switch is not interactive — the layout already makes it so, since the whole row is what
     * takes a tap — and nothing here writes anything. It is redrawn from [SettingRow.External.checked]
     * each time the section is rebuilt, which is what returning from android's own settings screen
     * causes.
     */
    private class ExternalHolder(val binding: ItemSettingSwitchBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: SettingRow.External) {
            binding.title.setText(row.title)
            binding.summary.setText(row.summary)
            binding.toggle.setOnCheckedChangeListener(null)
            binding.toggle.isChecked = row.checked
            binding.root.setOnClickListener { row.onClick() }
            // nothing stored means nothing to go back to, so the long press is not offered.
            binding.root.setOnLongClickListener(null)
            binding.root.isLongClickable = false
        }
    }

    private inner class ColourHolder(val binding: ItemSettingColourBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: SettingRow.Colour) {
            binding.title.setText(row.title)
            binding.summary.setText(row.summary)
            binding.swatch.background = swatch(row.colour)
            binding.root.setOnClickListener { row.onClick() }
            binding.root.setOnLongClickListener { offerDefault(row.key, row) }
        }
    }

    /**
     * The way back out of a choice.
     *
     * **Offered only for a row that is actually set**, so a long press on an untouched row does
     * nothing rather than showing a dialog whose button would be a no-op — which would tell the user
     * the row was set when it is not, and this gesture is the only place that distinction is visible.
     */
    private fun offerDefault(key: String, row: SettingRow): Boolean {
        if (!settings.isSet(key)) return false
        // any bound holder's context is the activity; the list is what we have a handle on.
        val context = recycler?.context ?: return false
        // **the message is a view of ours rather than setMessage**, and only so that the space below
        // it is the button panel's alone. see dialog_message.xml: a one-line question left a band of
        // empty dialog under it taller than the question.
        val message = DialogMessageBinding.inflate(LayoutInflater.from(context))
        message.message.setText(R.string.setting_use_default_message)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.setting_use_default_title)
            .setView(message.root)
            .setPositiveButton(R.string.setting_use_default) { _, _ ->
                settings.clear(key)
                onChanged(row)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return true
    }

    private var recycler: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        recycler = null
    }

    private companion object {
        /**
         * The circle a colour row draws, built rather than declared — a drawable resource is
         * compiled and the colour here is a value, so it has to be a `GradientDrawable`.
         *
         * **No ring.** It had one, on the reasoning that a swatch close to the row's own colour would
         * disappear; the switch in the row below has no ring either, and matching it matters more.
         */
        fun swatch(colour: Int): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colour)
        }

        const val TYPE_HEADER = 0
        const val TYPE_SWITCH = 1
        const val TYPE_VALUE = 2
        const val TYPE_EXTERNAL = 3
        const val TYPE_COLOUR = 4
        const val TYPE_SCREEN = 5
        const val TYPE_SLIDER = 6
    }
}
