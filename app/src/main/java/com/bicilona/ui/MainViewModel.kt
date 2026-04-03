package com.bicilona.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bicilona.data.db.BicilonaDatabase
import com.bicilona.data.db.FavoritePlace
import com.bicilona.data.model.BicilonaRoute
import com.bicilona.data.model.BicilonaStation
import com.bicilona.data.repository.BicilonaRepository
import com.bicilona.util.LocationUtils
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.launch

enum class BikeTypePreference {
    BOTH, MECHANICAL_ONLY, ELECTRIC_ONLY
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BicilonaRepository()
    private val favoriteDao = BicilonaDatabase.getInstance(application).favoriteDao()
    private val prefs = application.getSharedPreferences("bicilona_prefs", android.content.Context.MODE_PRIVATE)

    private var refreshJob: kotlinx.coroutines.Job? = null

    companion object {
        const val METERS_PER_BLOCK = 120.0
        const val MIN_BLOCKS = 1
        const val MAX_BLOCKS = 10
        const val DEFAULT_BLOCKS = 3
        const val REFRESH_INTERVAL_MS = 30_000L // 30 seconds
    }

    private var allStations: List<BicilonaStation> = emptyList()

    private val _visibleStations = MutableLiveData<List<BicilonaStation>>()
    val visibleStations: LiveData<List<BicilonaStation>> = _visibleStations

    private val _nearestStation = MutableLiveData<BicilonaStation?>()
    val nearestStation: LiveData<BicilonaStation?> = _nearestStation

    // Manually selected pickup station (null = auto-pick nearest)
    private val _selectedPickup = MutableLiveData<BicilonaStation?>()
    val selectedPickup: LiveData<BicilonaStation?> = _selectedPickup

    // Nearby dropoff alternatives shown when route is active
    private val _nearbyDropoffs = MutableLiveData<List<BicilonaStation>>()
    val nearbyDropoffs: LiveData<List<BicilonaStation>> = _nearbyDropoffs

    // Currently selected dropoff override (null = auto-pick nearest)
    private val _selectedDropoff = MutableLiveData<BicilonaStation?>()

    private val _route = MutableLiveData<BicilonaRoute?>()
    val route: LiveData<BicilonaRoute?> = _route

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _blockRadius = MutableLiveData(prefs.getInt("block_radius", DEFAULT_BLOCKS))
    val blockRadius: LiveData<Int> = _blockRadius

    private val _bikeTypePreference = MutableLiveData(
        BikeTypePreference.entries.getOrElse(prefs.getInt("bike_type", 0)) { BikeTypePreference.BOTH }
    )
    val bikeTypePreference: LiveData<BikeTypePreference> = _bikeTypePreference

    // Store the current destination for re-routing
    var currentDestination: LatLng? = null
        private set

    var userLocation: LatLng? = null
        set(value) {
            field = value
            filterStations()
        }

    val radiusMeters: Double
        get() = (_blockRadius.value ?: DEFAULT_BLOCKS) * METERS_PER_BLOCK

    fun setMapsApiKey(key: String) {
        repository.mapsApiKey = key
    }

    fun setBikeTypePreference(pref: BikeTypePreference) {
        _bikeTypePreference.value = pref
        prefs.edit().putInt("bike_type", pref.ordinal).apply()
        filterStations()
    }

    fun togglePickupStation(station: BicilonaStation) {
        if (_selectedPickup.value?.stationId == station.stationId) {
            _selectedPickup.value = null
        } else {
            _selectedPickup.value = station
        }
    }

    fun clearSelectedPickup() {
        _selectedPickup.value = null
    }

    /**
     * Select a different dropoff station and re-route
     */
    fun selectDropoffStation(station: BicilonaStation) {
        _selectedDropoff.value = station
        val dest = currentDestination ?: return
        findRouteInternal(dest)
    }

    fun incrementBlocks() {
        val current = _blockRadius.value ?: DEFAULT_BLOCKS
        if (current < MAX_BLOCKS) {
            _blockRadius.value = current + 1
            prefs.edit().putInt("block_radius", current + 1).apply()
            filterStations()
            recomputeNearbyDropoffs()
        }
    }

    fun decrementBlocks() {
        val current = _blockRadius.value ?: DEFAULT_BLOCKS
        if (current > MIN_BLOCKS) {
            _blockRadius.value = current - 1
            prefs.edit().putInt("block_radius", current - 1).apply()
            filterStations()
            recomputeNearbyDropoffs()
        }
    }

    fun resetToDefaults() {
        _blockRadius.value = DEFAULT_BLOCKS
        prefs.edit().putInt("block_radius", DEFAULT_BLOCKS).apply()
        _bikeTypePreference.value = BikeTypePreference.BOTH
        prefs.edit().putInt("bike_type", BikeTypePreference.BOTH.ordinal).apply()
        setWarningAtMinutes(25)
        setRedirectEnabled(false)
        setRedirectMinutes(1)
        filterStations()
        recomputeNearbyDropoffs()
    }

    /**
     * Re-apply radius to nearby dropoffs when block radius changes mid-route
     */
    private fun recomputeNearbyDropoffs() {
        val dest = currentDestination ?: return
        val route = _route.value ?: return
        computeNearbyDropoffs(dest, route.dropoffStation.stationId)
    }

    fun loadStations() {
        viewModelScope.launch {
            _loading.value = true
            try {
                allStations = repository.getAllStations()
                filterStations()
            } catch (e: Exception) {
                _error.value = "Failed to load stations: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(REFRESH_INTERVAL_MS)
                try {
                    allStations = repository.getAllStations()
                    filterStations()
                } catch (_: Exception) {
                    // Silent fail on background refresh
                }
            }
        }
    }

    private fun filterStations() {
        val loc = userLocation
        if (loc == null) {
            _visibleStations.value = allStations
            updateNearestStation()
            return
        }

        val radius = radiusMeters
        _visibleStations.value = allStations.filter { station ->
            LocationUtils.distanceMeters(loc.latitude, loc.longitude, station.lat, station.lon) <= radius
        }
        updateNearestStation()
    }

    private fun updateNearestStation() {
        val loc = userLocation ?: return
        val visible = _visibleStations.value ?: return
        
        // Try visible stations first, fall back to all stations
        val candidates = visible.filter { it.isOperational && relevantBikeCount(it) > 0 }
            .ifEmpty { allStations.filter { it.isOperational && relevantBikeCount(it) > 0 } }
        
        _nearestStation.value = candidates.minByOrNull {
            LocationUtils.distanceMeters(loc.latitude, loc.longitude, it.lat, it.lon)
        }
    }

    /**
     * Find stations with free docks near the destination
     */
    private fun computeNearbyDropoffs(destination: LatLng, currentDropoffId: String) {
        val radius = radiusMeters
        _nearbyDropoffs.value = allStations.filter { station ->
            station.isOperational &&
            station.docksAvailable > 0 &&
            station.stationId != currentDropoffId &&
            LocationUtils.distanceMeters(
                destination.latitude, destination.longitude,
                station.lat, station.lon
            ) <= radius
        }.sortedBy {
            LocationUtils.distanceMeters(
                destination.latitude, destination.longitude,
                it.lat, it.lon
            )
        }
    }

    fun relevantBikeCount(station: BicilonaStation): Int {
        return when (_bikeTypePreference.value) {
            BikeTypePreference.MECHANICAL_ONLY -> station.mechanicalBikes
            BikeTypePreference.ELECTRIC_ONLY -> station.electricBikes
            else -> station.bikesAvailable
        }
    }

    fun findRoute(destination: LatLng) {
        currentDestination = destination
        _selectedDropoff.value = null
        findRouteInternal(destination)
    }

    private fun findRouteInternal(destination: LatLng) {
        val origin = userLocation ?: run {
            _error.value = "Location not available yet"
            return
        }

        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val pref = _bikeTypePreference.value ?: BikeTypePreference.BOTH
                val overridePickup = _selectedPickup.value
                val overrideDropoff = _selectedDropoff.value
                val route = repository.findRoute(origin, destination, pref, overridePickup, overrideDropoff)
                _route.value = route
                // Compute nearby alternatives for dropoff
                computeNearbyDropoffs(destination, route.dropoffStation.stationId)
            } catch (e: Exception) {
                _error.value = "Could not find route: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun cancelRoute() {
        _route.value = null
        _selectedPickup.value = null
        _selectedDropoff.value = null
        _nearbyDropoffs.value = emptyList()
        currentDestination = null
        filterStations()
    }

    fun clearError() {
        _error.value = null
    }

    // ──── Timer settings ────

    /** Fixed Bicing free ride limit */
    val timeLimitMinutes: Int = 30

    /** Warn at X minutes into the ride (default 25 = 5 min before limit) */
    private val _warningAtMinutes = MutableLiveData(prefs.getInt("warning_at_minutes", 25))
    val warningAtMinutes: LiveData<Int> = _warningAtMinutes

    private val _redirectMinutes = MutableLiveData(prefs.getInt("redirect_minutes", 1))
    val redirectMinutes: LiveData<Int> = _redirectMinutes

    private val _redirectEnabled = MutableLiveData(prefs.getBoolean("redirect_enabled", false))
    val redirectEnabled: LiveData<Boolean> = _redirectEnabled

    fun setWarningAtMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(5, 29)
        _warningAtMinutes.value = clamped
        prefs.edit().putInt("warning_at_minutes", clamped).apply()
    }

    fun setRedirectMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(1, 10)
        _redirectMinutes.value = clamped
        prefs.edit().putInt("redirect_minutes", clamped).apply()
    }

    fun setRedirectEnabled(enabled: Boolean) {
        _redirectEnabled.value = enabled
        prefs.edit().putBoolean("redirect_enabled", enabled).apply()
    }

    /**
     * Find the nearest station with free docks to the user's current location
     */
    fun findNearestDropoffToUser(): BicilonaStation? {
        val loc = userLocation ?: return null
        return allStations
            .filter { it.isOperational && it.docksAvailable > 0 }
            .minByOrNull {
                LocationUtils.distanceMeters(loc.latitude, loc.longitude, it.lat, it.lon)
            }
    }

    // ──── Favorites ────

    val favorites: LiveData<List<FavoritePlace>> = favoriteDao.getAll()

    fun saveFavorite(name: String, lat: Double, lon: Double) {
        viewModelScope.launch {
            favoriteDao.insert(FavoritePlace(name = name, lat = lat, lon = lon))
        }
    }

    fun deleteFavorite(favorite: FavoritePlace) {
        viewModelScope.launch {
            favoriteDao.delete(favorite)
        }
    }
}
