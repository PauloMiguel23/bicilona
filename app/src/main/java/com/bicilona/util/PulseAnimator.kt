package com.bicilona.util

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.animation.LinearInterpolator
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.*

/**
 * Smooth pulsing ring effect around a map position using a GroundOverlay.
 * Automatically scales with map zoom level.
 */
class PulseAnimator(
    private val context: Context,
    private val map: GoogleMap
) {
    private var overlay: GroundOverlay? = null
    private var animator: ValueAnimator? = null

    fun start(position: LatLng, color: Int) {
        stop()

        val bitmap = createRingBitmap(color)
        val initialRadius = baseRadiusForZoom(map.cameraPosition.zoom)

        overlay = map.addGroundOverlay(
            GroundOverlayOptions()
                .position(position, initialRadius)
                .image(BitmapDescriptorFactory.fromBitmap(bitmap))
                .transparency(0.2f)
                .zIndex(2f)
        )

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val base = baseRadiusForZoom(map.cameraPosition.zoom)
                val size = base * (1f + f * 2f)
                val alpha = 1f - f
                try {
                    overlay?.setDimensions(size)
                    overlay?.transparency = 1f - (alpha * 0.7f) // max 70% visible
                } catch (_: Exception) {
                    stop()
                }
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        overlay?.remove()
        overlay = null
    }

    /**
     * Scale base radius so the pulse looks the same screen-size at any zoom.
     * Reference: 30m at zoom 15 (street level).
     */
    private fun baseRadiusForZoom(zoom: Float): Float {
        val referenceZoom = 15f
        val referenceRadius = 120f
        val scale = Math.pow(2.0, (referenceZoom - zoom).toDouble()).toFloat()
        return referenceRadius * scale
    }

    private fun createRingBitmap(color: Int): Bitmap {
        val size = 200
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.alpha = 150
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, size / 2f, fillPaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.alpha = 255
            style = Paint.Style.STROKE
            strokeWidth = size / 8f
        }
        canvas.drawCircle(cx, cy, size / 2f - size / 20f, ringPaint)

        return bitmap
    }
}
