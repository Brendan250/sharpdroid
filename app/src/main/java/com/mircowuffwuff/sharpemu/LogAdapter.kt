package com.mircowuffwuff.sharpemu

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
// the colour roles are Material's own attributes, and this module's R does not carry them: a
// non-transitive R class holds what the module itself declares and nothing a library does.
import com.google.android.material.R as MaterialR

/**
 * The lines in the log window on the panel the back button opens over a running game.
 *
 * **It holds the lines it has been given and nothing decides which they are here.** What arrives is
 * whatever the process printed, in the order it printed it, and the window it is a window onto is the
 * host layer's — see [HostLayer.nativeLogRange]. This is the same cap as that ring's, kept on this
 * side too so that a viewer left open for an afternoon holds what the ring holds and not a growing
 * copy of everything it ever held.
 *
 * **Three colours, not the desktop viewer's six**, and the missing three are a deliberate answer
 * rather than a shortfall. That viewer draws info blue and warnings amber against one fixed dark
 * palette because it has exactly one; this app has four, two of which are monochrome on purpose, and
 * a hue introduced here would be the one hue in them. So what a line gets is a role every scheme
 * names: quiet for the levels nobody reads unless they are hunting, ordinary for the rest, and the
 * error role for anything that went wrong. **Warnings share the error role deliberately** — no scheme
 * here has a warning colour, and inventing one is the mistake this paragraph exists to prevent.
 */
class LogAdapter(context: Context) : RecyclerView.Adapter<LogAdapter.Line>() {

    private val lines = ArrayList<String>()

    private val ordinary = MaterialColors.getColor(context, MaterialR.attr.colorOnSurface, 0)
    private val quiet = MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, 0)
    private val loud = MaterialColors.getColor(context, MaterialR.attr.colorError, 0)

    class Line(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun getItemCount(): Int = lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Line = Line(
        LayoutInflater.from(parent.context).inflate(R.layout.item_log_line, parent, false) as TextView
    )

    override fun onBindViewHolder(holder: Line, position: Int) {
        val line = lines[position]
        holder.text.text = line
        holder.text.setTextColor(colourOf(line))
    }

    /**
     * Adds what arrived since the last poll, dropping from the front when the cap is passed.
     *
     * **The two notifications are separate on purpose.** A removal at the front and an insertion at
     * the end are different events to a list that is scrolled, and telling it that everything changed
     * would be telling it to rebuild every visible row — which detaches views, and a detached view
     * jumps its drawables to their end state.
     */
    fun append(arrived: Array<String>) {
        if (arrived.isEmpty()) {
            return
        }
        val at = lines.size
        lines.addAll(arrived)
        notifyItemRangeInserted(at, arrived.size)

        val over = lines.size - CAP
        if (over > 0) {
            lines.subList(0, over).clear()
            notifyItemRangeRemoved(0, over)
        }
    }

    /** Emptied when the panel closes, so a run that is being played holds none of this. */
    fun clear() {
        val had = lines.size
        if (had == 0) {
            return
        }
        lines.clear()
        notifyItemRangeRemoved(0, had)
    }

    /**
     * The most recent lines as one block of text, newest last, under [budget] characters.
     *
     * **A budget rather than a line count, because the clipboard is a Binder transaction** and what
     * that fails on is a size in bytes. A line here is anything from a word to the 3800 characters the
     * log pump breaks a long one at, so a count of lines predicts nothing about the total.
     */
    fun tail(budget: Int): String {
        var from = lines.size
        var total = 0
        while (from > 0) {
            val next = total + lines[from - 1].length + 1
            if (next > budget) {
                break
            }
            total = next
            --from
        }
        return lines.subList(from, lines.size).joinToString("\n")
    }

    fun isEmpty(): Boolean = lines.isEmpty()

    /**
     * Which of the three a line takes.
     *
     * The markers are the emulator's own level labels, which is what its logger writes and what the
     * desktop viewer colours by. **A line of ours carries none of them and is ordinary**, which is
     * correct: the host layer says what it did rather than at what level it did it, and a line of its
     * that matters says so in words.
     */
    private fun colourOf(line: String): Int = when {
        line.contains("[ERROR]") || line.contains("[CRITICAL]") || line.contains("[WARNING]") -> loud
        line.contains("[DEBUG]") || line.contains("[TRACE]") -> quiet
        else -> ordinary
    }

    private companion object {
        /** The ring's own capacity, so that the two hold the same log rather than two lengths of it. */
        const val CAP = 4000
    }
}
