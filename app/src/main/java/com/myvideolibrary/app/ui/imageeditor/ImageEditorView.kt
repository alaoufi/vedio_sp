package com.myvideolibrary.app.ui.imageeditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Editing surface for a single image. Holds a mutable working bitmap plus text
 * overlays, and a selection rectangle (in bitmap coordinates) that the crop /
 * hide / OCR actions operate on. Drawing is done in bitmap space via a
 * fit-center matrix so overlays bake into the saved image at full resolution.
 */
class ImageEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** A movable text label positioned in bitmap coordinates. */
    class TextItem(var text: String, var x: Float, var y: Float, var size: Float, var color: Int)

    private var bmp: Bitmap? = null
    private val texts = mutableListOf<TextItem>()
    private var selection: RectF? = null

    private val matrix = Matrix()
    private val baseMatrix = Matrix()
    private val inverse = Matrix()
    private var scale = 1f

    // Pinch-to-zoom on top of the fit-center base (two-finger gesture).
    private var viewScale = 1f
    private var viewTransX = 0f
    private var viewTransY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val scaleDetector = android.view.ScaleGestureDetector(
        context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean {
                lastFocusX = d.focusX; lastFocusY = d.focusY; return true
            }
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                viewScale = (viewScale * d.scaleFactor).coerceIn(1f, 5f)
                viewTransX += d.focusX - lastFocusX
                viewTransY += d.focusY - lastFocusY
                lastFocusX = d.focusX; lastFocusY = d.focusY
                if (viewScale <= 1.01f) { viewScale = 1f; viewTransX = 0f; viewTransY = 0f }
                updateMatrix()
                return true
            }
        }
    )

    private var draggingText: TextItem? = null
    private var selStartX = 0f
    private var selStartY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var mode = Mode.SELECT

    enum class Mode { SELECT, MOVE_TEXT }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.CYAN
    }

    fun setImage(bitmap: Bitmap) {
        bmp = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        selection = null
        texts.clear()
        requestLayout()
        recomputeMatrix()
        invalidate()
    }

    /** The current working bitmap with text overlays baked in (for saving). */
    fun exportBitmap(): Bitmap? {
        val base = bmp ?: return null
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        drawTexts(canvas)
        return out
    }

    fun addText(text: String, color: Int) {
        val b = bmp ?: return
        val size = max(24f, b.width / 16f)
        val sel = selection
        val cx = sel?.centerX() ?: (b.width / 2f)
        val cy = sel?.centerY() ?: (b.height / 2f)
        val item = TextItem(text, cx, cy, size, color)
        texts.add(item)
        draggingText = item
        mode = Mode.MOVE_TEXT
        invalidate()
    }

    /** Crops the working bitmap to the selection; returns false if none/too small. */
    fun applyCrop(): Boolean {
        val b = bmp ?: return false
        val r = bitmapSelection() ?: return false
        if (r.width() < 8 || r.height() < 8) return false
        val cropped = Bitmap.createBitmap(
            b, r.left.toInt(), r.top.toInt(), r.width().toInt(), r.height().toInt()
        )
        bmp = cropped.copy(Bitmap.Config.ARGB_8888, true)
        texts.clear()
        selection = null
        recomputeMatrix()
        invalidate()
        return true
    }

    /**
     * Fully hides the selected region with a solid fill of its average colour —
     * no ghost/remnant of the text is left, and it blends with the surroundings.
     */
    fun applyHide(): Boolean {
        val b = bmp ?: return false
        val r = bitmapSelection() ?: return false
        val w = r.width().toInt()
        val h = r.height().toInt()
        if (w < 4 || h < 4) return false
        val sub = Bitmap.createBitmap(b, r.left.toInt(), r.top.toInt(), w, h)
        val onePx = Bitmap.createScaledBitmap(sub, 1, 1, true)
        val avg = onePx.getPixel(0, 0)
        val fill = Paint().apply { color = avg; style = Paint.Style.FILL }
        Canvas(b).drawRect(r, fill)
        selection = null
        invalidate()
        return true
    }

    /** The pixels inside the current selection (for OCR), or the whole image. */
    fun selectionOrWholeBitmap(): Bitmap? {
        val b = bmp ?: return null
        val r = bitmapSelection() ?: return b
        val w = r.width().toInt()
        val h = r.height().toInt()
        if (w < 8 || h < 8) return b
        return Bitmap.createBitmap(b, r.left.toInt(), r.top.toInt(), w, h)
    }

    fun hasSelection(): Boolean = bitmapSelection() != null

    // ---- Rendering ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeMatrix()
    }

    private fun recomputeMatrix() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        val dx = (width - b.width * scale) / 2f
        val dy = (height - b.height * scale) / 2f
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)
        updateMatrix()
    }

    /** Combines the fit-center base with the current pinch zoom/pan. */
    private fun updateMatrix() {
        val maxTx = width * (viewScale - 1f) / 2f
        val maxTy = height * (viewScale - 1f) / 2f
        viewTransX = viewTransX.coerceIn(-maxTx, maxTx)
        viewTransY = viewTransY.coerceIn(-maxTy, maxTy)
        matrix.set(baseMatrix)
        matrix.postScale(viewScale, viewScale, width / 2f, height / 2f)
        matrix.postTranslate(viewTransX, viewTransY)
        matrix.invert(inverse)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bmp ?: return
        canvas.save()
        canvas.concat(matrix)
        canvas.drawBitmap(b, 0f, 0f, null)
        drawTexts(canvas)
        selection?.let {
            selPaint.strokeWidth = 3f / scale
            selPaint.pathEffect = DashPathEffect(floatArrayOf(12f / scale, 8f / scale), 0f)
            canvas.drawRect(it, selPaint)
        }
        canvas.restore()
    }

    private fun drawTexts(canvas: Canvas) {
        for (t in texts) {
            textPaint.textSize = t.size
            textPaint.color = t.color
            textPaint.setShadowLayer(t.size / 12f, 0f, 0f, Color.BLACK)
            canvas.drawText(t.text, t.x, t.y, textPaint)
        }
    }

    // ---- Touch ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Two-finger gestures pinch-zoom/pan; one finger selects or moves text.
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress || event.pointerCount > 1) {
            draggingText = null
            return true
        }
        val p = floatArrayOf(event.x, event.y)
        inverse.mapPoints(p)
        val bx = p[0]
        val by = p[1]
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = textAt(bx, by)
                if (hit != null) {
                    draggingText = hit
                    mode = Mode.MOVE_TEXT
                } else {
                    mode = Mode.SELECT
                    selStartX = bx
                    selStartY = by
                    selection = RectF(bx, by, bx, by)
                }
                lastX = bx
                lastY = by
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.MOVE_TEXT) {
                    draggingText?.let {
                        it.x += bx - lastX
                        it.y += by - lastY
                    }
                } else {
                    selection = RectF(
                        min(selStartX, bx), min(selStartY, by),
                        max(selStartX, bx), max(selStartY, by)
                    )
                }
                lastX = bx
                lastY = by
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                draggingText = null
                invalidate()
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun textAt(bx: Float, by: Float): TextItem? {
        val bounds = android.graphics.Rect()
        for (t in texts.asReversed()) {
            textPaint.textSize = t.size
            textPaint.getTextBounds(t.text, 0, t.text.length, bounds)
            val left = t.x
            val top = t.y + bounds.top
            val right = t.x + bounds.width()
            val bottom = t.y + bounds.bottom
            if (bx in left..right && by in top..bottom) return t
        }
        return null
    }

    private fun bitmapSelection(): RectF? {
        val b = bmp ?: return null
        val s = selection ?: return null
        val r = RectF(
            s.left.coerceIn(0f, b.width.toFloat()),
            s.top.coerceIn(0f, b.height.toFloat()),
            s.right.coerceIn(0f, b.width.toFloat()),
            s.bottom.coerceIn(0f, b.height.toFloat())
        )
        return if (r.width() < 4 || r.height() < 4) null else r
    }
}
