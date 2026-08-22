package com.mircowuffwuff.sharpdroid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mircowuffwuff.sharpdroid.databinding.ItemGameBinding

/**
 * the game grid's tiles.
 *
 * one tile is one directory: a tap runs it, holding it opens that game's own settings, and pulling
 * the grid refreshes it.
 */
class GameAdapter(
    private var games: List<Game>,
    private val onLaunch: (Game) -> Unit,
    private val onConfigure: (Game) -> Unit,
) : RecyclerView.Adapter<GameAdapter.Holder>() {

    class Holder(val binding: ItemGameBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemGameBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val game = games[position]
        holder.binding.name.text = game.name
        // **every tile scrolls its own name, where a manager card scrolls only the chosen one.** a
        // marquee runs while its view is selected and there is no selection on this screen -- nothing
        // here is picked before it is launched -- so the choice is all of them or none. it costs
        // nothing on a name that fits, since android animates only when the text is wider than the
        // view, and on a grid of covers the few that overflow are the few worth reading.
        holder.binding.name.isSelected = true
        // **staged is the only provenance that says so**, and it is set per bind because a recycled
        // tile keeps whatever the previous game left on it. see item_game.xml for why a granted game
        // wears nothing.
        holder.binding.badge.visibility =
            if (game.source is GameSource.Staged) View.VISIBLE else View.GONE
        // the title id is parsed and carried and is deliberately not drawn: a name is what a person
        // picks a game by, and every log line and script names the id itself. see item_game.xml.
        holder.binding.root.setOnClickListener { onLaunch(game) }
        // **holding a cover opens that game's settings, and nothing on the tile says so.** it is the
        // gesture the platform's own launchers put a shortcut menu behind, so it is where a person
        // looks first -- and a tile is artwork with a name on it, so any mark saying "hold me" would
        // be drawn over the one thing the grid exists to show. the whole tile is the target, which
        // is what makes it discoverable by accident as well as by habit.
        holder.binding.root.setOnLongClickListener {
            onConfigure(game)
            true
        }

        // **coil rather than a decode here, and it is the recycling that decides it.** icon0.png is
        // a quarter of a megabyte, so decoding it on the main thread would stutter a scroll, and
        // decoding it on a worker means a row that scrolled away before the bitmap arrived must not
        // receive it. load() cancels the previous request for this ImageView, samples to the size it
        // will be drawn at, and caches -- none of which is worth writing again.
        //
        // **it is handed a File or a content uri and neither is this class's business** -- coil loads
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
