package dev.snocr.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A simple pen/finger drawing surface for writing a question by hand. Strokes
 * are black on white; export produces a white-background bitmap. Kept
 * deliberately minimal for low latency on e-ink (no anti-alias smoothing pass,
 * incremental invalidation of the dirty region only).
 */
class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class Stroke(val path: Path, val width: Float)

    private val strokes = ArrayList<Stroke>()
    private var current: Stroke? = null
    private var lastX = 0f
    private var lastY = 0f

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /** Stroke width in px; a bit heavier helps the model read the handwriting. */
    var strokeWidth: Float = 6f

    var onStrokeCountChanged: (() -> Unit)? = null

    val hasContent: Boolean get() = strokes.isNotEmpty() || current != null

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        for (stroke in strokes) {
            strokePaint.strokeWidth = stroke.width
            canvas.drawPath(stroke.path, strokePaint)
        }
        current?.let {
            strokePaint.strokeWidth = it.width
            canvas.drawPath(it.path, strokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val path = Path().apply { moveTo(x, y) }
                current = Stroke(path, strokeWidth)
                lastX = x
                lastY = y
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = current ?: return true
                // Quadratic smoothing between the last point and the midpoint.
                stroke.path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                lastX = x
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val stroke = current
                if (stroke != null) {
                    stroke.path.lineTo(x, y)
                    strokes.add(stroke)
                    current = null
                    onStrokeCountChanged?.invoke()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
            else -> return false
        }
        return true
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.size - 1)
            onStrokeCountChanged?.invoke()
            invalidate()
        }
    }

    fun clear() {
        if (hasContent) {
            strokes.clear()
            current = null
            onStrokeCountChanged?.invoke()
            invalidate()
        }
    }

    /**
     * Renders the strokes to a white-background bitmap at the view's current
     * size, or null if nothing has been drawn or the view is unmeasured.
     */
    fun exportBitmap(): Bitmap? {
        if (!hasContent || width == 0 || height == 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        for (stroke in strokes) {
            strokePaint.strokeWidth = stroke.width
            canvas.drawPath(stroke.path, strokePaint)
        }
        return bitmap
    }
}
