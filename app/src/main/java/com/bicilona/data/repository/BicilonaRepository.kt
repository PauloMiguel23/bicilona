package com.bicilona.data.repository

import com.bicilona.data.api.RetrofitClient
import com.bicilona.data.model.BicilonaRoute
import com.bicilona.data.model.BicilonaStation
import com.bicilona.ui.BikeTypePreference
import com.bicilona.util.LocationUtils
import com.bicilona.util.PolylineDecoder
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class BicilonaRepository {

    private val api = RetrofitClient.bicingApi
    private val directionsApi = RetrofitClient.directionsApi

    var mapsApiKey: String = ""

    suspend fun getAllStations(): List<BicilonaStation> = coroutineScope {
        val infoDeferred = async { api.getStationInfo() }
        val statusDeferred = async { api.getStationStatus() }

        val info = infoDeferred.await()
        val status = statusDeferred.await()

        val statusMap = status.data.stations.associateBy { it.stationId }

        info.data.stations.mapNotNull { station ->
            val st = statusMap[station.stationId] ?: return@mapNotNull null

            BicilonaStation(
                stationId = station.stationId,
                name = station.name.trim(),
                lat = station.lat,
                lon = station.lon,
                address = station.address,
                capacity = station.capacity,
                bikesAvailable = st.numBikesAvailable,
                docksAvailable = st.numDocksAvailable,
                isOperational = st.status == "IN_SERVICE" && st.isInstalled && st.isRenting && st.isReturning,
                vehicleTypes = st.vehicleTypesAvailable
                    ?.associate { it.vehicleTypeId to it.count }
                    ?: emptyMap()
            )
        }
    }

    /**
     * Find the best route, filtering pickup stations by bike type preference
     */
    suspend fun findRoute(
        origin: LatLng,
        destination: LatLng,
        bikeTypePref: BikeTypePreference = BikeTypePreference.BOTH,
        overridePickup: BicilonaStation? = null,
        overrideDropoff: BicilonaStation? = null
    ): BicilonaRoute = coroutineScope {
        val stations = getAllStations()

        // Use override pickup if provided, otherwise find nearest with preferred bike type
        val pickupStation = overridePickup ?: stations
            .filter { station ->
                station.isOperational && when (bikeTypePref) {
                    BikeTypePreference.MECHANICAL_ONLY -> station.mechanicalBikes > 0
                    BikeTypePreference.ELECTRIC_ONLY -> station.electricBikes > 0
                    BikeTypePreference.BOTH -> station.bikesAvailable > 0
                }
            }
            .minByOrNull {
                LocationUtils.distanceMeters(origin.latitude, origin.longitude, it.lat, it.lon)
            } ?: throw NoSuchElementException("No pickup station with your preferred bike type found")

        // Use override dropoff if provided, otherwise find the one minimizing total journey time
        val dropoffStation = overrideDropoff ?: run {
            val pickupLat = pickupStation.lat
            val pickupLon = pickupStation.lon
            val destLat = destination.latitude
            val destLon = destination.longitude
            val pickupStr = "${pickupLat},${pickupLon}"
            val destStr2 = "${destLat},${destLon}"

            // Walking ~80m/min, cycling ~250m/min — used only for initial ranking
            val walkSpeed = 80.0
            val bikeSpeed = 250.0

            // Take top 5 candidates by estimated time, then fetch real directions
            val candidates = stations
                .filter { it.isOperational && it.docksAvailable > 0 }
                .sortedBy { station ->
                    val rideMeters = LocationUtils.distanceMeters(pickupLat, pickupLon, station.lat, station.lon)
                    val walkMeters = LocationUtils.distanceMeters(station.lat, station.lon, destLat, destLon)
                    (rideMeters / bikeSpeed) + (walkMeters / walkSpeed)
                }
                .take(5)

            if (candidates.isEmpty()) throw NoSuchElementException("No dropoff station with available docks found")

            // Fetch real directions for each candidate in parallel
            val candidateResults = candidates.map { station ->
                val stationStr = "${station.lat},${station.lon}"
                async {
                    val ride = fetchDirections(pickupStr, stationStr, "bicycling")
                    val walk = fetchDirections(stationStr, destStr2, "walking")
                    val totalSeconds = (ride?.durationSeconds ?: Int.MAX_VALUE) +
                        (walk?.durationSeconds ?: Int.MAX_VALUE)
                    station to totalSeconds
                }
            }.map { it.await() }

            candidateResults.minByOrNull { it.second }?.first ?: candidates.first()
        }

        val pickupLatLng = "${pickupStation.lat},${pickupStation.lon}"
        val dropoffLatLng = "${dropoffStation.lat},${dropoffStation.lon}"
        val originStr = "${origin.latitude},${origin.longitude}"
        val destStr = "${destination.latitude},${destination.longitude}"

        val walkToPickup = async { fetchDirections(originStr, pickupLatLng, "walking") }
        val ride = async { fetchDirections(pickupLatLng, dropoffLatLng, "bicycling") }
        val walkToDest = async { fetchDirections(dropoffLatLng, destStr, "walking") }

        val walkToPickupResult = walkToPickup.await()
        val rideResult = ride.await()
        val walkToDestResult = walkToDest.await()

        BicilonaRoute(
            pickupStation = pickupStation,
            dropoffStation = dropoffStation,
            walkToPickupMeters = walkToPickupResult?.distanceMeters
                ?: LocationUtils.distanceMeters(origin.latitude, origin.longitude, pickupStation.lat, pickupStation.lon),
            rideMeters = rideResult?.distanceMeters
                ?: LocationUtils.distanceMeters(pickupStation.lat, pickupStation.lon, dropoffStation.lat, dropoffStation.lon),
            walkToDestinationMeters = walkToDestResult?.distanceMeters
                ?: LocationUtils.distanceMeters(dropoffStation.lat, dropoffStation.lon, destination.latitude, destination.longitude),
            walkToPickupPoints = walkToPickupResult?.points,
            ridePoints = rideResult?.points,
            walkToDestPoints = walkToDestResult?.points,
            walkToPickupDuration = walkToPickupResult?.durationText,
            rideDuration = rideResult?.durationText,
            walkToDestDuration = walkToDestResult?.durationText
        )
    }

    private data class DirectionsResult(
        val points: List<LatLng>,
        val distanceMeters: Double,
        val durationText: String,
        val durationSeconds: Int
    )

    private suspend fun fetchDirections(origin: String, destination: String, mode: String): DirectionsResult? {
        if (mapsApiKey.isBlank()) return null
        return try {
            val response = directionsApi.getDirections(origin, destination, mode, mapsApiKey)
            if (response.status == "OK" && response.routes.isNotEmpty()) {
                val route = response.routes[0]
                val leg = route.legs[0]
                DirectionsResult(
                    points = PolylineDecoder.decode(route.overviewPolyline.points),
                    distanceMeters = leg.distance.value.toDouble(),
                    durationText = leg.duration.text,
                    durationSeconds = leg.duration.value
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
