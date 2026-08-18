package com.mircowuffwuff.sharpemu

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.TooltipCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
// the colour roles are Material's own attributes, and this module's R does not carry them: a
// non-transitive R class holds what the module itself declares and nothing a library does.
import com.google.android.material.R as MaterialR
import com.mircowuffwuff.sharpemu.databinding.ItemUserDataCardBinding

/**
 * Draws the User data cards.
 *
 * **What each card can do is decided here rather than carried on the item**, because it is a property
 * of the kind and never of the measurement: the shader cache has no export on a device with 88 MB of
 * it for the same reason it has none on a device with nothing.
 *
 * @param onAction called with the card's kind and which button was pressed.
 */
class UserDataAdapter(
    private var items: List<UserDataItem>,
    private val onAction: (UserDataItem.Kind, Action) -> Unit,
) : RecyclerView.Adapter<UserDataAdapter.Holder>() {

    /**
     * What a button on a card does.
     *
     * **[label] is not drawn.** The button is a glyph, so the word is what a screen reader reads out
     * and what a long press shows as a tooltip — which is the only way to check an arrow whose
     * direction has to be decoded the first time.
     */
    enum class Action(val icon: Int, val label: Int) {
        EXPORT(R.drawable.ic_export, R.string.action_export),
        IMPORT(R.drawable.ic_import, R.string.action_import),
        DELETE(R.drawable.ic_delete, R.string.action_delete),
        RESET(R.drawable.ic_reset, R.string.action_reset),
    }

    fun submit(newItems: List<UserDataItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    /** What the Everything card measured, so the wipe confirmation can name a real figure. */
    fun everythingSize(): Long =
        items.firstOrNull { it.kind == UserDataItem.Kind.EVERYTHING }?.bytes ?: 0L

    override fun getItemCount() = items.size

    class Holder(val binding: ItemUserDataCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemUserDataCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val binding = holder.binding
        val context = binding.root.context

        binding.name.setText(item.kind.title)
        binding.description.setText(item.description)
        binding.figures.text = figures(holder, item)
        // **the accent marks the answer to the card, and an absence is not one.** it is the same
        // distinction a settings row draws between a chosen value and "None" -- see SettingsAdapter's
        // ScreenHolder, whose note about setting the colour on both branches applies here for the
        // same reason: a holder is recycled, and the colour of the card it was last bound to would
        // otherwise stay on it.
        binding.figures.setTextColor(
            MaterialColors.getColor(
                binding.figures,
                if (absent(item)) MaterialR.attr.colorOnSurfaceVariant else MaterialR.attr.colorPrimary,
            )
        )

        val actions = actionsFor(item.kind)
        listOf(binding.action1, binding.action2, binding.action3).forEachIndexed { index, button ->
            val action = actions.getOrNull(index)
            if (action == null) {
                button.visibility = View.GONE
                button.setOnClickListener(null)
                return@forEachIndexed
            }
            button.visibility = View.VISIBLE
            button.setImageResource(action.icon)
            // the name lives here because the glyph is the whole button: this is what a screen reader
            // reads out and what a long press shows. it names the card as well as the action, so an
            // Export announced out of context still says what it exports.
            button.contentDescription =
                context.getString(R.string.user_data_action, context.getString(action.label), context.getString(item.kind.title))
            TooltipCompat.setTooltipText(button, context.getString(action.label))
            button.setOnClickListener { onAction(item.kind, action) }
        }
    }

    /**
     * Whether this card has nothing to report — no bytes, or for the settings no row off its default.
     *
     * It is what decides the figures line's colour, and it is deliberately the same question the line
     * itself answers in words: the two cannot disagree.
     */
    private fun absent(item: UserDataItem): Boolean = when (item.kind) {
        UserDataItem.Kind.SETTINGS -> (item.count ?: 0) == 0
        else -> item.bytes == 0L
    }

    /**
     * The card's second line: a size, and what it is a size *of* where the kind can count something.
     *
     * **Empty says so in words.** "0 B" reads as a measurement that went wrong, where "Nothing saved
     * yet" reads as the state it is.
     */
    private fun figures(holder: Holder, item: UserDataItem): String {
        val context = holder.binding.root.context
        if (item.kind == UserDataItem.Kind.SETTINGS) {
            val changed = item.count ?: 0
            return if (changed == 0) {
                context.getString(R.string.user_data_settings_default)
            } else {
                context.resources.getQuantityString(R.plurals.user_data_settings_changed, changed, changed)
            }
        }
        if (item.bytes == 0L) {
            return context.getString(
                when (item.kind) {
                    UserDataItem.Kind.SAVE_DATA -> R.string.user_data_saves_empty
                    UserDataItem.Kind.SHADER_CACHE -> R.string.user_data_shaders_empty
                    else -> R.string.user_data_everything_empty
                }
            )
        }
        val size = Formatter.formatShortFileSize(context, item.bytes)
        // **titles on both cards, not games on one of them.** a save directory and a cache directory
        // are both keyed by title id, so the two counts are the same kind of thing counted twice —
        // and two words for it would read as a difference that is not there.
        val count = item.count ?: return size
        val counted = context.resources.getQuantityString(R.plurals.user_data_titles, count, count)
        return context.getString(R.string.user_data_figures, size, counted)
    }

    private fun actionsFor(kind: UserDataItem.Kind): List<Action> = when (kind) {
        UserDataItem.Kind.EVERYTHING -> listOf(Action.EXPORT, Action.IMPORT, Action.DELETE)
        UserDataItem.Kind.SAVE_DATA -> listOf(Action.EXPORT, Action.IMPORT, Action.DELETE)
        // **no export or import.** the cache is compiled against one driver on one device and is
        // rebuilt by playing, so carrying one to another install is work with nothing at the end of
        // it — the blob's own header is what the next driver validates and discards.
        UserDataItem.Kind.SHADER_CACHE -> listOf(Action.DELETE)
        // **reset only.** a settings export travels inside the whole-of-it export above, and moving
        // one on its own is a separate piece of work rather than a button waiting for it.
        UserDataItem.Kind.SETTINGS -> listOf(Action.RESET)
    }
}
