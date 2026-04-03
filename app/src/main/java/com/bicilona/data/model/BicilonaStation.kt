package com.bicilona.data.model

import com.google.android.gms.maps.model.LatLng

/**
 * Combined station data: info + real-time status
 */
data class BicilonaStation(
    val stationId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String?,
    val capacity: Int,
    val bikesAvailable: Int,
    val docksAvailable: Int,
    val isOperational: Boolean,
    val vehicleTypes: Map<String, Int> // e.g. "ICONIC" -> 5, "BOOST" -> 2
) {
    val mechanicalBikes: Int get() = vehicleTypes.getOrDefault("ICONIC", 0)
    val electricBikes: Int get() = (vehicleTypes.getOrDefault("BOOST", 0)
            + vehicleTypes.getOrDefault("EFIT", 0)
            + vehicleTypes.getOrDefault("FIT", 0))
}

/**
 * A route plan: walk → bike → walk, with actual path polylines
 */
data class BicilonaRoute(
    val pickupStation: BicilonaStation,
    val dropoffStation: BicilonaStation,
    val walkToPickupMeters: Double,
    val rideMeters: Double,
    val walkToDestinationMeters: Double,
    val walkToPickupPoints: List<LatLng>? = null,
    val ridePoints: List<LatLng>? = null,
    val walkToDestPoints: List<LatLng>? = null,
    val walkToPickupDuration: String? = null,
    val rideDuration: String? = null,
    val walkToDestDuration: String? = null
)
