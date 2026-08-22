package com.mircowuffwuff.sharpdroid

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mircowuffwuff.sharpdroid.databinding.ItemFolderBinding

/**
 * the rows of [FoldersActivity]: one granted folder each.
 *
 * **the whole row is not clickable and the button is.** there is exactly one thing to do to a folder
 * and it is destructive, so a row that acted on a tap would be a row that removes a folder when
 * somebody meant to look at it.
 */
class FolderAdapter(
    private var items: List<Item>,
    private val onRemove: (Item) -> Unit,
) : RecyclerView.Adapter<FolderAdapter.Holder>() {

    /** a granted folder and what to call it -- see [GameLibrary.label] for why it is the document id. */
    data class Item(val tree: Uri, val label: String)

    fun submit(newItems: List<Item>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemFolderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Item) {
            binding.label.text = item.label
            binding.remove.setOnClickListener { onRemove(item) }
        }
    }
}
