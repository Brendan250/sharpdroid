package com.mircowuffwuff.sharpdroid

/**
 * one row in a settings section.
 *
 * **typed rows in a list rather than a `PreferenceScreen`, which is the yuzu lineage's shape** -- see
 * Eden's `SettingsAdapter` and the item classes beside it. it is why their settings screens look the
 * way they do, and following it is the whole reason this app took the AndroidX dependency graph:
 * being able to read one of Eden's screens and carry the pattern across is worth more than being
 * able to build offline.
 *
 * **a row knows its key, and the key is what makes "unset" reachable.** [Settings.isSet] answers
 * whether the user has ever touched this row, which decides whether a long press offers *Use
 * default* at all. a row with no key -- an action, a header, a permission this app does not own -- is
 * never in either state.
 *
 * **nothing on screen distinguishes a set row from an untouched one**, and that is a trade rather
 * than an omission: a mark saying so has to hold its width on every row, which indents the whole
 * list to annotate one of them. the distinction is real -- only a set row reaches a launch -- and the
 * cost of not drawing it is that the long press is undiscoverable. the per-game scene needs a
 * visible affordance for exactly this, and that is the place to reconsider it.
 */
sealed class SettingRow {

    /** the key this row reads and writes, or null for a row that is not a stored value. */
    open val key: String? = null

    /**
     * a divider with a label, for a subsection inside a section.
     *
     * **a label above a run of rows, never another button press.** a subsection is a grouping and
     * not a destination, so hiding one behind a tap adds a screen without adding a choice.
     *
     * **a page of its own is a different thing and this rule does not forbid it.** what it forbids
     * is a run of two or three rows put behind a press for tidiness; a page long enough to want
     * headers of its own, reached from a [Screen] row that reads out what is chosen inside it, is a
     * destination that answers a question rather than a grouping that hides one.
     */
    data class Header(val title: Int) : SettingRow()

    /** a boolean, drawn as a Material switch. */
    data class Switch(
        override val key: String,
        val title: Int,
        val summary: Int,
        val default: Boolean,
    ) : SettingRow()

    /**
     * a choice from a fixed list, drawn as a row that opens a single-choice dialog.
     *
     * [values] is what is stored and [entries] is what is shown, one to one. they are separate
     * because what the payload parses -- `0.75` -- is not what a person should have to read.
     */
    data class Dropdown(
        override val key: String,
        val title: Int,
        val summary: Int,
        val entries: Array<String>,
        val values: Array<String>,
        val default: String,
        /**
         * what the row reads instead of the chosen entry, or null to read that entry.
         *
         * **a fixed list can stop describing a configuration, and a row that went on naming an
         * entry then says something untrue.** the JIT preset is the case: somebody who has changed
         * several of the settings a rung covers may sit nearer a different rung than the one they
         * chose, so the honest reading names none of them. naming a nearer one is not the repair
         * either, since resemblance cannot be computed and a label that occasionally snapped
         * elsewhere would surprise more than one that declines to guess.
         *
         * **the dialog says the same thing by checking nothing**, which is the one state a
         * single-choice list has that means exactly this.
         */
        val reading: String? = null,
    ) : SettingRow() {
        // an Array in a data class gives identity equals/hashCode, which would make two rows built
        // from the same arrays unequal. nothing here compares rows, and saying so is cheaper than an
        // override nothing calls.
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /**
     * a row that opens a screen of its own, showing what is currently chosen underneath it.
     *
     * **[value] is a string rather than a resource**, which is the difference between this and
     * [Dropdown]: what it shows is the name of something on the device -- a build -- rather than one
     * of a fixed set of labels this app shipped.
     *
     * **there is no `enabled` flag**, because nothing here greys a row out: exactly one build ships
     * per APK, so there is no recommendation to follow and no toggle to govern the build row. a flag
     * with no caller is a flag that is wrong by the time something wants it.
     *
     * **[key] is null for a screen that stores nothing.** the build and driver rows each name a stored
     * choice, and the long press puts it back; the folder manager is a place to go rather than a value
     * that was picked, so there is no default for it to go back to and no gesture on it.
     */
    data class Screen(
        override val key: String?,
        val title: Int,
        val summary: Int,
        val value: String,
        /**
         * whether [value] names something, or reports that there is nothing.
         *
         * the value line is drawn in the accent, which is what marks it as the answer to the row.
         * "None" is not an answer of that kind -- it is the absence of one -- so it is drawn in the
         * body colour instead, and reads as a state rather than as a choice somebody made.
         */
        val chosen: Boolean = true,
        val onClick: () -> Unit,
    ) : SettingRow()

    /**
     * a colour, drawn as a swatch and picked in a dialog.
     *
     * **it appears under the Theme row only while a Custom theme is chosen**, which is why it is a
     * row type rather than a section of its own: a colour with no scheme to seed would be a control
     * that does nothing, and a scheme with no colour would be a theme nobody can change.
     */
    data class Colour(
        override val key: String,
        val title: Int,
        val summary: Int,
        val colour: Int,
        val onClick: () -> Unit,
    ) : SettingRow()

    /**
     * a switch showing state this app does not own -- today, the all-files permission.
     *
     * **it shows rather than sets, and the difference is the platform's rather than a design
     * choice.** `MANAGE_EXTERNAL_STORAGE` is granted in android's own settings and nowhere else, so
     * the switch cannot be flipped from here: tapping the row opens that screen, and the state is
     * read back when the user comes back. it is drawn as a switch because a switch is what the thing
     * *is*, and a row whose description had to begin with "Off." was saying in a sentence what a
     * widget says at a glance.
     *
     * it carries no key. there is nothing stored, so there is no default to go back to and no long
     * press.
     */
    data class External(
        val title: Int,
        val summary: Int,
        val checked: Boolean,
        val onClick: () -> Unit,
    ) : SettingRow()

}
