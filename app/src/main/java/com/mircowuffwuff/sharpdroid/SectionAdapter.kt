package com.mircowuffwuff.sharpdroid

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mircowuffwuff.sharpdroid.databinding.ItemSettingsSectionBinding

/**
 * the cards a settings scene is made of: an icon, a title, and one line saying what is behind it.
 *
 * **one adapter for the app's own scene and for a game's**, which is the same argument that keeps
 * [SettingsSectionActivity] serving both -- the two scenes differ in which sections they offer and in
 * what a tap carries, and not at all in what a card is. a second copy would be a second place to
 * notice the day a card grows anything.
 */
class SectionAdapter(
    private val sections: List<SettingsActivity.Section>,
    /**
     * which scene is drawing, which decides a card's second line and nothing else.
     *
     * **a flag rather than a summary handed in per card**, because what varies is one line of one
     * card: a caller assembling text would be a caller that has to be told the day a second section
     * needs a per-game wording.
     */
    private val perGame: Boolean,
    private val onClick: (SettingsActivity.Section) -> Unit,
) : RecyclerView.Adapter<SectionAdapter.Holder>() {

    class Holder(val binding: ItemSettingsSectionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemSettingsSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = sections.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val section = sections[position]
        holder.binding.icon.setImageResource(section.icon)
        holder.binding.title.setText(section.title)
        holder.binding.summary.setText(
            section.perGameSummary?.takeIf { perGame } ?: section.summary
        )
        holder.binding.root.setOnClickListener { onClick(section) }
    }
}
