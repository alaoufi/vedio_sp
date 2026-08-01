package com.myvideolibrary.app.ui.player

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * A movable/resizable opaque box drawn over the player to cover ("hide") floating
 * text burned into a video — while watching, without touching the file. When not
 * editable it just paints the box and lets touches fall through to the player.
 */
class HideBoxView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val rect = RectF()
    var hasBox = false
        private set
    private var editable = false
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f

    private enum class Mode { NONE, MOVE, RESIZE }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN }

    private val handleSize = 48f

    fun setEditable(on: Boolean) {
        editable = on
        if (on && !hasBox) {
            // Default to a strip near the bottom, where captions usually sit.
            rect.set(width * 0.15f, height * 0.72f, width * 0.85f, height * 0.9f)
            hasBox = true
        }
        invalidate()
    }

    fun clearBox() {
        hasBox = false
        invalidate()
    }

    /** Box as fractions of the view (l,t,r,b), or null when there is none. */
    fun normalizedRect(): FloatArray? {
        if (!hasBox || width == 0 || height == 0) return null
        return floatArrayOf(
            rect.left / width, rect.top / height, rect.right / width, rect.bottom / height
        )
    }

    fun setNormalizedRect(n: FloatArray) {
        post {
            if (width == 0 || height == 0) return@post
            rect.set(n[0] * width, n[1] * height, n[2] * width, n[3] * height)
            hasBox = true
            invalidate()
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (!hasBox) return
        canvas.drawRect(rect, fill)
        if (editable) {
            canvas.drawRect(rect, border)
            canvas.drawCircle(rect.right, rect.bottom, handleSize / 2, handle)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable || !hasBox) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                mode = when {
                    abs(event.x - rect.right) < handleSize && abs(event.y - rect.bottom) < handleSize ->
                        Mode.RESIZE
                    rect.contains(event.x, event.y) -> Mode.MOVE
                    else -> Mode.NONE
                }
                lastX = event.x
                lastY = event.y
                return mode != Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                when (mode) {
                    Mode.MOVE -> rect.offset(dx, dy)
                    Mode.RESIZE -> {
                        rect.right = (rect.right + dx).coerceAtLeast(rect.left + handleSize)
                        rect.bottom = (rect.bottom + dy).coerceAtLeast(rect.top + handleSize)
                    }
                    Mode.NONE -> {}
                }
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
