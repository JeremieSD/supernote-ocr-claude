package dev.snocr.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Shows a page and lets the user drag a rectangle over it to pick the region a
 * question is about. The selection is reported normalized (0..1) relative to the
 * drawn image, so it can be applied to a higher-resolution render later.
 *
 * A tap or a tiny drag clears the selection.
 */
class RegionSelectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val dest = RectF()      // where the bitmap is drawn (fit-center), in view px
    private var sel: RectF? = null  // current selection in view px, clamped to dest

    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    /** Invoked on selection change with the normalized rect, or null when cleared. */
    var onRegionChanged: ((RectF?) -> Unit)? = null

    private val fillPaint = Paint().apply { color = Color.WHITE }
    private val dimPaint = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp
        computeDest()
        invalidate()
    }

    /** Restores a previously chosen region (normalized to the image), or clears it. */
    fun setNormalizedRect(r: RectF?) {
        sel = if (r == null || dest.isEmpty) null else RectF(
            dest.left + r.left * dest.width(),
            dest.top + r.top * dest.height(),
            dest.left + r.right * dest.width(),
            dest.top + r.bottom * dest.height(),
        )
        invalidate()
    }

    /** The current selection normalized to the image content, or null if none. */
    fun normalizedRect(): RectF? {
        val s = sel ?: return null
        if (dest.isEmpty) return null
        return RectF(
            ((s.left - dest.left) / dest.width()).coerceIn(0f, 1f),
            ((s.top - dest.top) / dest.height()).coerceIn(0f, 1f),
            ((s.right - dest.left) / dest.width()).coerceIn(0f, 1f),
            ((s.bottom - dest.top) / dest.height()).coerceIn(0f, 1f),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val previous = normalizedRect()
        computeDest()
        setNormalizedRect(previous)
    }

    private fun computeDest() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        dest.set(left, top, left + w, top + h)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        if (dest.isEmpty) computeDest()
        canvas.drawRect(dest, fillPaint)
        canvas.drawBitmap(bmp, null, dest, null)
        val s = sel ?: return
        // Dim the image outside the selection so the chosen area stands out.
        canvas.save()
        canvas.clipRect(dest)
        canvas.clipOutRect(s)
        canvas.drawRect(dest, dimPaint)
        canvas.restore()
        canvas.drawRect(s, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dest.isEmpty) return false
        val x = event.x.coerceIn(dest.left, dest.right)
        val y = event.y.coerceIn(dest.top, dest.bottom)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = x
                startY = y
                dragging = true
                sel = RectF(x, y, x, y)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                sel = RectF(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val s = sel
                if (s != null && (s.width() < MIN_PX || s.height() < MIN_PX)) {
                    sel = null // a tap or tiny drag clears the selection
                }
                onRegionChanged?.invoke(normalizedRect())
                invalidate()
            }
            else -> return false
        }
        return true
    }

    companion object {
        private const val MIN_PX = 24f
    }
}
