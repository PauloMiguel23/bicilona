package com.bicilona.data.model

import com.google.gson.annotations.SerializedName

/**
 * GBFS station_status.json response
 */
data class StationStatusResponse(
    @SerializedName("last_updated") val lastUpdated: Long,
    val data: StationStatusData
)

data class StationStatusData(
    val stations: List<StationStatus>
)

data class StationStatus(
    @SerializedName("station_id") val stationId: String,
    @SerializedName("num_bikes_available") val numBikesAvailable: Int,
    @SerializedName("num_docks_available") val numDocksAvailable: Int,
    @SerializedName("num_bikes_disabled") val numBikesDisabled: Int,
    @SerializedName("num_docks_disabled") val numDocksDisabled: Int,
    val status: String,
    @SerializedName("is_installed") val isInstalled: Boolean,
    @SerializedName("is_renting") val isRenting: Boolean,
    @SerializedName("is_returning") val isReturning: Boolean,
    @SerializedName("last_reported") val lastReported: Long?,
    @SerializedName("vehicle_types_available") val vehicleTypesAvailable: List<VehicleType>?
)

data class VehicleType(
    @SerializedName("vehicle_type_id") val vehicleTypeId: String,
    val count: Int
)
