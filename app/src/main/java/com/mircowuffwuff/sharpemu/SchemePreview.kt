package com.mircowuffwuff.sharpemu

import android.graphics.Color
import android.util.Log
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeContent

/**
 * What a seed colour would generate, computed without applying it.
 *
 * **This is the honest answer to "show me the theme while I pick it".** A Material scheme is resolved
 * while a view hierarchy is inflated, so the screens *around* the picker cannot repaint as a slider
 * moves — repainting them means recreating the activity, which would close the dialog doing the
 * picking. What can be live is the picker's own preview, and it is only worth having if it shows the
 * colours that will actually arrive.
 *
 * So it asks the same generator: [SchemeContent] is what `setContentBasedSource` builds, and
 * [MaterialDynamicColors] is what reads roles out of it. The preview is therefore not an
 * approximation of the theme — it is the theme, drawn small.
 *
 * **It is wrapped in a `runCatching` and that is deliberate.** These classes are Material's own
 * colour-utilities port; they are shipped in the library this app already depends on, but they are
 * not part of its stable public surface, so a version bump could move them. A preview that cannot be
 * computed falls back to the seed itself — the picker still works, and nothing crashes on a screen
 * whose whole job is choosing a colour.
 */
data class SchemePreview(
    val background: Int,
    val surfaceContainer: Int,
    val outline: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
) {
    companion object {

        /** The dark scheme a seed generates, or a plain fallback built from the seed itself. */
        @JvmStatic
        fun of(seed: Int): SchemePreview = runCatching {
            val scheme = SchemeContent(Hct.fromInt(seed), true, 0.0)
            val roles = MaterialDynamicColors()
            SchemePreview(
                background = roles.background().getArgb(scheme),
                surfaceContainer = roles.surfaceContainer().getArgb(scheme),
                outline = roles.outlineVariant().getArgb(scheme),
                onSurface = roles.onSurface().getArgb(scheme),
                onSurfaceVariant = roles.onSurfaceVariant().getArgb(scheme),
                primary = roles.primary().getArgb(scheme),
            )
        }.getOrElse {
            Log.w(TAG, "[app] could not generate a scheme preview from the seed", it)
            SchemePreview(
                background = Color.BLACK,
                surfaceContainer = Color.DKGRAY,
                outline = Color.GRAY,
                onSurface = Color.WHITE,
                onSurfaceVariant = Color.LTGRAY,
                primary = seed,
            )
        }

        private const val TAG = "sharpemu"
    }
}
