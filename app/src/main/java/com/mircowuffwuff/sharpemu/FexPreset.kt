package com.mircowuffwuff.sharpemu

/**
 * the FEXCore JIT presets, as a named ladder from most correct to fastest.
 *
 * **the knobs are FEX's, the ladder is GameNative's, and the route is this project's own.**
 * GameNative reaches FEX's options by exporting `FEX_TSOENABLED` and friends into the environment
 * of a FEX process it launches. that route does not exist here and cannot be made to: those
 * variables are read by `EnvLoader` in FEX's frontend, and a process that hosts FEXCore as a
 * library does not build the frontend. so a variable by that name reaches nothing, silently, which
 * is exactly the shape of a setting that looks like it works. the host layer takes
 * `--fex Name=Value` instead and sets the option directly, refusing a name FEXCore does not have.
 *
 * **the names below are FEXCore's json spellings**, which is what `--fex` resolves against. they
 * are not the environment spellings with the prefix removed, even where the two happen to look
 * alike.
 *
 * **a preset is a host-layer argument and never a guest environment variable.** it is deliberately
 * absent from [Settings.guestEnvironment], because that map merges a build's own `env` underneath
 * the user's choices -- and a JIT knob is a property of the host layer's correctness rather than a
 * preference a payload may express, the same rule `--smc` and `--asyncsig` already follow. a build
 * able to ask for [PERFORMANCE] would be a build able to break every launch of itself.
 */
object FexPreset {

    const val STABILITY = "stability"
    const val COMPATIBILITY = "compatibility"
    const val INTERMEDIATE = "intermediate"
    const val PERFORMANCE = "performance"
    const val EXTREME = "extreme"

    /** the order the slider runs in, and the values the store holds. */
    val ALL = arrayOf(STABILITY, COMPATIBILITY, INTERMEDIATE, PERFORMANCE, EXTREME)

    /**
     * what the row shows before it has been touched -- and, uniquely, a rung that produces exactly
     * what an untouched row produces.
     *
     * **[INTERMEDIATE] is FEXCore's own defaults, and it sets nothing to get there.** that is what
     * makes this row honest rather than merely plausible: choosing the middle of the ladder and
     * never opening the screen at all are the same translation, so the row cannot name one thing
     * while the launch does another. it is also the only way a user can ask for FEXCore's defaults
     * from the control itself: this row is a slider, and every position the thumb can land on is a
     * rung -- none of them means *unset*, which is reachable only by the long press that clears a row.
     *
     * **Compatibility would have been the obvious choice and is the wrong one.** `VectorTSOEnabled`
     * and `MemcpySetTSOEnabled` both default to off and Compatibility turns both on, asking for
     * atomic vector loadstores and atomic `REP MOVS`/`REP STOS` that a default run does not emit.
     * measured on `Dreaming Sarah`, interleaved: 38.8 fps against 51.4. the name reads like the safe
     * middle and is the second most expensive rung on the ladder.
     */
    const val DEFAULT = INTERMEDIATE

    /**
     * Denuvo is not among them. GameNative carries a sixth preset by that name, tuned for a Windows
     * DRM that pairs self-modifying code with hypervisor detection -- `SMCChecks=full` and
     * `HideHypervisorBit`. neither has anything to answer on a PS5 title, and `SMCChecks` in
     * particular is not this ladder's to set: the host layer's VMA tracker has to agree with it, so
     * it belongs to `--smc` and is refused here.
     */
    private val REFUSED = setOf("SMCChecks", "IS64BIT_MODE", "GDBSERVER")

    /**
     * knobs that are absent from every rung because nothing here reads them.
     *
     * **a FEXCore option being declared is not the same as it being consumed.** `--fex` resolves a
     * name against the generated option table and refuses one FEXCore does not declare, which
     * catches a typo but not this: several options in that table are read only by FEX's own
     * frontend -- `LinuxEmulation`, `FEXInterpreter`, the Windows sources -- and a process that hosts
     * FEXCore as a library builds none of it. so they would be accepted, logged, and reach nothing,
     * which is the same failure as the `FEX_` environment spelling above and harder to see.
     *
     * `VolatileMetadata` reads volatile metadata out of PE files, and is consumed in the Windows
     * image tracker. `MonoHacks` gates on a flag that only the Windows invalidation tracker ever
     * sets, so it can never become active. `KernelUnalignedAtomicBackpatching` is a `prctl` the
     * frontend makes. `HostFeatures` is answered by the host layer building its own, so the config
     * option is not what decides.
     *
     * **`HalfBarrierTSOEnabled` is the exception and is on the ladder**, though FEX reads it in its
     * frontend too: the host layer handles unaligned accesses itself and reads that option to
     * choose how, so here it is honoured rather than inert.
     */
    private val UNREACHABLE = setOf("VolatileMetadata", "MonoHacks",
                                    "KernelUnalignedAtomicBackpatching", "HostFeatures")

    /**
     * what a preset sets, as FEXCore option names.
     *
     * **every rung names every knob**, rather than expressing itself as a change from the rung below
     * it. a preset that inherited would describe the ladder instead of the translation, and the
     * question a reader has here is what one setting does -- not what it does differently.
     * [INTERMEDIATE] is the exception and names none of them, because FEXCore's defaults are what it
     * is: writing them out would be six values to keep in step with an upstream that owns them.
     *
     * **Intermediate is one knob away from GameNative's**, deliberately. theirs sets
     * `X87ReducedPrecision`, emulating x87 at 64-bit rather than 80-bit precision -- FEX's own text on
     * it warns of rendering bugs. dropping it is what makes this rung equal to the defaults, and it
     * costs nothing measurable: with the knob set, four runs interleaved against a launch naming no
     * preset came back 51.45 against 51.41 fps on `Dreaming Sarah`. that title saturates nothing and
     * may barely reach x87 at all, so the figure bounds this rung rather than the knob.
     *
     * **Extreme is the only rung that changes how an unaligned access is repaired**, and that is the
     * whole of what separates it from [PERFORMANCE]. x86 permits an unaligned access anywhere,
     * including on the atomics FEX compiles guest memory accesses into, and arm64's atomics require
     * natural alignment -- so the JIT emits the aligned form and the host layer backpatches the first
     * time one faults. `HalfBarrierTSOEnabled` chooses what it is backpatched *to*: a half-barrier
     * atomic, which keeps the ordering the guest expects, or a plain load or store, which does not.
     *
     * **Extreme is the plain one, and it is the only rung on this ladder that can corrupt data
     * rather than merely run slowly.** a torn read needs another thread to be touching the same
     * misaligned address at the same moment, so it is a race and not a certainty -- which is exactly
     * why it belongs on the rung named for it rather than one below.
     *
     * **[PERFORMANCE] therefore asks for the half-barrier, though it turns TSO off.** a rung that
     * quietly dropped atomicity while advertising only a TSO change would be a rung nobody chose the
     * risk of.
     *
     * **and the knob is subordinate to TSO, which bounds what Extreme buys.** FEX's own text opens
     * "when TSO emulation is enabled" for a reason: with TSO off the JIT emits far fewer atomic
     * sequences, so far fewer faults reach the repair at all. measured on this workload the repair
     * fires at [COMPATIBILITY] and does not fire once at [PERFORMANCE] -- so on a title that behaves
     * like these two, Extreme's setting of it is honoured and never reached. it is set because the
     * rung that is allowed to be dangerous should be, and because a title using a locked unaligned
     * access would reach it; it is not what makes Extreme faster.
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
        PERFORMANCE -> tso(on = false, vector = false, memcpySet = false, halfBarrier = true) +
            mapOf("X87ReducedPrecision" to "1", "Multiblock" to "1")
        // **the rung that spends memory.** both of these default to the lean side and say so in
        // FEX's own text -- "saving memory", "can potentially introduce more stutters" -- so turning
        // them off is asking for the block lookup to be as fast as it can be and paying in
        // footprint: the L1 stops being resized to fit and sits at its maximum, which is 16 MB per
        // guest thread rather than 128 KB, and the L2 lookup is consulted instead of skipped.
        //
        // **on a handheld that ceiling is the real limit of this rung**, and it is a limit no knob
        // above announces: nothing refuses, the process simply has more to lose to the low-memory
        // killer the longer it runs.
        EXTREME -> options(PERFORMANCE) +
            mapOf("HalfBarrierTSOEnabled" to "0", "DisableL2Cache" to "0", "DynamicL1Cache" to "0")
        else -> emptyMap()
    }

    /**
     * the four TSO knobs, which are the ladder's real axis.
     *
     * **`on` is stated rather than derived from the other three.** with it off the other three are
     * moot -- FEX only consults them while it is emulating x86's memory ordering at all -- and reading
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
     * the preset as the host layer's own flags, ready to append to an argument vector.
     *
     * an id this build does not know contributes nothing, so a store written by a later version
     * launches on FEXCore's defaults rather than refusing to launch at all.
     */
    @JvmStatic
    fun arguments(id: String?): List<String> {
        val resolved = normalise(id) ?: return emptyList()
        val args = ArrayList<String>()
        for ((name, value) in options(resolved)) {
            if (name in REFUSED || name in UNREACHABLE) continue
            args.add("--fex")
            args.add("$name=$value")
        }
        return args
    }

    /**
     * the stored spelling of an id, or null for one this build does not know.
     *
     * **case-insensitive, and that is not politeness.** these ids are typed by hand into `am start`
     * and into a script's own argument, and what reaches the app is whatever was typed -- so
     * `Intermediate` arrives capitalised. matching exactly would drop it, fall back to the stored
     * setting, and produce a run that silently was not the one asked for. every rung is lowercase ASCII, so the root locale is the right one and the
     * turkish dotless i cannot reach this.
     */
    @JvmStatic
    fun normalise(id: String?): String? {
        val lower = id?.lowercase(java.util.Locale.ROOT) ?: return null
        return if (ALL.contains(lower)) lower else null
    }
}
