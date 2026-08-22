package com.mircowuffwuff.sharpdroid

import android.content.Context
import android.content.SharedPreferences

/**
 * how long the last boot took to reach each of its checkpoints, so the next one can be drawn.
 *
 * **a boot has no progress of its own -- it has a position, and a position is not a fraction.** the
 * host layer says which of eleven checkpoints a run has passed; turning that into a bar needs to
 * know how far apart they are in time, and the only honest source for that is a boot that already
 * happened. so this is a record of one, kept per launch configuration and read back at the next.
 *
 * ### the split, which is the whole reason this is not one number
 *
 * **a boot is a shared prefix and a per-title tail, and they are recorded separately.** everything up
 * to the emulator reading the game's identity is the emulator starting itself and is the same
 * whichever game is launched -- measured at fifteen milliseconds apart across three titles, at every
 * CPU clock. everything after it is that game's own, and the three here differ by seconds.
 *
 * that is what lets a title that has never booted still get a determinate bar: it inherits a measured
 * prefix and borrows a tail from the median of every tail on the device. the borrowed half is the
 * weakest thing here -- tails at one clock spanned 2.4 s to 4.2 s -- so it is wrong by tens of percent,
 * for the last third of one boot, once per title, and right from then on.
 *
 * ### the keys
 *
 * **the prefix is keyed by the build and the JIT preset**, which are the two things that change how
 * long the emulator takes to start itself. **the tail is keyed by the game and the GPU driver**,
 * because Vulkan is untouched until the last second of a boot -- the thunk attaches at around +6.3 s
 * of a 6.7 s boot -- so a driver cannot move the prefix and can move the tail.
 *
 * **the CPU clock is deliberately not a key, and that is measured rather than assumed.** a boot spans
 * 4.1 s to 9.4 s across this device's four underclock profiles, and the ratio between a recorded time
 * and a live one at the *first* checkpoint predicts the first frame to within about 4% at every one
 * of them -- so the rescale in [GuestLoading] absorbs a clock change, where a key would treat one as
 * "no record" and spend a whole boot on a spinner. what that costs is one stall, once: after a
 * profile change the first segment draws at the old rate and the bar waits at the first checkpoint,
 * about four tenths of a second in the worst pairing here, and the record is right from the next
 * boot.
 *
 * **the limit of that is a boot doing more *work* rather than the same work slower.** a cold shader
 * cache against a warm one is not something a ratio taken from the phases before it can predict, and
 * nothing measured here speaks to it.
 *
 * ### where it lives
 *
 * **a `SharedPreferences` file of its own, written and read only by [MainActivity].** that activity is
 * `:guest`, a process of its own, and `SharedPreferences` is cached per process and is not coherent
 * across them -- a store the settings scenes also touched would be two processes disagreeing about a
 * file. nothing outside `:guest` has any use for this.
 *
 * **it is a measurement and not a setting**, which is why a settings reset leaves it alone: it is
 * derived from boots that happened, it costs one indeterminate boot to lose, and it rebuilds itself.
 */
class BootRecord private constructor(private val prefs: SharedPreferences) {

    /**
     * what each checkpoint's elapsed time is expected to be, in milliseconds since the host layer
     * started, or null when there is nothing to predict from.
     *
     * **null is the fresh-install answer and it means an indeterminate bar**, not a bar at zero. the
     * two halves arrive together -- a device with a prefix has at least one tail -- so a null here is
     * almost always the first launch on an install.
     *
     * the map is keyed by the host layer's own checkpoint ids, so an id this app has never heard of
     * carries a time like any other and an id that disappears takes its own entry with it.
     */
    fun expected(build: String, preset: String, game: String, driver: String): Map<String, Long>? {
        val prefix = read(prefixKey(build, preset)) ?: return null
        val at = prefix[SPLIT] ?: return null
        val tail = read(tailKey(game, driver)) ?: borrowedTail(driver) ?: return null

        val expected = LinkedHashMap<String, Long>(prefix)
        // the tail is stored relative to the split, which is what makes it transplantable: the same
        // game's tail is the same tail whether the emulator took two seconds to start or four.
        tail.forEach { (id, relative) -> expected[id] = at + relative }
        return monotone(expected).takeIf { it.containsKey(TERMINAL) }
    }

    /**
     * files a finished boot, from the array the host layer stamped its own checkpoints with.
     *
     * **called the instant the picture appears, never at the end of the run.** a run ends by
     * `exit_group`, which ends the process from inside the guest -- `nativeRun` never returns and
     * `onDestroy` never happens -- so anything deferred to teardown is never written at all.
     *
     * **a boot that did not reach both ends of the split is not recorded**, and the reason is that
     * half of it would be unattributable: without the split point there is no way to say which of the
     * times belong to the emulator and which to the game, and filing the game's under the emulator's
     * key would poison every other title's prediction. it says so rather than failing quietly, since
     * the way this rots is upstream renaming the line the split is taken from.
     */
    fun record(
        build: String,
        preset: String,
        game: String,
        driver: String,
        ids: Array<String>,
        times: LongArray,
    ) {
        val reached = LinkedHashMap<String, Long>()
        for (i in ids.indices) {
            // -1 is a real answer rather than a zero: a checkpoint no line matched and one reached in
            // the same instant as its neighbour would otherwise look alike.
            if (i < times.size && times[i] >= 0) {
                reached[ids[i]] = times[i]
            }
        }
        val at = reached[SPLIT]
        val end = reached[TERMINAL]
        if (at == null || end == null) {
            AppLog.w(TAG, "[app] this boot is not recorded: it passed " + reached.size + " of "
                    + ids.size + " checkpoints and needs both '" + SPLIT + "' and '" + TERMINAL
                    + "'. the next boot of this game draws an indeterminate bar")
            return
        }

        val prefix = LinkedHashMap<String, Long>()
        val tail = LinkedHashMap<String, Long>()
        var past = false
        for (id in ids) {
            val ms = reached[id] ?: continue
            if (past) {
                tail[id] = ms - at
            } else {
                prefix[id] = ms
            }
            if (id == SPLIT) {
                past = true
            }
        }

        prefs.edit()
            .putString(prefixKey(build, preset), write(prefix))
            .putString(tailKey(game, driver), write(tail))
            .apply()
    }

    /**
     * the median tail on this device, for a game that has never booted.
     *
     * **per checkpoint rather than per boot**, so a title whose tail is missing one entry still
     * contributes every entry it has. the medians are then forced upward-only by [monotone]: taking
     * eleven independent medians of sequences that each skip a different entry can produce a
     * timeline that goes backwards, which the estimate would read as a boot undoing itself.
     *
     * **the same driver first, and any driver rather than nothing.** a driver moves the tail, which
     * is why it is a key at all -- but a borrowed tail is already the roughest thing in this file, and
     * one from the wrong driver is a far better answer than an indeterminate bar.
     */
    private fun borrowedTail(driver: String): Map<String, Long>? =
        tails(driver).takeIf { it.isNotEmpty() }?.let { median(it) }
            ?: tails(null).takeIf { it.isNotEmpty() }?.let { median(it) }

    private fun tails(driver: String?): List<Map<String, Long>> {
        val suffix = if (driver == null) null else SEPARATOR + driver
        return prefs.all.keys
            .filter { it.startsWith(TAIL) && (suffix == null || it.endsWith(suffix)) }
            .mapNotNull { read(it) }
    }

    private fun median(tails: List<Map<String, Long>>): Map<String, Long> {
        val byId = LinkedHashMap<String, MutableList<Long>>()
        tails.forEach { tail ->
            tail.forEach { (id, ms) -> byId.getOrPut(id) { mutableListOf() }.add(ms) }
        }
        val medians = LinkedHashMap<String, Long>()
        byId.forEach { (id, values) ->
            values.sort()
            medians[id] = values[values.size / 2]
        }
        return medians
    }

    /** the same order the map already has, with every entry at least as late as the one before it. */
    private fun monotone(timeline: Map<String, Long>): Map<String, Long> {
        var highest = 0L
        val fixed = LinkedHashMap<String, Long>(timeline.size)
        timeline.forEach { (id, ms) ->
            highest = maxOf(highest, ms)
            fixed[id] = highest
        }
        return fixed
    }

    /**
     * `id=ms;id=ms`, in order.
     *
     * a string rather than a set of keys per checkpoint, because what is read back is always the
     * whole timeline and never one entry of it -- and because the order is part of the value.
     */
    private fun write(timeline: Map<String, Long>): String =
        timeline.entries.joinToString(";") { it.key + "=" + it.value }

    private fun read(key: String): Map<String, Long>? {
        val stored = prefs.getString(key, null)?.takeIf { it.isNotEmpty() } ?: return null
        val timeline = LinkedHashMap<String, Long>()
        stored.split(";").forEach { entry ->
            val eq = entry.indexOf('=')
            // a value this cannot parse is one this app wrote in an older shape. dropping the entry
            // rather than the record leaves a timeline one checkpoint coarser, which is what an
            // unmatched checkpoint already costs.
            val ms = if (eq > 0) entry.substring(eq + 1).toLongOrNull() else null
            if (ms != null) {
                timeline[entry.substring(0, eq)] = ms
            }
        }
        return timeline.takeIf { it.isNotEmpty() }
    }

    private fun prefixKey(build: String, preset: String) =
        PREFIX + clean(build) + SEPARATOR + clean(preset)

    private fun tailKey(game: String, driver: String) =
        TAIL + clean(game) + SEPARATOR + clean(driver)

    /** the separator out of the parts a key is made of, so two keys cannot collide by containing it. */
    private fun clean(part: String) = part.replace(SEPARATOR, "_")

    companion object {

        private const val TAG = "sharpdroid"
        private const val STORE = "boot-record"
        private const val PREFIX = "prefix/"
        private const val TAIL = "tail/"
        private const val SEPARATOR = "/"

        /**
         * the checkpoint the boot splits at: the emulator has read the game's identity, so everything
         * after it is that game's own work.
         *
         * **it is the host layer's id and this app does not own it.** if it ever stops being in the
         * table, nothing here breaks -- [record] declines to file a boot it cannot split, [expected]
         * answers null, and every launch draws an indeterminate bar with the phase text still moving.
         */
        private const val SPLIT = "title"

        /** the last entry, which is the thunk's first presented frame rather than anything printed. */
        private const val TERMINAL = "first-frame"

        /** stock, for a launch that chose no driver. a key needs a value and null is not one. */
        const val STOCK_DRIVER = "stock"

        /** FEXCore's own defaults, for a launch that named no preset. */
        const val DEFAULT_PRESET = "default"

        @JvmStatic
        fun of(context: Context): BootRecord =
            BootRecord(context.getSharedPreferences(STORE, Context.MODE_PRIVATE))
    }
}
