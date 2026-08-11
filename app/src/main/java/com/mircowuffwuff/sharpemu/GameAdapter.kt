package com.mircowuffwuff.sharpemu

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mircowuffwuff.sharpemu.databinding.ItemGameBinding

/**
 * The game grid's tiles.
 *
 * One tile is one directory and one tap. Holding a tile opens per-game settings and pulling the grid
 * refreshes it — the second exists, the first does not, and both attach here rather than anywhere
 * else.
 */
class GameAdapter(
    private var games: List<Game>,
    private val onLaunch: (Game) -> Unit,
) : RecyclerView.Adapter<GameAdapter.Holder>() {

    class Holder(val binding: ItemGameBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemGameBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val game = games[position]
        holder.binding.name.text = game.name
        // **every tile scrolls its own name, where a manager card scrolls only the chosen one.** a
        // marquee runs while its view is selected and there is no selection on this screen — nothing
        // here is picked before it is launched — so the choice is all of them or none. it costs
        // nothing on a name that fits, since android animates only when the text is wider than the
        // view, and on a grid of covers the few that overflow are the few worth reading.
        holder.binding.name.isSelected = true
        // the title id is parsed and carried and is deliberately not drawn: a name is what a person
        // picks a game by, and every log line and script names the id itself. see item_game.xml.
        holder.binding.root.setOnClickListener { onLaunch(game) }

        // **coil rather than a decode here, and it is the recycling that decides it.** icon0.png is
        // a quarter of a megabyte, so decoding it on the main thread would stutter a scroll, and
        // decoding it on a worker means a row that scrolled away before the bitmap arrived must not
        // receive it. load() cancels the previous request for this ImageView, samples to the size it
        // will be drawn at, and caches — none of which is worth writing again.
        //
        // **it is handed a File or a content uri and neither is this class's business** — coil loads
        // either natively, which is why a granted dump's artwork needs no decoding or copying of
        // ours. GameSource is what knows the difference.
        //
        // a null model, and a uri that turns out not to be there, both resolve to the error drawable,
        // which is the same placeholder: a dump with no artwork looks like one that has not been
        // decoded yet, and "yet" is a few milliseconds.
        holder.binding.icon.load(game.icon) {
            placeholder(R.drawable.ic_game_placeholder)
            error(R.drawable.ic_game_placeholder)
        }
    }

    override fun getItemCount(): Int = games.size

    fun submit(games: List<Game>) {
        this.games = games
        // the whole list, rather than a DiffUtil pass: a rescan answers with a handful of
        // directories, and there is nothing on a row yet whose animation would be worth diffing for.
        notifyDataSetChanged()
    }
}
