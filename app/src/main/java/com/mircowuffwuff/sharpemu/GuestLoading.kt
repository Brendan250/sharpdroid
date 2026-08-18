package com.mircowuffwuff.sharpemu

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * What a launch shows between the tap and the guest's first frame.
 *
 * **A launch is several seconds of black screen and this is the whole of what fills it.** The picture
 * belongs to the guest and the guest has not drawn one yet, so until it does the window is ours: the
 * game's own cover and name, the phase the boot is in, and a bar.
 *
 * ### where the bar's position comes from
 *
 * **The host layer reports a position and this turns it into a fraction.** It matches the emulator's
 * own log against an ordered table of checkpoints and answers how many have been passed — a position
 * along a boot, not a proportion of one, because the checkpoints are nowhere near evenly spaced in
 * time. Measured gaps on one boot ran 0.09, 0.88, 0.49, 0.63, 0.13, 0.32, 0.08, 0.37, 0.58 and
 * 0.13 s: a bar stepping a tenth per checkpoint jumps twice inside ninety milliseconds and then sits
 * dead for nearly a second, which reads as broken in a way a sweep never does.
 *
 * So the bar sweeps on the clock, against a total taken from [BootRecord] — the last boot of this
 * game in this configuration — and **each checkpoint corrects the rate rather than the position**.
 * The correction is one ratio: how long this boot took to reach the checkpoint, over how long the
 * record says it took. Applied at the *first* checkpoint alone that ratio predicts the first frame to
 * within about 4% across a 2.3x range of CPU clocks, and recomputing it at each one tightens an
 * estimate that was already close.
 *
 * **Nothing recorded is an indeterminate bar, not a bar at zero**, with the phase text still moving.
 * Deliberately not a spinner: that state is seen once per install, and it should not be the one
 * launch where the screen looks like a different screen.
 *
 * ### two rules that are not refinements
 *
 * **The position never goes backwards.** A rescale can shrink the remaining estimate below what has
 * already been drawn, and a bar that retreats says the boot has undone something.
 *
 * **And it never reaches full early.** It is held short of the end until the picture is actually up,
 * because a bar sitting at 100% on a screen that is still there is worse than one sitting at 97%.
 *
 * ### the mode, and the one switch there is
 *
 * **The bar starts indeterminate and only ever moves to determinate**, which is the direction Material
 * leaves unrestricted on a visible view. The exception is a fresh install, where the unpack is
 * genuinely determinate and the boot after it has nothing to predict from; that switch is made with
 * the bar hidden, at the same moment the phase text changes.
 */
class GuestLoading(
    private val context: Context,
    private val onFirstFrame: FirstFrame,
) {

    /**
     * The boot is over: the guest has presented a frame.
     *
     * Handed the host layer's own stamps for each checkpoint, which is what [BootRecord] files —
     * **not** when this class noticed them, since a poll carries up to a frame of jitter and the
     * array does not.
     */
    fun interface FirstFrame {
        fun onFirstFrame(times: LongArray)
    }

    private val main = Handler(Looper.getMainLooper())

    private val root: View =
        LayoutInflater.from(context).inflate(R.layout.view_guest_loading, null, false)

    private val cover: ShapeableImageView = root.findViewById(R.id.loading_cover)
    private val title: TextView = root.findViewById(R.id.loading_title)
    private val detail: TextView = root.findViewById(R.id.loading_detail)
    private val bar: LinearProgressIndicator = root.findViewById(R.id.loading_bar)
    private val percent: TextView = root.findViewById(R.id.loading_percent)

    /** The host layer's own checkpoint ids, in order. Asked for once, at [booting]. */
    private var ids: Array<String> = emptyArray()

    /** What each checkpoint's elapsed time is expected to be, or null for an indeterminate bar. */
    private var expected: Map<String, Long>? = null

    /** Where the whole boot is expected to end, in milliseconds since the host layer started. */
    private var ends: Long = 0

    /** `SystemClock.uptimeMillis()` at the moment before `nativeRun`, which is the host layer's t=0. */
    private var startedAt: Long = 0

    /**
     * How much slower this boot is running than the recorded one.
     *
     * **One by construction until the first checkpoint**, because the record *is* the previous boot
     * and before the first checkpoint there is nothing to compare against. That is what bounds the
     * cost of a CPU profile change to a single boot, and it is why nothing here reads a clock
     * frequency to guess with: neither cluster's cap gives a law clean enough to build a prior from,
     * and keying the record by clock would make a profile change read as "no record" instead.
     */
    private var scale: Double = 1.0

    private var reached = 0

    /** The last thousandth drawn, which is also the floor the next one is held to. */
    private var drawn = 0

    /** The last whole percent written into the label, so the text is not rebuilt sixty times a second. */
    private var labelled = -1

    /**
     * The last whole percent of an unpack, which is the one figure here counted off another thread.
     *
     * **Its own field rather than [drawn], which is the main thread's.** The two phases never overlap
     * — an unpack finishes before the host layer starts — but sharing one counter across two threads
     * to express that would be a claim about ordering rather than a use of it.
     */
    @Volatile
    private var unpacked = -1

    private var polling = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!polling) {
                return
            }
            poll()
            if (polling) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    init {
        bar.isIndeterminate = true
        percent.visibility = View.GONE
        detail.setText(R.string.loading_starting)
    }

    /** Added over the surface by [MainActivity], under the back overlay. */
    fun view(): View = root

    /**
     * Which game is loading.
     *
     * **Both of these are handed down by the launch rather than read here**, and that is one
     * `putExtra` in [GameLaunch] rather than this activity learning to parse a dump. A launch that
     * names no display name is `am start`, and the folder is what it has to say instead; a launch that
     * names no artwork draws none, since a placeholder in a popup reads as a cover that failed to
     * load rather than as one nobody sent.
     *
     * @param icon what coil is handed — a `File` for a staged game, a `content://` uri for a granted
     *     one. **A granted game's uri reaches `:guest` intact**: the grant belongs to the package
     *     rather than to a process, which is the same reason such a game boots in here at all.
     */
    fun describe(name: String, icon: Any?) {
        title.text = name
        if (icon == null) {
            cover.visibility = View.GONE
            return
        }
        cover.visibility = View.VISIBLE
        cover.load(icon) {
            placeholder(R.drawable.ic_game_placeholder)
            error(R.drawable.ic_game_placeholder)
        }
    }

    /**
     * One of the app's own asset trees being written out, which happens once per install.
     *
     * **Called from whichever thread is doing the unpacking**, which is the host layer's, and posted
     * per whole percent rather than per read: the bundled build is one 61 MB file and twenty-six small
     * ones, so a post per 64 KB buffer would be over a thousand messages to a main thread that has
     * nothing else to say.
     *
     * **This is a phase and not a screen of its own.** It is named on the same line every boot
     * checkpoint is named on, and it fills the same bar — a launch is one wait, whatever the app is
     * doing during it.
     */
    fun unpacking(phase: Int, done: Long, total: Long) {
        val whole = if (total > 0) (done * 100 / total).toInt() else 0
        if (whole == unpacked) {
            return
        }
        unpacked = whole
        main.post {
            detail.setText(phase)
            determinate()
            // animated, because this is posted per whole percent and a step of one is visible on a
            // bar this wide. it costs nothing on a value that has not moved.
            bar.setProgressCompat(whole * 10, true)
            show(whole * 10)
        }
    }

    /**
     * The host layer is about to start, so this is where the boot's own clock begins.
     *
     * **The two halves of the timeline are not the same clock**, which is why the moment is taken
     * here: the host layer measures from its own entry and the app's wait began at the tap, with the
     * unpack in between. Everything the host layer reports is relative to [startedAt].
     *
     * Called from the host layer's thread, immediately before `nativeRun`.
     */
    fun booting(expected: Map<String, Long>?) {
        val at = SystemClock.uptimeMillis()
        main.post {
            startedAt = at
            ids = HostLayer.nativeBootCheckpointIds()
            this.expected = expected
            ends = expected?.get(ids.lastOrNull() ?: "") ?: 0
            // the unpack's own progress is not part of the boot's, and reserving a segment for it
            // would put a hole at the start of every launch that does not unpack anything -- which is
            // every launch but the first of an install. so the bar restarts, unanimated, under a
            // phase line that has already changed.
            drawn = 0
            labelled = -1
            if (ends > 0) {
                determinate()
                bar.setProgressCompat(0, false)
                show(0)
            } else {
                indeterminate()
            }
            detail.setText(R.string.loading_starting)
            polling = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    /**
     * One animation frame's worth of asking where the boot has got to.
     *
     * One JNI call, which is a single relaxed load on the other side — around 420 of them over a
     * seven second boot. The times array is read only when the position actually moves.
     */
    private fun poll() {
        val now = HostLayer.nativeBootCheckpointsReached()
        if (now > reached) {
            // **it can jump by more than one**, when two checkpoints fall inside one frame or when a
            // pattern the emulator no longer prints is passed over. so the label is taken from where
            // the position now is, never from a step of one.
            reached = now
            advanced()
        }
        if (reached >= ids.size && ids.isNotEmpty()) {
            complete()
            return
        }
        sweep()
    }

    /** A checkpoint has been passed: name the phase, and correct the rate. */
    private fun advanced() {
        val timeline = expected
        val times = HostLayer.nativeBootCheckpointTimes()

        // **the newest checkpoint that has a line to say**, walking back rather than reading the top
        // one: a checkpoint this app has no text for still advances the position, and taking its
        // absence as "say nothing" would blank the line instead of leaving the last phase up.
        for (i in reached - 1 downTo 0) {
            val phase = label(ids[i])
            if (phase != null) {
                detail.setText(phase)
                break
            }
        }

        if (timeline == null) {
            return
        }
        // the latest checkpoint this boot actually reached that the record also has a time for. one
        // that was passed over carries -1 and is skipped, which is the same rule the record files by.
        for (i in reached - 1 downTo 0) {
            val was = timeline[ids[i]] ?: continue
            if (i < times.size && times[i] > 0 && was > 0) {
                scale = times[i].toDouble() / was.toDouble()
                break
            }
        }
    }

    private fun sweep() {
        if (ends <= 0) {
            return
        }
        val elapsed = SystemClock.uptimeMillis() - startedAt
        val predicted = scale * ends
        val thousandths = if (predicted > 0) (elapsed * 1000 / predicted).toInt() else 0
        // never backwards, and never full while the screen is still up.
        val held = thousandths.coerceIn(drawn, CEILING)
        drawn = held
        bar.progress = held
        show(held)
    }

    /**
     * The guest has presented its first frame.
     *
     * **The record is written here rather than when the run ends, because the run does not end here.**
     * A guest that calls `exit_group` ends the process from inside itself: `nativeRun` never returns
     * and `onDestroy` never happens, so anything deferred to teardown is never written at all.
     */
    private fun complete() {
        polling = false
        Choreographer.getInstance().removeFrameCallback(frame)
        val times = HostLayer.nativeBootCheckpointTimes()
        root.visibility = View.GONE
        onFirstFrame.onFirstFrame(times)
    }

    /** The figure, as a whole percent, rebuilt only when the whole percent moves. */
    private fun show(thousandths: Int) {
        val whole = thousandths / 10
        if (whole == labelled) {
            return
        }
        labelled = whole
        percent.text = context.getString(R.string.loading_percent, whole)
    }

    private fun determinate() {
        percent.visibility = View.VISIBLE
        bar.isIndeterminate = false
    }

    /**
     * **Determinate to indeterminate is the restricted direction**, and Material refuses it outright
     * on an indicator the user can see rather than deferring it — so the bar is taken off screen for
     * the change and put back. It is reachable on one launch: a fresh install, whose unpack is
     * measurable and whose boot has no record to measure against yet.
     */
    private fun indeterminate() {
        if (bar.isIndeterminate) {
            return
        }
        percent.visibility = View.GONE
        bar.visibility = View.INVISIBLE
        bar.isIndeterminate = true
        bar.visibility = View.VISIBLE
    }

    /**
     * What the screen says while each phase is under way.
     *
     * **Keyed by the host layer's ids, and an id with no entry changes nothing on screen.** That is
     * what lets the table on the other side gain a checkpoint without this app having to know: an
     * unrecognised one still advances the position and still corrects the rate, and the line simply
     * keeps saying what it was saying.
     *
     * Two of them read the same, and that is not an oversight — the emulator taking its first module
     * to `Execute` and scheduling the game's first thread are both, to somebody waiting, the game
     * starting.
     */
    private fun label(id: String): Int? = when (id) {
        "emulator-start" -> R.string.loading_emulator_start
        "runtime" -> R.string.loading_runtime
        "clock" -> R.string.loading_clock
        "address-space" -> R.string.loading_address_space
        "hle-warm" -> R.string.loading_hle_warm
        "title" -> R.string.loading_read_game
        "modules" -> R.string.loading_modules
        "execute", "guest-threads" -> R.string.loading_execute
        "video-out" -> R.string.loading_video_out
        else -> null
    }

    private companion object {
        /**
         * How far the bar may get before the picture is actually up, in thousandths.
         *
         * It is short of the end rather than at it because the estimate can be early: a boot that
         * beats its own record would otherwise sit at a full bar with the screen still in front of
         * the game, which reads as something having hung.
         */
        const val CEILING = 970
    }
}
