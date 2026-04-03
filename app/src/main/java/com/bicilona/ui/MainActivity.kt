package com.bicilona.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.bicilona.R
import com.bicilona.data.db.FavoritePlace
import com.bicilona.data.model.BicilonaRoute
import com.bicilona.data.model.BicilonaStation
import com.bicilona.util.LocationUtils
import com.bicilona.util.MarkerFactory
import com.bicilona.util.PulseAnimator
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.model.Place
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val LOCATION_PERMISSION_REQUEST = 1
    }

    private lateinit var googleMap: GoogleMap
    private val viewModel: MainViewModel by viewModels()

    // UI elements
    private lateinit var bottomSheet: BottomSheetBehavior<View>
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var etDestination: AutoCompleteTextView
    private lateinit var tvCurrentLocation: TextView
    private lateinit var btnResetPickup: ImageButton
    private lateinit var progressBar: View

    // Bottom sheet views
    private lateinit var tvRouteTitle: TextView
    private lateinit var tvRouteSummary: TextView
    private lateinit var tvPickupStation: TextView
    private lateinit var tvPickupInfo: TextView
    private lateinit var tvRideInfo: TextView
    private lateinit var tvDropoffStation: TextView
    private lateinit var tvDropoffInfo: TextView
    private lateinit var tvDestWalkInfo: TextView

    // Settings views
    private lateinit var tvBlockCount: TextView
    private lateinit var tvBlockDistance: TextView

    // Map state
    private val stationMarkers = mutableMapOf<String, Marker>()
    private var pickupMarker: Marker? = null
    private var dropoffMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private val routePolylines = mutableListOf<Polyline>()
    private var pulseAnimator: PulseAnimator? = null

    // Place autocomplete
    private lateinit var dropdownAdapter: DestinationDropdownAdapter
    private var currentFavorites: List<FavoritePlace> = emptyList()
    private var currentPredictions: List<AutocompletePrediction> = emptyList()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    // Cached marker icons
    private lateinit var dotGreen: BitmapDescriptor
    private lateinit var dotOrange: BitmapDescriptor
    private lateinit var dotRed: BitmapDescriptor
    private lateinit var dotGray: BitmapDescriptor
    private lateinit var dotBlue: BitmapDescriptor
    private lateinit var dotPurple: BitmapDescriptor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Places SDK
        val apiKey = packageManager
            .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("com.google.android.geo.API_KEY") ?: ""
        if (apiKey.isNotBlank() && !Places.isInitialized()) {
            Places.initialize(applicationContext, apiKey)
        }
        viewModel.setMapsApiKey(apiKey)

        initViews()
        initBottomSheet()
        initSettingsDrawer()
        initSearch()
        observeViewModel()

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync { map ->
            googleMap = map
            initMarkerIcons()
            setupMap()
            enableMyLocation()
            loadClosestStations()
        }
    }

    // ════════════════════════════════════════
    // Initialization
    // ════════════════════════════════════════

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        etDestination = findViewById(R.id.etDestination)
        tvCurrentLocation = findViewById(R.id.tvCurrentLocation)
        btnResetPickup = findViewById(R.id.btnResetPickup)
        progressBar = findViewById(R.id.progressBar)

        // Bottom sheet views
        tvRouteTitle = findViewById(R.id.tvRouteTitle)
        tvRouteSummary = findViewById(R.id.tvRouteSummary)
        tvPickupStation = findViewById(R.id.tvPickupStation)
        tvPickupInfo = findViewById(R.id.tvPickupInfo)
        tvRideInfo = findViewById(R.id.tvRideInfo)
        tvDropoffStation = findViewById(R.id.tvDropoffStation)
        tvDropoffInfo = findViewById(R.id.tvDropoffInfo)
        tvDestWalkInfo = findViewById(R.id.tvDestWalkInfo)

        // Settings
        tvBlockCount = findViewById(R.id.tvBlockCount)
        tvBlockDistance = findViewById(R.id.tvBlockDistance)

        // Settings gear button
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            drawerLayout.openDrawer(findViewById(R.id.settingsDrawer))
        }

        // Cancel route button
        findViewById<View>(R.id.btnCancelRoute).setOnClickListener {
            cancelRoute()
        }

        // Reset pickup
        btnResetPickup.setOnClickListener {
            viewModel.clearSelectedPickup()
            btnResetPickup.visibility = View.GONE
            tvCurrentLocation.text = "Getting nearest station…"
        }

        // Save favorite button
        findViewById<View>(R.id.btnSaveFavorite).setOnClickListener {
            val dest = viewModel.currentDestination ?: return@setOnClickListener
            showSaveFavoriteDialog(dest)
        }

        // Navigate button
        findViewById<View>(R.id.btnNavigate).setOnClickListener {
            val route = viewModel.route.value ?: return@setOnClickListener
            LocationUtils.launchGoogleMaps(
                this,
                route.pickupStation.lat,
                route.pickupStation.lon
            )
        }
    }

    private fun initBottomSheet() {
        val sheet = findViewById<View>(R.id.bottomSheet)
        bottomSheet = BottomSheetBehavior.from(sheet)
        // Hide the bottom sheet initially — no route to show yet
        bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun initMarkerIcons() {
        dotGreen = MarkerFactory.createDot(this, Color.parseColor("#4CAF50"), strokeColor = Color.WHITE)
        dotOrange = MarkerFactory.createDot(this, Color.parseColor("#FF9800"), strokeColor = Color.WHITE)
        dotRed = MarkerFactory.createDot(this, Color.parseColor("#E30613"), strokeColor = Color.WHITE)
        dotGray = MarkerFactory.createDot(this, Color.GRAY, strokeColor = Color.WHITE)
        dotBlue = MarkerFactory.createHighlightedDot(this, Color.parseColor("#2196F3"))
        dotPurple = MarkerFactory.createHighlightedDot(this, Color.parseColor("#9C27B0"))
    }

    private fun initSettingsDrawer() {
        // Block radius controls
        findViewById<MaterialButton>(R.id.btnBlockMinus).setOnClickListener {
            viewModel.decrementBlocks()
        }
        findViewById<MaterialButton>(R.id.btnBlockPlus).setOnClickListener {
            viewModel.incrementBlocks()
        }

        // Bike type buttons
        val btnMechanical = findViewById<MaterialButton>(R.id.btnMechanical)
        val btnElectric = findViewById<MaterialButton>(R.id.btnElectric)

        btnMechanical.setOnClickListener {
            val current = viewModel.bikeTypePreference.value
            if (current == BikeTypePreference.MECHANICAL_ONLY) {
                viewModel.setBikeTypePreference(BikeTypePreference.BOTH)
            } else {
                viewModel.setBikeTypePreference(BikeTypePreference.MECHANICAL_ONLY)
            }
        }
        btnElectric.setOnClickListener {
            val current = viewModel.bikeTypePreference.value
            if (current == BikeTypePreference.ELECTRIC_ONLY) {
                viewModel.setBikeTypePreference(BikeTypePreference.BOTH)
            } else {
                viewModel.setBikeTypePreference(BikeTypePreference.ELECTRIC_ONLY)
            }
        }
    }

    private fun initSearch() {
        dropdownAdapter = DestinationDropdownAdapter(this) { fav ->
            viewModel.deleteFavorite(fav)
        }
        dropdownAdapter.onItemClickListener = { item ->
            when (item) {
                is DropdownItem.Favorite -> {
                    val latLng = LatLng(item.place.lat, item.place.lon)
                    etDestination.setText(item.place.name)
                    etDestination.dismissDropDown()
                    hideKeyboard()
                    setDestination(latLng)
                }
                is DropdownItem.Suggestion -> {
                    fetchPlaceAndRoute(item.prediction)
                    etDestination.dismissDropDown()
                    hideKeyboard()
                }
            }
        }
        etDestination.setAdapter(dropdownAdapter)

        // Show favorites when field gets focus with empty text
        etDestination.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && etDestination.text.isNullOrBlank() && currentFavorites.isNotEmpty()) {
                dropdownAdapter.showFavoritesOnly(currentFavorites)
                etDestination.showDropDown()
            }
        }

        // Text changed → autocomplete
        etDestination.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                if (query.length >= 2) {
                    searchRunnable = Runnable { searchPlaces(query) }
                    searchHandler.postDelayed(searchRunnable!!, 300)
                } else if (query.isEmpty() && currentFavorites.isNotEmpty()) {
                    dropdownAdapter.showFavoritesOnly(currentFavorites)
                }
            }
        })

        // Handle item click from dropdown
        etDestination.setOnItemClickListener { _, _, position, _ ->
            val item = dropdownAdapter.getItem(position)
            when (item) {
                is DropdownItem.Favorite -> {
                    val latLng = LatLng(item.place.lat, item.place.lon)
                    etDestination.setText(item.place.name)
                    hideKeyboard()
                    setDestination(latLng)
                }
                is DropdownItem.Suggestion -> {
                    fetchPlaceAndRoute(item.prediction)
                    hideKeyboard()
                }
            }
        }

        // IME search action
        etDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                val query = etDestination.text?.toString()?.trim() ?: ""
                if (query.isNotEmpty()) {
                    geocodeAndRoute(query)
                }
                true
            } else false
        }
    }

    // ════════════════════════════════════════
    // Map setup
    // ════════════════════════════════════════

    private fun setupMap() {
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(41.3874, 2.1686), 14f))
        googleMap.uiSettings.isMapToolbarEnabled = false

        // Custom info window
        googleMap.setInfoWindowAdapter(object : GoogleMap.InfoWindowAdapter {
            override fun getInfoContents(marker: Marker): View? = null
            override fun getInfoWindow(marker: Marker): View? {
                val station = marker.tag as? BicilonaStation ?: return null
                val view = LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.info_window_station, null)

                view.findViewById<TextView>(R.id.tvInfoTitle).text = station.name
                view.findViewById<TextView>(R.id.tvInfoMechanical).text =
                    "⚙️ ${station.mechanicalBikes}"
                view.findViewById<TextView>(R.id.tvInfoElectric).text =
                    "⚡ ${station.electricBikes}"
                view.findViewById<TextView>(R.id.tvInfoDocks).text =
                    "🅿️ ${station.docksAvailable} docks free"
                return view
            }
        })

        // Long-press to set destination
        googleMap.setOnMapLongClickListener { latLng ->
            etDestination.setText("📍 ${String.format("%.4f, %.4f", latLng.latitude, latLng.longitude)}")
            setDestination(latLng)
        }

        // Tap on station marker → toggle pickup or show info
        googleMap.setOnMarkerClickListener { marker ->
            val station = marker.tag as? BicilonaStation
            if (station != null && viewModel.route.value == null) {
                // No active route — toggle as custom pickup
                viewModel.togglePickupStation(station)
                marker.showInfoWindow()
                true
            } else {
                // Let default info window show
                false
            }
        }
    }

    private fun enableMyLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            googleMap.isMyLocationEnabled = true
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            enableMyLocation()
            loadClosestStations()
        }
    }

    // ════════════════════════════════════════
    // Station loading
    // ════════════════════════════════════════

    private fun loadClosestStations() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val latLng = LatLng(location.latitude, location.longitude)
                viewModel.userLocation = latLng
                viewModel.loadStations()
                // Zoom to user location
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
            } else {
                Log.w(TAG, "Last location is null, loading all stations")
                viewModel.loadStations()
            }
        }
    }

    // ════════════════════════════════════════
    // Observe ViewModel
    // ════════════════════════════════════════

    private fun observeViewModel() {
        viewModel.visibleStations.observe(this) { stations ->
            updateStationMarkers(stations)
        }

        viewModel.nearestStation.observe(this) { station ->
            if (station != null && viewModel.selectedPickup.value == null) {
                tvCurrentLocation.text = station.name
            }
        }

        viewModel.selectedPickup.observe(this) { station ->
            if (station != null) {
                tvCurrentLocation.text = "📌 ${station.name}"
                btnResetPickup.visibility = View.VISIBLE
            } else {
                btnResetPickup.visibility = View.GONE
                val nearest = viewModel.nearestStation.value
                tvCurrentLocation.text = nearest?.name ?: "Getting nearest station…"
            }
            // Re-render markers to highlight selected pickup
            viewModel.visibleStations.value?.let { updateStationMarkers(it) }
        }

        viewModel.route.observe(this) { route ->
            if (route != null) {
                showRoute(route)
            } else {
                clearRouteFromMap()
                bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
            }
        }

        viewModel.loading.observe(this) { isLoading ->
            progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(this) { error ->
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        viewModel.blockRadius.observe(this) { blocks ->
            tvBlockCount.text = blocks.toString()
            tvBlockDistance.text = "~${(blocks * MainViewModel.METERS_PER_BLOCK).toInt()}m"
        }

        viewModel.bikeTypePreference.observe(this) { pref ->
            val btnMech = findViewById<MaterialButton>(R.id.btnMechanical)
            val btnElec = findViewById<MaterialButton>(R.id.btnElectric)
            btnMech.alpha = if (pref == BikeTypePreference.MECHANICAL_ONLY) 1f else 0.5f
            btnElec.alpha = if (pref == BikeTypePreference.ELECTRIC_ONLY) 1f else 0.5f
            // Refresh markers with new coloring
            viewModel.visibleStations.value?.let { updateStationMarkers(it) }
        }

        viewModel.favorites.observe(this) { favs ->
            currentFavorites = favs
        }
    }

    // ════════════════════════════════════════
    // Station markers
    // ════════════════════════════════════════

    private fun updateStationMarkers(stations: List<BicilonaStation>) {
        if (!::googleMap.isInitialized) return

        val currentIds = stations.map { it.stationId }.toSet()
        val selectedPickupId = viewModel.selectedPickup.value?.stationId

        // Remove markers for stations no longer visible
        val toRemove = stationMarkers.keys - currentIds
        toRemove.forEach { id ->
            stationMarkers.remove(id)?.remove()
        }

        // Add or update markers
        stations.forEach { station ->
            val icon = when {
                station.stationId == selectedPickupId -> dotBlue
                !station.isOperational -> dotGray
                viewModel.relevantBikeCount(station) == 0 -> dotRed
                viewModel.relevantBikeCount(station) <= 3 -> dotOrange
                else -> dotGreen
            }

            val existing = stationMarkers[station.stationId]
            if (existing != null) {
                existing.setIcon(icon)
                existing.tag = station
            } else {
                val marker = googleMap.addMarker(
                    MarkerOptions()
                        .position(LatLng(station.lat, station.lon))
                        .icon(icon)
                        .anchor(0.5f, 0.5f)
                        .zIndex(1f)
                )
                marker?.tag = station
                if (marker != null) {
                    stationMarkers[station.stationId] = marker
                }
            }
        }
    }

    // ════════════════════════════════════════
    // Route display
    // ════════════════════════════════════════

    private fun showRoute(route: BicilonaRoute) {
        clearRouteFromMap()

        val pickup = route.pickupStation
        val dropoff = route.dropoffStation

        // Pickup marker (blue)
        pickupMarker = googleMap.addMarker(
            MarkerOptions()
                .position(LatLng(pickup.lat, pickup.lon))
                .icon(dotBlue)
                .anchor(0.5f, 0.5f)
                .zIndex(3f)
                .title(pickup.name)
        )
        pickupMarker?.tag = pickup

        // Dropoff marker (purple)
        dropoffMarker = googleMap.addMarker(
            MarkerOptions()
                .position(LatLng(dropoff.lat, dropoff.lon))
                .icon(dotPurple)
                .anchor(0.5f, 0.5f)
                .zIndex(3f)
                .title(dropoff.name)
        )
        dropoffMarker?.tag = dropoff

        // Destination marker
        viewModel.currentDestination?.let { dest ->
            destinationMarker = googleMap.addMarker(
                MarkerOptions()
                    .position(dest)
                    .zIndex(4f)
                    .title("Destination")
            )
        }

        // Pulse on pickup
        pulseAnimator = PulseAnimator(this, googleMap)
        pulseAnimator?.start(LatLng(pickup.lat, pickup.lon), Color.parseColor("#2196F3"))

        // Draw polylines
        route.walkToPickupPoints?.let { points ->
            routePolylines.add(googleMap.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .color(Color.parseColor("#2196F3"))
                    .width(8f)
                    .pattern(listOf(Dot(), Gap(12f)))
                    .zIndex(2f)
            ))
        }
        route.ridePoints?.let { points ->
            routePolylines.add(googleMap.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .color(Color.parseColor("#4CAF50"))
                    .width(10f)
                    .zIndex(2f)
            ))
        }
        route.walkToDestPoints?.let { points ->
            routePolylines.add(googleMap.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .color(Color.parseColor("#9C27B0"))
                    .width(8f)
                    .pattern(listOf(Dot(), Gap(12f)))
                    .zIndex(2f)
            ))
        }

        // Fit camera to route
        val boundsBuilder = LatLngBounds.builder()
        viewModel.userLocation?.let { boundsBuilder.include(it) }
        viewModel.currentDestination?.let { boundsBuilder.include(it) }
        boundsBuilder.include(LatLng(pickup.lat, pickup.lon))
        boundsBuilder.include(LatLng(dropoff.lat, dropoff.lon))
        try {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 120))
        } catch (e: Exception) {
            Log.w(TAG, "Could not fit bounds", e)
        }

        // Update bottom sheet
        val walkDist = LocationUtils.formatDistance(route.walkToPickupMeters)
        val walkTime = route.walkToPickupDuration ?: "${LocationUtils.walkingMinutes(route.walkToPickupMeters)} min"
        val rideDist = LocationUtils.formatDistance(route.rideMeters)
        val rideTime = route.rideDuration ?: "${LocationUtils.cyclingMinutes(route.rideMeters)} min"
        val destDist = LocationUtils.formatDistance(route.walkToDestinationMeters)
        val destTime = route.walkToDestDuration ?: "${LocationUtils.walkingMinutes(route.walkToDestinationMeters)} min"

        tvRouteSummary.text = "$rideTime · $rideDist ride"
        tvPickupStation.text = pickup.name
        tvPickupInfo.text = "$walkDist · $walkTime · ${viewModel.relevantBikeCount(pickup)} bikes"
        tvRideInfo.text = "$rideDist · $rideTime"
        tvDropoffStation.text = dropoff.name
        tvDropoffInfo.text = "${dropoff.docksAvailable} docks free"
        tvDestWalkInfo.text = "$destDist · $destTime walk"

        // Show bottom sheet
        bottomSheet.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun clearRouteFromMap() {
        pickupMarker?.remove()
        pickupMarker = null
        dropoffMarker?.remove()
        dropoffMarker = null
        destinationMarker?.remove()
        destinationMarker = null
        routePolylines.forEach { it.remove() }
        routePolylines.clear()
        pulseAnimator?.stop()
        pulseAnimator = null
    }

    private fun cancelRoute() {
        viewModel.cancelRoute()
        etDestination.text?.clear()
        // Zoom back to user
        viewModel.userLocation?.let { loc ->
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 15f))
        }
    }

    // ════════════════════════════════════════
    // Place search & geocoding
    // ════════════════════════════════════════

    private fun searchPlaces(query: String) {
        if (!Places.isInitialized()) return

        val placesClient = Places.createClient(this)
        val bounds = RectangularBounds.newInstance(
            LatLng(41.32, 2.06),  // SW Barcelona
            LatLng(41.47, 2.23)   // NE Barcelona
        )

        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setLocationBias(bounds)
            .build()

        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                currentPredictions = response.autocompletePredictions
                dropdownAdapter.setData(currentFavorites, currentPredictions)
                if (dropdownAdapter.count > 0) {
                    etDestination.showDropDown()
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Place autocomplete failed", e)
            }
    }

    private fun fetchPlaceAndRoute(prediction: AutocompletePrediction) {
        if (!Places.isInitialized()) return

        val placesClient = Places.createClient(this)
        val request = FetchPlaceRequest.newInstance(
            prediction.placeId,
            listOf(Place.Field.LAT_LNG, Place.Field.NAME)
        )

        placesClient.fetchPlace(request)
            .addOnSuccessListener { response ->
                val latLng = response.place.latLng
                if (latLng != null) {
                    etDestination.setText(prediction.getPrimaryText(null))
                    setDestination(latLng)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Fetch place failed", e)
                Toast.makeText(this, "Could not get place details", Toast.LENGTH_SHORT).show()
            }
    }

    private fun geocodeAndRoute(query: String) {
        if (!Places.isInitialized()) return

        val placesClient = Places.createClient(this)
        val bounds = RectangularBounds.newInstance(
            LatLng(41.32, 2.06),
            LatLng(41.47, 2.23)
        )

        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setLocationBias(bounds)
            .build()

        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                val first = response.autocompletePredictions.firstOrNull()
                if (first != null) {
                    fetchPlaceAndRoute(first)
                } else {
                    Toast.makeText(this, "No results found", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun setDestination(latLng: LatLng) {
        viewModel.findRoute(latLng)
    }

    // ════════════════════════════════════════
    // Favorites dialog
    // ════════════════════════════════════════

    private fun showSaveFavoriteDialog(destination: LatLng) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_save_favorite, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.btnSave).setOnClickListener {
            val name = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etFavName)
                .text?.toString()?.trim() ?: ""
            if (name.isNotBlank()) {
                viewModel.saveFavorite(name, destination.latitude, destination.longitude)
                Toast.makeText(this, "Saved ⭐ $name", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        // Cancel button (first MaterialButton that isn't btnSave)
        val buttons = dialogView.let { view ->
            val parent = (view as android.view.ViewGroup)
            findViewsOfType(parent, MaterialButton::class.java)
        }
        buttons.firstOrNull { it.id != R.id.btnSave }?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun <T : View> findViewsOfType(viewGroup: android.view.ViewGroup, clazz: Class<T>): List<T> {
        val result = mutableListOf<T>()
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (clazz.isInstance(child)) result.add(clazz.cast(child))
            if (child is android.view.ViewGroup) result.addAll(findViewsOfType(child, clazz))
        }
        return result
    }

    // ════════════════════════════════════════
    // Utility
    // ════════════════════════════════════════

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etDestination.windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.stop()
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
    }
}
