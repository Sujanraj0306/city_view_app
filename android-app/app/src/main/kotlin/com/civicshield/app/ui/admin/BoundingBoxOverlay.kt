package com.civicshield.app.ui.admin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay that draws a red rectangle on top of a sibling ImageView
 * using Gemini's normalized (0..1) bounding box. Hidden by default; becomes
 * visible only when [setBox] is called with non-null values.
 */
class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var normX: Float = 0f
    private var normY: Float = 0f
    private var normW: Float = 0f
    private var normH: Float = 0f
    private var hasBox: Boolean = false

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E53935")
        strokeWidth = 4 * resources.displayMetrics.density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(40, 0xE5, 0x39, 0x35)
    }

    fun setBox(x: Double, y: Double, w: Double, h: Double) {
        normX = x.toFloat().coerceIn(0f, 1f)
        normY = y.toFloat().coerceIn(0f, 1f)
        normW = w.toFloat().coerceIn(0f, 1f)
        normH = h.toFloat().coerceIn(0f, 1f)
        hasBox = normW > 0f && normH > 0f
        visibility = if (hasBox) VISIBLE else GONE
        invalidate()
    }

    fun clearBox() {
        hasBox = false
        visibility = GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasBox) return
        val left = normX * width
        val top = normY * height
        val right = (normX + normW).coerceAtMost(1f) * width
        val bottom = (normY + normH).coerceAtMost(1f) * height
        val rect = RectF(left, top, right, bottom)
        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, strokePaint)
    }
}
