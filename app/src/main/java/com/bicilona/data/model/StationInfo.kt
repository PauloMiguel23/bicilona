package com.bicilona.data.model

import com.google.gson.annotations.SerializedName

/**
 * GBFS station_information.json response
 */
data class StationInfoResponse(
    @SerializedName("last_updated") val lastUpdated: Long,
    val data: StationInfoData
)

data class StationInfoData(
    val stations: List<StationInfo>
)

data class StationInfo(
    @SerializedName("station_id") val stationId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val altitude: Double?,
    val address: String?,
    val capacity: Int,
    @SerializedName("physical_configuration") val physicalConfiguration: String?,
    @SerializedName("is_charging_station") val isChargingStation: Boolean?
)
