package com.mircowuffwuff.sharpdroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * the whole colour picker: hue across, chroma down, at a pinned tone.
 *
 * **these are the generator's own axes rather than a conversion into them.** a seed's first stop is
 * `Hct.fromInt`, so picking in hue and chroma is picking the two numbers `SchemeContent` actually
 * reads -- see [HctColour]. hue and *saturation* would look the same and would not be: HSV saturation
 * is a fraction of whatever the screen can show at that hue, so one height in the field means a
 * different colourfulness in every column.
 *
 * **the field is painted in accents rather than in seeds**, so choosing from it is choosing the
 * colour the *Selected* line in the preview will be. the position still means chroma -- which is what
 * tints the surfaces -- but what is drawn is [HctColour.accent], the seed clamped to what the accent's
 * tone can hold.
 *
 * **above a certain chroma the accent stops changing and the surfaces do not**, and nothing in the
 * field marks where: the top of a red column tints the backgrounds without moving the accent, and
 * the preview is the only place that is visible. a dashed line across each column would say so, at
 * the cost of a rule drawn over a colour field.
 *
 * the field is drawn from a small bitmap built once per size: each pixel is a CAM16 solve, which is
 * cheap in the thousands and not in the hundreds of thousands. scaling it up is what a smooth
 * gradient is anyway.
 */
class HueChromaField @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val rect = RectF()
    private val source = Rect()
    private var field: Bitmap? = null

    /** called whenever a touch moves the knob. never called for a programmatic change. */
    var onPicked: ((hue: Float, chromaFraction: Float) -> Unit)? = null

    var hue: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 360f)
            invalidate()
        }

    /** how colourful, as a fraction of the most this hue can be. */
    var chromaFraction: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** the chroma that fraction stands for right now. */
    fun chroma(): Float = HctColour.ceiling(hue) * chromaFraction

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val began = SystemClock.elapsedRealtime()
        val bitmap = Bitmap.createBitmap(COLUMNS, ROWS, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(COLUMNS * ROWS)
        for (x in 0 until COLUMNS) {
            val hue = x / (COLUMNS - 1f) * 360f
            // once per column rather than once per pixel: the bisection is the expensive half.
            val ceiling = HctColour.ceiling(hue)
            for (y in 0 until ROWS) {
                val fraction = 1f - y / (ROWS - 1f)
                pixels[y * COLUMNS + x] = HctColour.accent(hue, ceiling * fraction)
            }
        }
        bitmap.setPixels(pixels, 0, COLUMNS, 0, 0, COLUMNS, ROWS)
        field = bitmap
        source.set(0, 0, COLUMNS, ROWS)
        // said out loud because it is the one part of this screen that could be slow, and a number
        // in a log is what a claim about it should rest on.
        Log.i(TAG, "[app] colour field: " + COLUMNS + "x" + ROWS + " in "
            + (SystemClock.elapsedRealtime() - began) + " ms")
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = field ?: return
        rect.set(inset, inset, width - inset, height - inset)
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(bitmap, source, rect, paint)
        canvas.restore()
        drawKnob(
            canvas,
            rect.left + hue / 360f * rect.width(),
            rect.top + (1f - chromaFraction) * rect.height(),
        )
    }

    /** a white ring with a dark halo, so the knob is visible on any colour underneath it. */
    private fun drawKnob(canvas: Canvas, x: Float, y: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = Color.BLACK
        paint.alpha = 90
        paint.strokeWidth = 6f
        canvas.drawCircle(x, y, KNOB, paint)
        paint.color = Color.WHITE
        paint.alpha = 255
        paint.strokeWidth = 4f
        canvas.drawCircle(x, y, KNOB, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // the gesture continues outside the view, which is what a picker should do: a finger
                // dragged past the edge means "as colourful as it goes", not "cancelled".
                parent?.requestDisallowInterceptTouchEvent(true)
                val across = (width - 2 * inset).coerceAtLeast(1f)
                val down = (height - 2 * inset).coerceAtLeast(1f)
                hue = (event.x - inset) / across * 360f
                chromaFraction = 1f - (event.y - inset) / down
                onPicked?.invoke(hue, chromaFraction)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    /** the preview panel's corner radius, so the two halves of the dialog match. */
    private val radius = 16f * resources.displayMetrics.density

    /**
     * how far inside its slot the field is drawn.
     *
     * **the view keeps the whole slot and paints a little inside it**, which is what "smaller but
     * anchored" means: shrinking the layout would move it, since it is centred by the row it sits in.
     * the touch mapping uses the same inset, so the edges of the drawing are still the edges of the
     * range.
     */
    private val inset = 6f

    private companion object {
        const val TAG = "sharpdroid"
        const val KNOB = 22f
        // small on purpose: every pixel is a CAM16 solve and the result is a smooth gradient, which
        // is exactly the thing that survives being scaled up.
        const val COLUMNS = 96
        const val ROWS = 48
    }
}
