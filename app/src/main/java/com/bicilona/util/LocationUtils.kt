package com.bicilona.util

import kotlin.math.*

object LocationUtils {

    /**
     * Haversine distance in meters between two coordinates
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * Human-readable distance string
     */
    fun formatDistance(meters: Double): String {
        return if (meters < 1000) {
            "${meters.toInt()}m"
        } else {
            String.format("%.1f km", meters / 1000)
        }
    }

    /**
     * Estimated walking time (5 km/h)
     */
    fun walkingMinutes(meters: Double): Int = (meters / 83.33).toInt()

    /**
     * Estimated cycling time (15 km/h)
     */
    fun cyclingMinutes(meters: Double): Int = (meters / 250.0).toInt()

    /**
     * Launch Google Maps with a specific location
     */
    fun launchGoogleMaps(context: android.content.Context, lat: Double, lon: Double) {
        val uri = android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon(Destination)")
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            // Fallback if Google Maps is not installed
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        }
    }
}