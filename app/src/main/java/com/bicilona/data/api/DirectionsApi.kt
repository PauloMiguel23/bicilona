package com.bicilona.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Google Directions API for real cycling/walking routes
 */
interface DirectionsApi {

    @GET("json")
    suspend fun getDirections(
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("mode") mode: String,
        @Query("key") apiKey: String
    ): DirectionsResponse
}

data class DirectionsResponse(
    val routes: List<DirectionsRoute>,
    val status: String
)

data class DirectionsRoute(
    @SerializedName("overview_polyline") val overviewPolyline: OverviewPolyline,
    val legs: List<DirectionsLeg>
)

data class OverviewPolyline(
    val points: String
)

data class DirectionsLeg(
    val distance: DirectionsValue,
    val duration: DirectionsValue
)

data class DirectionsValue(
    val value: Int,
    val text: String
)
