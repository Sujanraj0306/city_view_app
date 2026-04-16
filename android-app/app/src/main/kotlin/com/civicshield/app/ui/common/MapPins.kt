package com.civicshield.app.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable

/**
 * Canonical teardrop-pin factory used by the admin Case Plots map. Draws a
 * filled teardrop with a white stroke and a small inner circle.
 *
 * Colors follow the spec:
 *   - helmet pending    -> red
 *   - pothole pending   -> orange
 *   - completed (any)   -> green
 *   - in_progress       -> grey
 */
object MapPins {

    val COLOR_HELMET_PENDING: Int = Color.parseColor("#E53935")
    val COLOR_POTHOLE_PENDING: Int = Color.parseColor("#FB8C00")
    val COLOR_COMPLETED: Int = Color.parseColor("#43A047")
    val COLOR_IN_PROGRESS: Int = Color.parseColor("#9E9E9E")

    /** Maps (type, status) to the canonical pin color for the admin map. */
    fun colorFor(type: String, status: String): Int = when (status) {
        "completed" -> COLOR_COMPLETED
        "in_progress" -> COLOR_IN_PROGRESS
        else -> when (type) {
            "helmet" -> COLOR_HELMET_PENDING
            "pothole" -> COLOR_POTHOLE_PENDING
            else -> COLOR_IN_PROGRESS
        }
    }

    /**
     * User-map color rule (spec: red/orange/green only — no grey).
     * Completed wins; else type drives the color regardless of status.
     */
    fun colorForUser(type: String, status: String): Int = when {
        status == "completed" -> COLOR_COMPLETED
        type == "helmet" -> COLOR_HELMET_PENDING
        type == "pothole" -> COLOR_POTHOLE_PENDING
        else -> COLOR_IN_PROGRESS
    }

    fun teardrop(ctx: Context, color: Int): BitmapDrawable {
        val d = ctx.resources.displayMetrics.density
        val w = (28 * d).toInt()
        val h = (40 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = w / 2f
        val cyHead = w / 2f
        val r = (w / 2f) - 2 * d

        val path = Path().apply {
            addCircle(cx, cyHead, r, Path.Direction.CW)
            moveTo(cx - r * 0.55f, cyHead + r * 0.75f)
            lineTo(cx, h.toFloat() - 2 * d)
            lineTo(cx + r * 0.55f, cyHead + r * 0.75f)
            close()
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)

        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * d
        canvas.drawPath(path, paint)

        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cyHead, r * 0.35f, paint)

        return BitmapDrawable(ctx.resources, bmp)
    }
}
