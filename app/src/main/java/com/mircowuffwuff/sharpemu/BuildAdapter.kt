package com.mircowuffwuff.sharpemu

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mircowuffwuff.sharpemu.databinding.ItemManagerCardBinding

/**
 * Draws the build list: the bundled build pinned at the top, then every build on the device.
 *
 * **The manager card, which the GPU driver manager draws too** — a radio down the left, the name, the
 * version under it, a line of description under that, a badge above and a trash can on the right. The
 * layout and the three things every card does to itself are `item_manager_card.xml` and [ManagerCard];
 * what is here is what a *build* has to say on those four lines. No *Fetch*: there is no index to
 * fetch from, and a build arrives as a zip.
 *
 * **Every card is four lines, and every line has something true on it.** Two cards sharing a grid row
 * are each as tall as their own content, so a card missing a line does not read as shorter — it reads
 * as empty, next to a full one. [bind] is where each of the four is guaranteed.
 *
 * **The bundled build is drawn through the same binding as any other**, unlike the driver manager's
 * system card. It *is* a build — it has a `meta.json`, a version and a commit — so a row type of its
 * own would be a second place for one identity to be drawn, and the two would drift. It is pinned
 * only in the sense that the activity puts it first and no sort touches it.
 *
 * **The badges are handed in, not computed.** [BuildLibrary] works them out from the same functions
 * the launcher resolves with, so a badge cannot disagree with what a launch does.
 */
class BuildAdapter(
    private var items: List<Item>,
    private val onSelect: (BuildLibrary.Entry) -> Unit,
    private val onDelete: (BuildLibrary.Entry) -> Unit,
) : RecyclerView.Adapter<BuildAdapter.Holder>() {

    /** One card. [bundled] is the pinned one, which shipped inside the app. */
    data class Item(val entry: BuildLibrary.Entry, val bundled: Boolean = false)

    fun submit(newItems: List<Item>) {
        items = newItems
        // the whole list. selecting one build unselects another and can move a badge, so several
        // cards change together and there is no animation here worth the bookkeeping of a diff.
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemManagerCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.bind(item.entry, item.bundled)
    }

    inner class Holder(val binding: ItemManagerCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: BuildLibrary.Entry, bundled: Boolean) {
            val build = entry.build
            val context = binding.root.context

            // **where it came from, which is the one claim about a build that cannot be faked and
            // the one with a different answer per card.** there are three provenances: it shipped
            // inside the app, adb staged it from a PC, or it was imported from a zip. the staged one
            // is dev-facing and is the quiet badge — nothing is ever copied, so it says which volume
            // the build runs from and nothing else.
            when {
                bundled -> badge(R.string.build_badge_bundled,
                    com.google.android.material.R.attr.colorPrimary,
                    com.google.android.material.R.attr.colorOnPrimary)

                entry.internal -> badge(R.string.build_badge_imported,
                    com.google.android.material.R.attr.colorSecondaryContainer,
                    com.google.android.material.R.attr.colorOnSecondaryContainer)

                else -> badge(R.string.build_badge_staged,
                    com.google.android.material.R.attr.colorSurfaceContainerHighest,
                    com.google.android.material.R.attr.colorOnSurfaceVariant)
            }
            // **the second badge says the one thing neither provenance nor the order can**: this
            // build is a SharpEmu version behind the one that shipped inside the app, and the fix is
            // one tap on the pinned card. it is absent on a healthy device, which is what makes it
            // worth reading — where "the newest one you happen to have" is the top card and needs no
            // badge to say so.
            //
            // **neutral rather than the error colour.** running an older build deliberately is half
            // of how anything here gets measured. red is spoken for by the fourth line, which says a
            // build cannot run at all.
            binding.badgeExtra.setText(R.string.build_badge_outdated)
            binding.badgeExtra.visibility = if (entry.outdated) View.VISIBLE else View.GONE

            // **every card draws the name its `meta.json` carries, the pinned one included.** the
            // build that ships is named at the moment it is bundled, so the name is a fact about the
            // build rather than a case in the list — and every other place one is named, the row in
            // Settings among them, says the same thing without knowing about this screen.
            binding.name.text = build.name
            // **the commit rather than the build number, wherever there is one.** sharpemuVersion
            // is upstream's tag and our fork moves faster than upstream, so two builds of one tag
            // are routine and look identical without it. the build number is bookkeeping — ours is
            // when it was packaged — so it is the fallback for a build that recorded no commit.
            val version = if (build.commit.isEmpty()) {
                context.getString(R.string.build_version, build.sharpemuVersion, build.packagedAt)
            } else {
                context.getString(R.string.build_version_commit, build.sharpemuVersion, build.shortCommit())
            }
            // **whoever produced it goes on the version line, where a driver card puts the same
            // claim.** it is a build's second identity — two zips of one commit differ by who made
            // them and by nothing else a person can see — and it takes no line of its own, because
            // there is no fifth line to take. absent on a build packaged before the field existed
            // and on the one that shipped with the app, and the line is then what it always was.
            binding.version.text = if (build.author.isEmpty()) {
                version
            } else {
                context.getString(R.string.build_attribution, version, build.author)
            }

            // **a build this app cannot run is drawn and marked, not hidden.** an import refuses one,
            // so the only way it reaches the list is by having been staged — and the screen it shows
            // up on is where somebody should find out why. it takes the fourth line rather than
            // adding a fifth: why a build cannot run outranks what its packager wrote about it.
            val runnable = build.runnable()
            if (!runnable) {
                binding.description.text =
                    context.getString(R.string.build_unrunnable, build.hostContract)
                binding.description.setTextColor(
                    ManagerCard.colour(binding.root, com.google.android.material.R.attr.colorError)
                )
            } else {
                // **the directory it runs from is the fallback, rather than an empty line.** a build
                // packaged with no notes is ordinary and a blank fourth line is what a reserved slot
                // looks like; the folder name is always there, is different on every card, and is
                // what a run is attributed to in a log.
                binding.description.text = build.notes.ifEmpty { build.folder }
                binding.description.setTextColor(
                    ManagerCard.colour(
                        binding.root,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    )
                )
            }

            binding.selected.isChecked = entry.selected
            binding.selected.isEnabled = runnable
            binding.root.isEnabled = runnable
            binding.root.setOnClickListener { if (runnable) onSelect(entry) }

            // **the bundled build has no delete button**, exactly as the system GPU driver has none.
            // it comes back with the next launch of the app, so a delete would be a button that
            // undoes itself — and there would then be no build at all until one was imported.
            binding.delete.visibility = if (bundled) View.GONE else View.VISIBLE
            binding.delete.setOnClickListener { onDelete(entry) }
            ManagerCard.scrollLongLines(
                entry.selected, binding.name, binding.version, binding.description,
            )
        }

        private fun badge(text: Int, background: Int, foreground: Int) =
            ManagerCard.badge(binding.badge, text, background, foreground)
    }
}
