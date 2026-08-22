package com.mircowuffwuff.sharpdroid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mircowuffwuff.sharpdroid.databinding.ItemManagerCardBinding

/**
 * draws the driver list: the system driver pinned at the top, then every package on the device.
 *
 * **the manager card, which the SharpEmu build manager draws too** -- a radio down the left, the name,
 * the version under it, a line of description under that, a badge above and a trash can on the right.
 * the layout and the three things every card does to itself are `item_manager_card.xml` and
 * [ManagerCard]; what is here is what a *driver* has to say on those four lines. no *Fetch*: there is
 * no index to fetch from, and a driver arrives as a zip.
 *
 * **every card is four lines, and every line has something true on it.** two cards sharing a grid
 * row are each as tall as their own content, so a card missing a line does not read as shorter -- it
 * reads as empty, next to a full one. [bind] is where each of the four is guaranteed.
 *
 * **the system driver is a row type of its own, unlike the build list's pinned card.** the bundled
 * build *is* a build -- it has a `meta.json`, a version and a commit -- so drawing it through the same
 * binding was right. the system driver is not a package at all, and giving it an empty identity to
 * draw would mean inventing fields for the one card nothing can read.
 */
class DriverAdapter(
    private var items: List<Item>,
    private val onSelectSystem: () -> Unit,
    private val onSelect: (DriverLibrary.Entry) -> Unit,
    private val onDelete: (DriverLibrary.Entry) -> Unit,
) : RecyclerView.Adapter<DriverAdapter.Holder>() {

    sealed class Item {
        data class System(val selected: Boolean) : Item()
        data class Driver(val entry: DriverLibrary.Entry) : Item()
    }

    fun submit(newItems: List<Item>) {
        val sameCount = newItems.size == items.size
        items = newItems
        // **every card is redrawn either way; the payload is what decides whether the views survive
        // it.** selecting one driver unselects another, so several cards change together and
        // there is nothing here a diff would spare -- but an unqualified `notifyDataSetChanged`
        // detaches every attached view and builds it again, and detaching a view jumps its drawables
        // to their end state. that ends the ripple running under the finger that asked for this, so
        // pressing a card that is not already selected answers with nothing at all. named with a
        // payload, each holder is reused and rebound where it stands and the ripple runs on.
        //
        // a card that arrives or leaves does change the count, and there the whole list is the
        // honest answer: nothing is being pressed when an import or a delete redraws this.
        if (sameCount) notifyItemRangeChanged(0, newItems.size, REDRAWN) else notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    private companion object {
        /**
         * handed to every redraw that keeps the same cards.
         *
         * **what it is does not matter and that it is there does**: RecyclerView reuses a holder
         * rather than replacing it for any change that carries a payload, and reuse is what keeps
         * the pressed view attached and its ripple alive.
         */
        val REDRAWN = Any()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        // one layout for both kinds of card, and for the other manager: they are the same thing on
        // the screen, and a second layout would drift from the first the moment either gained a line.
        Holder(ItemManagerCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        when (val item = items[position]) {
            is Item.System -> holder.bindSystem(item.selected)
            is Item.Driver -> holder.bind(item.entry)
        }
    }

    inner class Holder(val binding: ItemManagerCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /**
         * the card for the driver the device shipped with.
         *
         * **it has no delete button, exactly as Eden's does not.** there is nothing on disk to
         * remove, and it is the answer a deletion moves the selection back to -- so a button there
         * would be one with nothing to do and no way to undo itself.
         */
        fun bindSystem(selected: Boolean) {
            badge(R.string.driver_badge_system, com.google.android.material.R.attr.colorPrimary,
                com.google.android.material.R.attr.colorOnPrimary)
            binding.name.setText(R.string.driver_system)
            binding.version.setText(R.string.driver_system_version)
            binding.description.setText(R.string.driver_system_description)
            binding.description.setTextColor(
                ManagerCard.colour(
                    binding.root,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
            )

            binding.selected.isChecked = selected
            binding.selected.isEnabled = true
            binding.root.isEnabled = true
            binding.root.setOnClickListener { onSelectSystem() }
            binding.delete.visibility = View.GONE
            binding.delete.setOnClickListener(null)
            scrollLongLines(selected)
        }

        fun bind(entry: DriverLibrary.Entry) {
            val driver = entry.driver
            val context = binding.root.context

            // **where it came from, which is the one claim about a package that cannot be faked and
            // the one with a different answer per card.** there are three provenances and not two --
            // a package is the device's, or adb put it there, or it was imported from a zip -- and
            // leaving the third unlabelled is what makes a badge look optional. the vendor was the
            // alternative and it is the same word on every package in practice, so it would have
            // filled the slot without telling anybody anything.
            if (entry.internal) {
                badge(R.string.driver_badge_imported,
                    com.google.android.material.R.attr.colorSecondaryContainer,
                    com.google.android.material.R.attr.colorOnSecondaryContainer)
            } else {
                badge(R.string.build_badge_staged,
                    com.google.android.material.R.attr.colorSurfaceContainerHighest,
                    com.google.android.material.R.attr.colorOnSurfaceVariant)
            }

            binding.name.text = driver.name
            // version first, because that is what a bug report is asked for, and the author beside
            // it because a turnip build is identified by who cut it at least as often as by its
            // mesa version.
            val version = driver.driverVersion.ifEmpty { driver.packageVersion }
            val attribution = driver.attribution()
            binding.version.text = when {
                version.isEmpty() -> attribution
                attribution.isEmpty() -> version
                else -> context.getString(R.string.driver_version, version, attribution)
            }

            // **a package this device cannot load is drawn and marked, not hidden.** an import
            // refuses both of these, so the only way one reaches the list is by having been staged --
            // and the screen it appears on is where somebody should find out why. it takes the
            // fourth line rather than adding a fifth: why a driver cannot be used outranks what its
            // author called it.
            val usable = driver.usable()
            if (!usable) {
                binding.description.text = if (driver.minApi > android.os.Build.VERSION.SDK_INT) {
                    context.getString(
                        R.string.driver_unusable_min_api,
                        driver.minApi,
                        android.os.Build.VERSION.SDK_INT,
                    )
                } else {
                    context.getString(R.string.driver_unusable_library, driver.libraryName)
                }
                binding.description.setTextColor(
                    ManagerCard.colour(binding.root, com.google.android.material.R.attr.colorError)
                )
            } else {
                // **the library it loads is the fallback, rather than an empty line.** a package
                // with no description is common and a blank fourth line is exactly what a reserved
                // slot looks like; the library name is always there, is not the same on every
                // package, and is the thing somebody comparing two turnip builds actually looks up.
                binding.description.text =
                    driver.description.ifEmpty { driver.libraryName }
                binding.description.setTextColor(
                    ManagerCard.colour(
                        binding.root,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    )
                )
            }

            binding.selected.isChecked = entry.selected
            binding.selected.isEnabled = usable
            binding.root.isEnabled = usable
            binding.root.setOnClickListener { if (usable) onSelect(entry) }
            binding.delete.visibility = View.VISIBLE
            binding.delete.setOnClickListener { onDelete(entry) }
            scrollLongLines(entry.selected)
        }

        /**
         * the badge, its word and its two colours. always shown; only the values change.
         *
         * **the second badge slot stays empty on this screen.** provenance is the whole of what a
         * driver card claims about itself -- there is no series to be the newest of.
         */
        private fun badge(text: Int, background: Int, foreground: Int) =
            ManagerCard.badge(binding.badge, text, background, foreground)

        private fun scrollLongLines(chosen: Boolean) = ManagerCard.scrollLongLines(
            chosen, binding.name, binding.version, binding.description,
        )
    }
}
