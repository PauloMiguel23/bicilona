package com.bicilona.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

/**
 * Creates small circular dot markers for map stations
 */
object MarkerFactory {

    /**
     * Create a filled circle dot BitmapDescriptor
     * @param color fill color (e.g. Color.GREEN)
     * @param radiusDp dot radius in dp
     * @param strokeColor optional border color
     * @param strokeWidthDp optional border width in dp
     */
    fun createDot(
        context: Context,
        color: Int,
        radiusDp: Float = 6f,
        strokeColor: Int? = null,
        strokeWidthDp: Float = 1.5f
    ): BitmapDescriptor {
        val density = context.resources.displayMetrics.density
        val radiusPx = (radiusDp * density).toInt()
        val strokePx = (strokeWidthDp * density)
        val size = (radiusPx + strokePx.toInt()) * 2 + 2

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f

        // Draw stroke/border if specified
        if (strokeColor != null) {
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = strokeColor
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, radiusPx + strokePx, strokePaint)
        }

        // Draw fill
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, radiusPx.toFloat(), fillPaint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    /**
     * Create a highlighted dot (larger, with a white ring + colored outer ring)
     */
    fun createHighlightedDot(
        context: Context,
        color: Int,
        radiusDp: Float = 10f
    ): BitmapDescriptor {
        val density = context.resources.displayMetrics.density
        val radiusPx = (radiusDp * density).toInt()
        val outerRingPx = (3f * density)
        val innerRingPx = (2f * density)
        val totalRadius = radiusPx + outerRingPx + innerRingPx
        val size = (totalRadius * 2 + 2).toInt()

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f

        // Outer colored ring
        val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, totalRadius, outerPaint)

        // White ring
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, radiusPx + innerRingPx, whitePaint)

        // Inner fill
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, radiusPx.toFloat(), fillPaint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}
