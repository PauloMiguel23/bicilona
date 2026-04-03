package com.bicilona.data.api

import com.bicilona.data.model.StationInfoResponse
import com.bicilona.data.model.StationStatusResponse
import retrofit2.http.GET

/**
 * GBFS-compatible API for Barcelona Bicing
 */
interface BicilonaApi {

    @GET("station_information.json")
    suspend fun getStationInfo(): StationInfoResponse

    @GET("station_status.json")
    suspend fun getStationStatus(): StationStatusResponse
}
