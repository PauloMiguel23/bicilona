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
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        }
    }

    /**
     * Launch Google Maps with full directions: origin → destination, bicycling mode.
     */
    fun launchGoogleMapsDirections(
        context: android.content.Context,
        originLat: Double, originLon: Double,
        destLat: Double, destLon: Double
    ) {
        val uri = android.net.Uri.parse(
            "https://www.google.com/maps/dir/?api=1" +
            "&origin=$originLat,$originLon" +
            "&destination=$destLat,$destLon" +
            "&travelmode=bicycling"
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        }
    }

    /**
     * Launch Google Maps turn-by-turn navigation directly to a destination.
     * Uses the google.navigation: URI which auto-starts navigation,
     * replacing any current navigation session without a confirmation screen.
     */
    fun launchGoogleMapsNavigation(
        context: android.content.Context,
        destLat: Double,
        destLon: Double
    ) {
        val uri = android.net.Uri.parse("google.navigation:q=$destLat,$destLon&mode=b")
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            // Fallback to directions URL
            launchGoogleMapsDirections(context, destLat, destLon, destLat, destLon)
        }
    }
}