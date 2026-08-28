package com.mircowuffwuff.sharpdroid

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

    /**
     * the order the control lists them in, and the values the store holds.
     *
     * **nothing sits above [PERFORMANCE], and a rung that bundled the block-lookup knobs with the
     * unaligned repair would be three unrelated things under one word**: a speed setting, a memory
     * purchase, and a repair that can corrupt data. the speed in that combination is the lookup
     * knobs alone -- about 4.6% of the load interval, eight runs each -- and the repair does not
     * fire once at [PERFORMANCE] on these titles, so such a rung would be fast for a reason nobody
     * was warned about and dangerous for one that buys nothing here. every one of those knobs is a
     * row of its own, which is what leaves the ladder nothing to add.
     */
    val ALL = arrayOf(STABILITY, COMPATIBILITY, INTERMEDIATE, PERFORMANCE)

    /**
     * what the row shows before it has been touched, and what a launch naming no rung runs.
     *
     * **[INTERMEDIATE] is the rung whose nine values are what an untouched install already gets** --
     * FEXCore's own defaults for the first eight and the host layer's for the block lookup's shrink.
     * so choosing the middle of the ladder and never opening the screen at all are the same
     * translation, and the row cannot name one thing while the launch does another.
     *
     * **it is a rung like the other three and holds no special place in the code.** the values are
     * written out here rather than left to whatever FEXCore defaults to, which is what lets this
     * claim survive an upstream that moves one of them: the app passes all nine either way, so what
     * the rows show is what the run got.
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
     * **every rung names every knob, with no exceptions and nothing inherited from the rung below.**
     * a preset is a promise that two installs reading the same word run the same translation, and a
     * rung that left a knob unnamed would leave that knob free to differ between them -- which is
     * the whole of what a preset is for.
     *
     * **the values are this app's own constants and are not read from FEXCore.** a table here
     * mirroring FEX's defaults would be wrong the day upstream moved one, and wrong in the worst
     * way: the row would draw one state while the launch passed nothing and the JIT did another.
     * naming every value means the app never has to know what FEXCore would have done.
     *
     * **[INTERMEDIATE] is therefore a rung like any other**, and its nine values are what a launch
     * naming nothing produces today -- FEXCore's own defaults for the first eight, and the host
     * layer's for the block lookup's shrink, which it holds at 0.
     *
     * **Intermediate is one knob away from GameNative's**, deliberately. theirs sets
     * `X87ReducedPrecision`, emulating x87 at 64-bit rather than 80-bit precision -- FEX's own text on
     * it warns of rendering bugs. dropping it is what keeps this rung equal to an untouched run, and
     * it costs nothing measurable: with the knob set, four runs interleaved against a launch naming no
     * preset came back 51.45 against 51.41 fps on `Dreaming Sarah`. that title saturates nothing and
     * may barely reach x87 at all, so the figure bounds this rung rather than the knob.
     *
     * **no rung turns `HalfBarrierTSOEnabled` off, and every rung asks for it.** x86 permits an
     * unaligned access anywhere, including on the atomics FEX compiles guest memory accesses into,
     * and arm64's atomics require natural alignment -- so the JIT emits the aligned form and the
     * host layer backpatches the first time one faults. that option chooses what it is backpatched
     * *to*: a half-barrier atomic, which keeps the ordering the guest expects, or a plain load or
     * store, which does not.
     *
     * **the plain form is the one thing on this screen that can corrupt data rather than merely run
     * slowly**, so it is a row somebody sets deliberately rather than something a rung named for
     * speed turns off underneath them. a torn read needs another thread touching the same
     * misaligned address at the same moment, so it is a race and not a certainty, and a rung that
     * quietly dropped atomicity while advertising only a TSO change would be a rung nobody chose
     * the risk of.
     *
     * **and the knob is subordinate to TSO, which bounds what it can buy.** FEX's own text opens
     * "when TSO emulation is enabled" for a reason: with TSO off the JIT emits far fewer atomic
     * sequences, so far fewer faults reach the repair at all. measured on this workload the repair
     * fires at [COMPATIBILITY] and does not fire once at [PERFORMANCE] -- so on a title that
     * behaves like these two, turning it off is honoured and never reached.
     */
    fun options(id: String): Map<String, String> = when (id) {
        // multiblock off: one guest block per translation. the slowest thing here and the one that
        // most changes what a fault looks like, which is why it leads the ladder.
        STABILITY -> tso(on = true, vector = true, memcpySet = true, halfBarrier = true) +
            mapOf("X87ReducedPrecision" to "0", "Multiblock" to "0") + lookup()
        COMPATIBILITY -> tso(on = true, vector = true, memcpySet = true, halfBarrier = true) +
            mapOf("X87ReducedPrecision" to "0", "Multiblock" to "1") + lookup()
        INTERMEDIATE -> tso(on = true, vector = false, memcpySet = false, halfBarrier = true) +
            mapOf("X87ReducedPrecision" to "0", "Multiblock" to "1") + lookup()
        // TSO emulation off entirely. FEX's text: highly likely to break any multithreaded
        // application. SharpEmu is a .NET process with a JIT, a GC and a render thread of its own.
        PERFORMANCE -> tso(on = false, vector = false, memcpySet = false, halfBarrier = true) +
            mapOf("X87ReducedPrecision" to "1", "Multiblock" to "1") + lookup()
        else -> options(DEFAULT)
    }

    /**
     * the block lookup, which every rung sets the same way and none may set otherwise.
     *
     * **no rung shrinks the L1, and that is not a performance judgement.** a shrink strands every
     * translation cached above the new size, and a guest thread resumed through the emulator's
     * continuation trampoline then runs the previous translation of it -- which stops the audio
     * permanently and can freeze the picture. a rung offering that from a control labelled for speed
     * would be a rung that reintroduces a known fault, so the value is the same on all four and the
     * row is where somebody asks for it deliberately.
     *
     * the other two are FEXCore's own: the L1 sized to fit rather than pinned at its maximum, and
     * the second-level lookup skipped. both trade a stutter for a great deal of memory, and the
     * ladder's axis is faithfulness rather than footprint -- so they are rows and no rung moves them.
     */
    private fun lookup(): Map<String, String> = linkedMapOf(
        "DynamicL1Cache" to "1",
        "DynamicL1CacheDecreaseCountHeuristic" to "0",
        "DisableL2Cache" to "1",
    )

    /**
     * one FEXCore option offered as a row, and the two values its switch stands for.
     *
     * **a switch over two values rather than a number field, and that is right even for the one
     * option here that is not a boolean.** the shrink threshold is a rate, and the only value of it
     * anybody here wants is the one that puts its branch out of reach; every other number is still
     * expressible, because the host layer takes `--fex Name=Value` and this app is not the only way
     * to reach it.
     *
     * **[on] and [off] are the option's values and not the switch's sense.** `DisableL2Cache` is
     * stated backwards -- true *disables* the lookup -- so a row titled for the lookup itself is on
     * at "0", and a row that inverted the title instead would be a double negative on screen.
     */
    data class Knob(
        val option: String,
        val title: Int,
        val summary: Int,
        val on: String,
        val off: String,
    ) {
        /** the switch's position for a value a rung, an override or [unset] settled on. */
        fun checked(value: String) = value == on

        /** the value this switch stores in the position it has been put in. */
        fun value(checked: Boolean) = if (checked) on else off
    }

    /**
     * how faithfully x86's memory ordering is reproduced, which is the ladder's own axis.
     *
     * every one of these is spanned by a rung, so every one of them can make the reading say
     * Custom.
     */
    val ORDERING = listOf(
        Knob("TSOEnabled", R.string.setting_fex_tso, R.string.setting_fex_tso_summary, "1", "0"),
        Knob("VectorTSOEnabled", R.string.setting_fex_vector_tso,
             R.string.setting_fex_vector_tso_summary, "1", "0"),
        Knob("MemcpySetTSOEnabled", R.string.setting_fex_memcpy_tso,
             R.string.setting_fex_memcpy_tso_summary, "1", "0"),
        Knob("HalfBarrierTSOEnabled", R.string.setting_fex_half_barrier,
             R.string.setting_fex_half_barrier_summary, "1", "0"),
    )

    /** what the JIT is allowed to do while translating a block. */
    val CODEGEN = listOf(
        Knob("Multiblock", R.string.setting_fex_multiblock,
             R.string.setting_fex_multiblock_summary, "1", "0"),
        Knob("X87ReducedPrecision", R.string.setting_fex_x87,
             R.string.setting_fex_x87_summary, "1", "0"),
    )

    /**
     * how a translation that already exists is found again.
     *
     * **every rung sets these the same way** -- see [lookup] for why the shrink in particular is not
     * a rung's to offer -- so a row here reads Custom the moment it is touched, exactly like the
     * rows above it. what makes them worth a group of their own is that they trade memory rather
     * than faithfulness, which is not the axis the ladder runs along.
     *
     * **the shrink is a threshold and its switch is over 50 and 0**, FEXCore's own value and the one
     * that makes the shrink branch unreachable: the comparison is a rate against this number, and a
     * rate cannot fall below zero. turning it on is asking for a fault that stops the audio
     * permanently, and the row's own summary says so.
     *
     * the adaptive row leads because the one under it does nothing while it is off: nothing is
     * resized, so nothing shrinks.
     */
    val LOOKUP = listOf(
        Knob("DynamicL1Cache", R.string.setting_fex_l1_dynamic,
             R.string.setting_fex_l1_dynamic_summary, "1", "0"),
        Knob("DynamicL1CacheDecreaseCountHeuristic", R.string.setting_fex_l1_shrink,
             R.string.setting_fex_l1_shrink_summary, "50", "0"),
        Knob("DisableL2Cache", R.string.setting_fex_l2, R.string.setting_fex_l2_summary, "0", "1"),
    )

    /** every knob the app offers, whatever group it is drawn under. */
    val KNOBS = ORDERING + CODEGEN + LOOKUP

    /**
     * the value a launch settles on for one knob, given a rung and the overrides in force.
     *
     * the order is the argument vector's: the rung is emitted first and an override after it, and
     * the host layer keeps the last assignment to a name. **there is no third fallback**, because
     * every rung names every knob -- an option missing from a rung would be a row this app draws and
     * cannot answer for.
     */
    fun resolve(option: String, preset: String?, overrides: Map<String, String>): String =
        overrides[option] ?: options(normalise(preset) ?: DEFAULT).getValue(option)

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
     * **a launch carries a complete JIT configuration, always.** every knob this app draws is named
     * on the command line, so what a row shows is what the run got -- rather than a row describing a
     * value the app declined to pass and left to whatever FEXCore happens to default to this year.
     *
     * **an id this build does not know is the default rung** rather than nothing, which is the same
     * answer the row itself gives: a store written by a later version launches somewhere named
     * instead of somewhere nobody chose.
     */
    @JvmStatic
    fun arguments(id: String?): List<String> {
        val resolved = normalise(id) ?: DEFAULT
        val args = ArrayList<String>()
        for ((name, value) in options(resolved)) {
            if (name in REFUSED || name in UNREACHABLE) continue
            args.add("--fex")
            args.add("$name=$value")
        }
        return args
    }

    /**
     * the stored knob overrides as the host layer's own flags, to append **after** [arguments].
     *
     * **the order is the whole of what makes an override an override.** the host layer applies
     * `--fex` in the order it is given and keeps the last assignment to a name, so a knob emitted
     * after a rung replaces what the rung said about it -- and one emitted before would be replaced
     * by it, silently, on exactly the rows somebody went to a screen to change.
     *
     * **a name this build does not offer contributes nothing**, because a store can be written by a
     * later version of this app and restored into this one, and a launch that refused an option it
     * could not resolve would be a game that no longer starts.
     */
    @JvmStatic
    fun overrideArguments(overrides: Map<String, String>): List<String> {
        val offered = KNOBS.associateBy { it.option }
        val args = ArrayList<String>()
        for ((name, value) in overrides) {
            if (name !in offered) continue
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
