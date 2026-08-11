package com.mircowuffwuff.sharpemu

import com.google.android.material.color.utilities.Hct

/**
 * Colours in the space Material's own generator thinks in.
 *
 * **HCT is hue, chroma and tone**, and it is not HSV under another name. Tone is perceptual
 * lightness — CIE L\* — so a tone of 60 looks equally light at every hue; chroma is colourfulness
 * measured perceptually rather than as a percentage of whatever the screen happens to allow. HSV's
 * value and saturation are neither: measured on four seeds picked at HSV value 0.75, the tone ranged
 * from **37 for a violet to 71 for a green**. "Locked 75% lightness" was not locking lightness.
 *
 * **Picking here rather than converting into here is the point.** `Hct.fromInt` is the first thing
 * that happens to a seed, so a picker with these axes is a picker with the generator's axes: nothing
 * is lost on the way in, and moving the same distance along an axis means the same thing wherever
 * you are.
 *
 * **The tone is read by the generator**, which is the easiest thing here to assume it is not.
 * `SchemeContent` is a *content* scheme: its accent tracks the seed's tone rather than sitting at a
 * fixed one. Measured, dark scheme, hue 18:
 *
 * | seed tone | accent | background chroma |
 * | --- | --- | --- |
 * | 40 | tone 80.0, chroma 30.0 | 4.1 |
 * | 60 | tone 79.9, chroma 29.6 | 3.4 |
 * | 80 | tone **93.2**, chroma **8.6** | 4.1 |
 *
 * **But it is clamped below about 60**, which is what makes two axes enough: any seed tone from 40 to
 * 60 produces an accent at tone 80, and only above that does the accent drift lighter and lose
 * chroma — which is never what a dark theme wants. So the tone is not a third choice worth a
 * control; it is the *container* that decides how much chroma a hue can hold, and it is picked here
 * to hold as much as possible while staying inside the clamped range.
 */
object HctColour {

    /** The colour at a point on the picker, at whichever tone that hue is picked at. */
    @JvmStatic
    fun colour(hue: Float, chroma: Float): Int =
        Hct.from(hue.toDouble(), chroma.toDouble(), tone(hue).toDouble()).toInt()

    /** A stored colour's hue, whatever tone it happens to have been saved at. */
    @JvmStatic
    fun hueOf(colour: Int): Float = Hct.fromInt(colour).hue.toFloat()

    /** A stored colour's chroma, likewise. */
    @JvmStatic
    fun chromaOf(colour: Int): Float = Hct.fromInt(colour).chroma.toFloat()

    /**
     * The most chroma this hue can actually reach at a tone.
     *
     * **The gamut of a screen is nothing like a cylinder**: at tone 60 a yellow reaches far more
     * chroma than a blue does, and asking for more than a hue can give returns the closest colour it
     * can. Found by bisection on what comes back rather than by a table — `Hct.from` gives the
     * nearest displayable colour, so a request that survives round-tripping was inside the gamut.
     */
    @JvmStatic
    fun maxChroma(hue: Float, tone: Float): Float {
        var low = 0f
        var high = LIMIT
        repeat(BISECTIONS) {
            val mid = (low + high) / 2f
            val got = Hct.from(hue.toDouble(), mid.toDouble(), tone.toDouble()).chroma
            if (got >= mid - TOLERANCE) low = mid else high = mid
        }
        return low
    }

    /**
     * The tone this hue is picked at: [Settings.CUSTOM_TONE], or lower where that is where the colour
     * is.
     *
     * **A single pinned tone starves the reds.** The chroma a hue can hold peaks at a different
     * lightness for each one: greens and blues peak near tone 60 and lose nothing, while reds, pinks
     * and violets peak well below it — so pinning every hue at 60 leaves those columns pale.
     *
     * **Capping the axis at the accent's own ceiling instead is worse than either**, which is the
     * non-obvious part. It does not make the *accent* duller, since the accent is already at its
     * ceiling and cannot go past it — but `SchemeContent` derives its **neutral** palettes from the
     * seed's chroma as well, so a seed capped from chroma 85 to 34 takes the red tint out of every
     * surface in the theme, and the complaint that reaches you is that the red is gone.
     *
     * So the tone slides down where sliding down buys chroma, and stays at 60 where it does not.
     * The lightness lock holds across most of the wheel and gives way exactly where keeping it would
     * cost the colour.
     */
    @JvmStatic
    fun tone(hue: Float): Float = sample(hue, tones)

    /** The most chroma this hue can hold at [tone] — what the picker's vertical axis is a fraction of. */
    @JvmStatic
    fun ceiling(hue: Float): Float = sample(hue, ceilings)

    /**
     * The most chroma an *accent* of this hue can hold, at [ACCENT_TONE].
     *
     * Everything above it is not wasted — it goes on tinting the surfaces — but the accent stops
     * changing there, which is the line the picker draws.
     */
    @JvmStatic
    fun accentCeiling(hue: Float): Float = sample(hue, accentCeilings)

    /**
     * The accent a seed of this hue and chroma produces.
     *
     * **This is what the picker paints**, so that choosing from the field is choosing the colour the
     * *Selected* line in the preview will be. It is the same arithmetic the generator does — the seed
     * clamped to what the accent's tone can hold — rather than a whole scheme built per pixel, which
     * at a few thousand pixels would not be a picker.
     */
    @JvmStatic
    fun accent(hue: Float, chroma: Float): Int = Hct.from(
        hue.toDouble(),
        minOf(chroma, accentCeiling(hue)).toDouble(),
        ACCENT_TONE.toDouble(),
    ).toInt()

    /**
     * The tone a dark scheme's primary lands on. **Measured rather than read off a specification**:
     * every accent generated while instrumenting the picker came back at 79.9 to 80.1. Nothing here
     * can raise a red accent past what that tone holds — the generator picks it, not this app — which
     * is why the answer was to give the *seed* its chroma back rather than to chase the accent.
     */
    const val ACCENT_TONE = 80f

    // ----------------------------------------------------------------------------------------------
    // the per-hue table
    //
    // **sampled every ten degrees and interpolated**, because the honest version is expensive: a tone
    // search is a bisection per candidate tone, and doing that per degree is tens of thousands of
    // CAM16 solves. the two curves are smooth in hue, so 37 samples and a lerp are indistinguishable
    // from the real thing and are built once for the life of the process.

    private const val SAMPLES = 37
    private const val STEP = 360f / (SAMPLES - 1)

    private val tones = FloatArray(SAMPLES)
    private val ceilings = FloatArray(SAMPLES)
    private val accentCeilings = FloatArray(SAMPLES)

    init {
        for (i in 0 until SAMPLES) {
            val hue = i * STEP
            var best = Settings.CUSTOM_TONE
            var most = maxChroma(hue, Settings.CUSTOM_TONE)
            var candidate = Settings.CUSTOM_TONE - TONE_STEP
            while (candidate >= MIN_TONE) {
                val chroma = maxChroma(hue, candidate)
                // strictly greater, so a hue that gains nothing keeps the lighter tone.
                if (chroma > most) {
                    most = chroma
                    best = candidate
                }
                candidate -= TONE_STEP
            }
            tones[i] = best
            ceilings[i] = most
            accentCeilings[i] = maxChroma(hue, ACCENT_TONE)
        }
    }

    private fun sample(hue: Float, table: FloatArray): Float {
        val position = (hue.coerceIn(0f, 360f) / STEP)
        val low = position.toInt().coerceIn(0, SAMPLES - 1)
        val high = (low + 1).coerceAtMost(SAMPLES - 1)
        val fraction = position - low
        return table[low] + (table[high] - table[low]) * fraction
    }

    /** How far the tone may fall. Below this a seed stops reading as the colour that was picked. */
    private const val MIN_TONE = 42f
    private const val TONE_STEP = 2f

    /** No hue reaches this at any tone, so it is a safe upper bound for the search. */
    private const val LIMIT = 150f
    private const val BISECTIONS = 12
    private const val TOLERANCE = 0.5f
}
