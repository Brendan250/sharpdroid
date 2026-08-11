package com.mircowuffwuff.sharpemu

/**
 * The FEXCore JIT presets, as a named ladder from most correct to fastest.
 *
 * **The knobs are FEX's, the ladder is GameNative's, and the route is this project's own.**
 * GameNative reaches FEX's options by exporting `FEX_TSOENABLED` and friends into the environment
 * of a FEX process it launches. That route does not exist here and cannot be made to: those
 * variables are read by `EnvLoader` in FEX's frontend, and a process that hosts FEXCore as a
 * library does not build the frontend. So a variable by that name reaches nothing, silently, which
 * is exactly the shape of a setting that looks like it works. The host layer takes
 * `--fex Name=Value` instead and sets the option directly, refusing a name FEXCore does not have.
 *
 * **The names below are FEXCore's json spellings**, which is what `--fex` resolves against. They
 * are not the environment spellings with the prefix removed, even where the two happen to look
 * alike.
 *
 * **A preset is a host-layer argument and never a guest environment variable.** It is deliberately
 * absent from [Settings.guestEnvironment], because that map merges a build's own `env` underneath
 * the user's choices — and a JIT knob is a property of the host layer's correctness rather than a
 * preference a payload may express, the same rule `--smc` and `--asyncsig` already follow. A build
 * able to ask for [PERFORMANCE] would be a build able to break every launch of itself.
 */
object FexPreset {

    const val STABILITY = "stability"
    const val COMPATIBILITY = "compatibility"
    const val INTERMEDIATE = "intermediate"
    const val PERFORMANCE = "performance"
    const val EXTREME = "extreme"

    /** The order the slider runs in, and the values the store holds. */
    val ALL = arrayOf(STABILITY, COMPATIBILITY, INTERMEDIATE, PERFORMANCE, EXTREME)

    /**
     * What the row shows before it has been touched — and, uniquely, a rung that produces exactly
     * what an untouched row produces.
     *
     * **[INTERMEDIATE] is FEXCore's own defaults, and it sets nothing to get there.** That is what
     * makes this row honest rather than merely plausible: choosing the middle of the ladder and
     * never opening the screen at all are the same translation, so the row cannot name one thing
     * while the launch does another. It is also the only way a user can ask for FEXCore's defaults
     * from the control itself: this row is a slider, and every position the thumb can land on is a
     * rung — none of them means *unset*, which is reachable only by the long press that clears a row.
     *
     * **Compatibility would have been the obvious choice and is the wrong one.** `VectorTSOEnabled`
     * and `MemcpySetTSOEnabled` both default to off and Compatibility turns both on, asking for
     * atomic vector loadstores and atomic `REP MOVS`/`REP STOS` that a default run does not emit.
     * Measured on `Dreaming Sarah`, interleaved: 38.8 fps against 51.4. The name reads like the safe
     * middle and is the second most expensive rung on the ladder.
     */
    const val DEFAULT = INTERMEDIATE

    /**
     * Denuvo is not among them. GameNative carries a sixth preset by that name, tuned for a Windows
     * DRM that pairs self-modifying code with hypervisor detection — `SMCChecks=full` and
     * `HideHypervisorBit`. Neither has anything to answer on a PS5 title, and `SMCChecks` in
     * particular is not this ladder's to set: the host layer's VMA tracker has to agree with it, so
     * it belongs to `--smc` and is refused here.
     */
    private val REFUSED = setOf("SMCChecks", "IS64BIT_MODE", "GDBSERVER")

    /**
     * What a preset sets, as FEXCore option names.
     *
     * **Every rung names every knob**, rather than expressing itself as a change from the rung below
     * it. A preset that inherited would describe the ladder instead of the translation, and the
     * question a reader has here is what one setting does — not what it does differently.
     * [INTERMEDIATE] is the exception and names none of them, because FEXCore's defaults are what it
     * is: writing them out would be six values to keep in step with an upstream that owns them.
     *
     * **Intermediate is one knob away from GameNative's**, deliberately. Theirs sets
     * `X87ReducedPrecision`, emulating x87 at 64-bit rather than 80-bit precision — FEX's own text on
     * it warns of rendering bugs. Dropping it is what makes this rung equal to the defaults, and it
     * costs nothing measurable: with the knob set, four runs interleaved against a launch naming no
     * preset came back 51.45 against 51.41 fps on `Dreaming Sarah`. That title saturates nothing and
     * may barely reach x87 at all, so the figure bounds this rung rather than the knob.
     *
     * **Extreme and Performance are the same translation under this FEXCore.** Extreme is Performance
     * plus `SmallTSCScale` and `VolatileMetadata`, and both of those already default to on; the
     * second reads volatile metadata out of PE files, which a linux guest does not have. It is kept
     * as a distinct rung because it is distinct upstream and because a later FEXCore may move either
     * default, not because it measures differently today.
     */
    fun options(id: String): Map<String, String> = when (id) {
        // multiblock off: one guest block per translation. the slowest thing here and the one that
        // most changes what a fault looks like, which is why it leads the ladder.
        STABILITY -> tso(on = true, vector = true, memcpySet = true, halfBarrier = true) +
            mapOf("X87ReducedPrecision" to "0", "Multiblock" to "0")
        COMPATIBILITY -> tso(on = true, vector = true, memcpySet = true, halfBarrier = true) +
            mapOf("X87ReducedPrecision" to "0", "Multiblock" to "1")
        // **FEXCore's own defaults, expressed by setting nothing at all.** see the note on the
        // ladder above: this rung is the middle of the ladder and the untouched state at once.
        INTERMEDIATE -> emptyMap()
        // TSO emulation off entirely. FEX's text: highly likely to break any multithreaded
        // application. SharpEmu is a .NET process with a JIT, a GC and a render thread of its own.
        PERFORMANCE -> tso(on = false, vector = false, memcpySet = false, halfBarrier = false) +
            mapOf("X87ReducedPrecision" to "1", "Multiblock" to "1")
        EXTREME -> options(PERFORMANCE) +
            mapOf("SmallTSCScale" to "1", "VolatileMetadata" to "1")
        else -> emptyMap()
    }

    /**
     * The four TSO knobs, which are the ladder's real axis.
     *
     * **`on` is stated rather than derived from the other three.** With it off the other three are
     * moot — FEX only consults them while it is emulating x86's memory ordering at all — and reading
     * "all three off" as "TSO off" would be right for every rung here and wrong for the first one
     * added that wants x86 ordering with none of the three refinements.
     */
    private fun tso(on: Boolean, vector: Boolean, memcpySet: Boolean, halfBarrier: Boolean): Map<String, String> =
        linkedMapOf(
            "TSOEnabled" to bit(on),
            "VectorTSOEnabled" to bit(vector),
            "MemcpySetTSOEnabled" to bit(memcpySet),
            "HalfBarrierTSOEnabled" to bit(halfBarrier),
        )

    private fun bit(on: Boolean) = if (on) "1" else "0"

    /**
     * The preset as the host layer's own flags, ready to append to an argument vector.
     *
     * An id this build does not know contributes nothing, so a store written by a later version
     * launches on FEXCore's defaults rather than refusing to launch at all.
     */
    @JvmStatic
    fun arguments(id: String?): List<String> {
        val resolved = normalise(id) ?: return emptyList()
        val args = ArrayList<String>()
        for ((name, value) in options(resolved)) {
            if (name in REFUSED) continue
            args.add("--fex")
            args.add("$name=$value")
        }
        return args
    }

    /**
     * The stored spelling of an id, or null for one this build does not know.
     *
     * **Case-insensitive, and that is not politeness.** These ids are typed by hand into `am start`
     * and into a script's own parameter, and PowerShell's `ValidateSet` accepts any casing while
     * passing through what was typed — so `Intermediate` reaches the app as written. Matching it
     * exactly would drop it, fall back to the stored setting, and produce a run that silently was not
     * the one asked for. Every rung is lowercase ASCII, so the root locale is the right one and the
     * turkish dotless i cannot reach this.
     */
    @JvmStatic
    fun normalise(id: String?): String? {
        val lower = id?.lowercase(java.util.Locale.ROOT) ?: return null
        return if (ALL.contains(lower)) lower else null
    }
}
