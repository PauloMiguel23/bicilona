package com.bicilona.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.content.Intent
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
import com.bicilona.service.RideTimerService
import com.bicilona.util.LocationUtils
import com.bicilona.util.MarkerFactory
import com.bicilona.util.PulseAnimator
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
    private lateinit var emptyStateCard: View

    // Bottom sheet views
    private lateinit var tvTotalTime: TextView
    private lateinit var tvRouteSummary: TextView
    private lateinit var tvPickupStation: TextView
    private lateinit var tvPickupInfo: TextView
    private lateinit var tvRideInfo: TextView
    private lateinit var tvDropoffStation: TextView
    private lateinit var tvDropoffInfo: TextView
    private lateinit var tvDestWalkInfo: TextView

    // Ride timer
    private var hasWarned = false
    private var hasRedirected = false

    // Settings views
    private lateinit var tvBlockCount: TextView
    private lateinit var tvBlockDistance: TextView

    // Map state
    private val stationMarkers = mutableMapOf<String, Marker>()
    private var pickupMarker: Marker? = null
    private var dropoffMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private val nearbyDropoffMarkers = mutableListOf<Marker>()
    private val favoriteMarkers = mutableListOf<Marker>()
    private val routePolylines = mutableListOf<Polyline>()
    private var pulseAnimator: PulseAnimator? = null

    // Place autocomplete
    private lateinit var dropdownAdapter: DestinationDropdownAdapter
    private var currentFavorites: List<FavoritePlace> = emptyList()
    private var currentPredictions: List<AutocompletePrediction> = emptyList()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var suppressDropdown = false

    // Live location
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var locationCallback: LocationCallback? = null
    private var hasInitialLocation = false

    // Cached marker icons
    private lateinit var dotGreen: BitmapDescriptor
    private lateinit var dotOrange: BitmapDescriptor
    private lateinit var dotRed: BitmapDescriptor
    private lateinit var dotGray: BitmapDescriptor
    private lateinit var highlightGreen: BitmapDescriptor
    private lateinit var highlightOrange: BitmapDescriptor
    private lateinit var highlightRed: BitmapDescriptor
    private lateinit var highlightGray: BitmapDescriptor
    private lateinit var dotPurple: BitmapDescriptor
    private lateinit var starIcon: BitmapDescriptor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Places SDK
        val apiKey = packageManager
            .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("com.google.android.geo.API_KEY") ?: ""
        if (apiKey.isNotBlank() && !Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(applicationContext, apiKey)
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
        emptyStateCard = findViewById(R.id.emptyStateCard)

        // Empty state expand radius button
        findViewById<View>(R.id.btnExpandRadius).setOnClickListener {
            viewModel.incrementBlocks()
        }

        // Bottom sheet views
        tvTotalTime = findViewById(R.id.tvTotalTime)
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

        // My location button
        findViewById<View>(R.id.btnMyLocation).setOnClickListener {
            viewModel.userLocation?.let { loc ->
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 15f))
            }
        }

        // Settings gear button
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            drawerLayout.openDrawer(findViewById(R.id.settingsDrawer))
        }

        // Help button
        findViewById<View>(R.id.btnHelp).setOnClickListener {
            showHelpDialog()
        }

        // Cancel route button
        findViewById<View>(R.id.btnCancelRoute).setOnClickListener {
            cancelRoute()
        }

        // Reset pickup
        btnResetPickup.setOnClickListener {
            viewModel.clearSelectedPickup()
            btnResetPickup.visibility = View.GONE
            tvCurrentLocation.text = getString(R.string.getting_nearest_station)
        }

        // Save favorite button
        findViewById<View>(R.id.btnSaveFavorite).setOnClickListener {
            val dest = viewModel.currentDestination ?: return@setOnClickListener
            if (!isCurrentDestinationFavorite()) {
                showSaveFavoriteDialog(dest)
            }
        }

        // Start ride — launches Google Maps navigation AND starts the timer
        findViewById<View>(R.id.btnNavigate).setOnClickListener {
            val route = viewModel.route.value ?: return@setOnClickListener
            LocationUtils.launchGoogleMapsNavigation(
                this,
                route.pickupStation.lat,
                route.pickupStation.lon,
                route.dropoffStation.lat,
                route.dropoffStation.lon
            )
            startRideTimer()
        }

        // Stop ride timer
        findViewById<View>(R.id.btnStopRide).setOnClickListener {
            stopRideTimer()
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
        highlightGreen = MarkerFactory.createHighlightedDot(this, Color.parseColor("#4CAF50"))
        highlightOrange = MarkerFactory.createHighlightedDot(this, Color.parseColor("#FF9800"))
        highlightRed = MarkerFactory.createHighlightedDot(this, Color.parseColor("#E30613"))
        highlightGray = MarkerFactory.createHighlightedDot(this, Color.GRAY)
        dotPurple = MarkerFactory.createHighlightedDot(this, Color.parseColor("#9C27B0"))
        starIcon = createStarIcon()
    }

    // Radius circle overlays
    private var radiusCircle: com.google.android.gms.maps.model.Circle? = null
    private var destinationRadiusCircle: com.google.android.gms.maps.model.Circle? = null

    private fun initSettingsDrawer() {
        // Block radius controls
        findViewById<MaterialButton>(R.id.btnBlockMinus).setOnClickListener {
            viewModel.decrementBlocks()
        }
        findViewById<MaterialButton>(R.id.btnBlockPlus).setOnClickListener {
            viewModel.incrementBlocks()
        }

        // Bike type — 3-way segmented toggle
        val btnAll = findViewById<MaterialButton>(R.id.btnBikeAll)
        val btnMechanical = findViewById<MaterialButton>(R.id.btnMechanical)
        val btnElectric = findViewById<MaterialButton>(R.id.btnElectric)

        btnAll.setOnClickListener {
            viewModel.setBikeTypePreference(BikeTypePreference.BOTH)
        }
        btnMechanical.setOnClickListener {
            viewModel.setBikeTypePreference(BikeTypePreference.MECHANICAL_ONLY)
        }
        btnElectric.setOnClickListener {
            viewModel.setBikeTypePreference(BikeTypePreference.ELECTRIC_ONLY)
        }

        // Reset to defaults
        findViewById<MaterialButton>(R.id.btnResetDefaults).setOnClickListener {
            viewModel.resetToDefaults()
        }

        // Support button (hidden — set visibility to VISIBLE to enable)
        findViewById<MaterialButton>(R.id.btnSupportCoffee).setOnClickListener {
            // TODO: replace with your actual Buy Me a Coffee / Ko-fi URL
            val url = "https://buymeacoffee.com/bicilona"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (_: Exception) {}
        }

        // Setting help buttons
        findViewById<View>(R.id.btnHelpRadius).setOnClickListener {
            showSettingHelp(getString(R.string.search_radius), R.string.help_radius)
        }
        findViewById<View>(R.id.btnHelpBikeType).setOnClickListener {
            showSettingHelp(getString(R.string.bike_type), R.string.help_bike_type)
        }
        findViewById<View>(R.id.btnHelpTimer).setOnClickListener {
            showSettingHelp(getString(R.string.ride_timer), R.string.help_timer)
        }

        // Version label
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            findViewById<TextView>(R.id.tvVersion).text = "Bicilona v${pInfo.versionName}"
        } catch (_: Exception) { }

        // Language selector
        val langButtons = mapOf(
            "en" to findViewById<MaterialButton>(R.id.btnLangEn),
            "es" to findViewById<MaterialButton>(R.id.btnLangEs),
            "ca" to findViewById<MaterialButton>(R.id.btnLangCa)
        )
        val savedLang = getSharedPreferences("bicilona_prefs", MODE_PRIVATE)
            .getString("app_language", "en") ?: "en"
        updateLanguageButtons(langButtons, savedLang)

        langButtons.forEach { (lang, btn) ->
            btn.setOnClickListener {
                if (lang == savedLang) return@setOnClickListener
                setAppLocale(lang)
                getSharedPreferences("bicilona_prefs", MODE_PRIVATE)
                    .edit().putString("app_language", lang).apply()
                recreate()
            }
        }

        // Timer settings
        val tvWarning = findViewById<TextView>(R.id.tvWarningValue)
        val tvRedirect = findViewById<TextView>(R.id.tvRedirectValue)

        viewModel.warningAtMinutes.observe(this) { tvWarning.text = it.toString() }
        viewModel.redirectMinutes.observe(this) { tvRedirect.text = it.toString() }

        // Redirect toggle
        val switchRedirect = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchRedirect)
        val redirectContainer = findViewById<View>(R.id.redirectMinutesContainer)
        switchRedirect.isChecked = viewModel.redirectEnabled.value == true
        redirectContainer.visibility = if (switchRedirect.isChecked) View.VISIBLE else View.GONE

        viewModel.redirectEnabled.observe(this) { enabled ->
            switchRedirect.isChecked = enabled
            redirectContainer.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        switchRedirect.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setRedirectEnabled(isChecked)
        }

        findViewById<MaterialButton>(R.id.btnWarningMinus).setOnClickListener {
            viewModel.setWarningAtMinutes((viewModel.warningAtMinutes.value ?: 25) - 1)
        }
        findViewById<MaterialButton>(R.id.btnWarningPlus).setOnClickListener {
            viewModel.setWarningAtMinutes((viewModel.warningAtMinutes.value ?: 25) + 1)
        }
        findViewById<MaterialButton>(R.id.btnRedirectMinus).setOnClickListener {
            viewModel.setRedirectMinutes((viewModel.redirectMinutes.value ?: 29) - 1)
        }
        findViewById<MaterialButton>(R.id.btnRedirectPlus).setOnClickListener {
            viewModel.setRedirectMinutes((viewModel.redirectMinutes.value ?: 29) + 1)
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

        // Show favorites when field is tapped or gains focus
        etDestination.threshold = 0

        etDestination.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                suppressDropdown = false
                showFavoritesDropdown()
            }
        }

        etDestination.setOnClickListener {
            suppressDropdown = false
            showFavoritesDropdown()
        }

        // Text changed → autocomplete
        etDestination.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressDropdown) return
                val query = s?.toString()?.trim() ?: ""
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                if (query.length >= 2) {
                    // Show matching favorites immediately, then search for places
                    val filteredFavs = filterFavorites(query)
                    dropdownAdapter.setData(filteredFavs, currentPredictions)
                    searchRunnable = Runnable { searchPlaces(query) }
                    searchHandler.postDelayed(searchRunnable!!, 300)
                } else if (query.isEmpty() && currentFavorites.isNotEmpty()) {
                    dropdownAdapter.showFavoritesOnly(currentFavorites)
                    etDestination.showDropDown()
                } else if (query.length == 1) {
                    val filteredFavs = filterFavorites(query)
                    if (filteredFavs.isNotEmpty()) {
                        dropdownAdapter.showFavoritesOnly(filteredFavs)
                        etDestination.showDropDown()
                    }
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
        googleMap.uiSettings.isMyLocationButtonEnabled = false

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
                    "🅿️ ${station.docksAvailable}"

                // Capacity bar
                val capacityBar = view.findViewById<android.widget.ProgressBar>(R.id.capacityBar)
                val capacityLabel = view.findViewById<TextView>(R.id.tvCapacityLabel)
                if (station.capacity > 0) {
                    val fillPercent = (station.bikesAvailable * 100) / station.capacity
                    capacityBar.max = 100
                    capacityBar.progress = fillPercent
                    capacityLabel.text = getString(R.string.bikes_capacity, station.bikesAvailable, station.capacity)
                } else {
                    capacityBar.visibility = View.GONE
                    capacityLabel.visibility = View.GONE
                }

                // Stale data warning
                val tvStale = view.findViewById<TextView>(R.id.tvStaleWarning)
                val nowEpoch = System.currentTimeMillis() / 1000
                if (station.isStale(nowEpoch)) {
                    tvStale.visibility = View.VISIBLE
                    val minsAgo = station.lastReported?.let { (nowEpoch - it) / 60 } ?: 0
                    tvStale.text = getString(R.string.stale_warning, minsAgo.toInt())
                }

                return view
            }
        })

        // Long-press to set destination
        googleMap.setOnMapLongClickListener { latLng ->
            etDestination.setText("📍 ${String.format("%.4f, %.4f", latLng.latitude, latLng.longitude)}")
            setDestination(latLng)
        }

        // Tap on station marker → toggle pickup, select dropoff, or show info
        googleMap.setOnMarkerClickListener { marker ->
            val station = marker.tag as? BicilonaStation
            val favorite = marker.tag as? FavoritePlace

            if (favorite != null) {
                // Tapped a favorite star — show options
                showFavoriteOptionsDialog(favorite)
                true
            } else if (station != null && viewModel.route.value != null) {
                // Route is active — check if it's a nearby dropoff alternative
                val isNearbyDropoff = nearbyDropoffMarkers.any { it == marker }
                if (isNearbyDropoff) {
                    viewModel.selectDropoffStation(station)
                    true
                } else {
                    // Show info window for pickup/dropoff markers
                    false
                }
            } else if (station != null) {
                // No active route — toggle as custom pickup
                viewModel.togglePickupStation(station)
                marker.showInfoWindow()
                true
            } else {
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

        // Get initial location fast
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null && !hasInitialLocation) {
                hasInitialLocation = true
                val latLng = LatLng(location.latitude, location.longitude)
                viewModel.userLocation = latLng
                viewModel.loadStations()
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                updateRadiusCircle()
            } else if (!hasInitialLocation) {
                Log.w(TAG, "Last location is null, loading all stations")
                viewModel.loadStations()
            }
        }

        // Start continuous updates
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateDistanceMeters(15f)
            .setMinUpdateIntervalMillis(5_000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val latLng = LatLng(location.latitude, location.longitude)
                viewModel.userLocation = latLng
                updateRadiusCircle()

                if (!hasInitialLocation) {
                    hasInitialLocation = true
                    viewModel.loadStations()
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    // ════════════════════════════════════════
    // Observe ViewModel
    // ════════════════════════════════════════

    private fun observeViewModel() {
        viewModel.visibleStations.observe(this) { stations ->
            updateStationMarkers(stations)
            updateStationStats(stations)
            // Show empty state if no stations in radius (and we have a location)
            val showEmpty = stations.isEmpty() && viewModel.userLocation != null && viewModel.route.value == null
            emptyStateCard.visibility = if (showEmpty) View.VISIBLE else View.GONE
        }

        viewModel.nearestStation.observe(this) { station ->
            if (station != null && viewModel.selectedPickup.value == null) {
                tvCurrentLocation.text = station.name
                pulseOnStation(station)
            }
            // Re-render markers to highlight nearest station
            viewModel.visibleStations.value?.let { updateStationMarkers(it) }
        }

        viewModel.selectedPickup.observe(this) { station ->
            if (station != null) {
                tvCurrentLocation.text = getString(R.string.station_pin, station.name)
                btnResetPickup.visibility = View.VISIBLE
                pulseOnStation(station)
            } else {
                btnResetPickup.visibility = View.GONE
                val nearest = viewModel.nearestStation.value
                tvCurrentLocation.text = nearest?.name ?: getString(R.string.getting_nearest_station)
                if (nearest != null) {
                    pulseOnStation(nearest)
                } else {
                    pulseAnimator?.stop()
                    pulseAnimator = null
                }
            }
            // Re-render markers to highlight selected pickup
            viewModel.visibleStations.value?.let { updateStationMarkers(it) }
        }

        viewModel.route.observe(this) { route ->
            if (route != null) {
                val isReRoute = routePolylines.isNotEmpty()
                showRoute(route, fitCamera = !isReRoute)
                updateSaveFavoriteButton()
                updateDestinationRadiusCircle()
                emptyStateCard.visibility = View.GONE
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
            tvBlockDistance.text = getString(R.string.distance_approx, (blocks * MainViewModel.METERS_PER_BLOCK).toInt())
            updateRadiusCircle()
        }

        viewModel.bikeTypePreference.observe(this) { pref ->
            val btnAll = findViewById<MaterialButton>(R.id.btnBikeAll)
            val btnMech = findViewById<MaterialButton>(R.id.btnMechanical)
            val btnElec = findViewById<MaterialButton>(R.id.btnElectric)

            // Filled style for active, outlined for inactive
            val activeColor = Color.parseColor("#1A1A2E")
            val inactiveColor = Color.TRANSPARENT
            val activeText = Color.WHITE
            val inactiveText = Color.parseColor("#1A1A2E")

            btnAll.setBackgroundColor(if (pref == BikeTypePreference.BOTH) activeColor else inactiveColor)
            btnAll.setTextColor(if (pref == BikeTypePreference.BOTH) activeText else inactiveText)
            btnMech.setBackgroundColor(if (pref == BikeTypePreference.MECHANICAL_ONLY) activeColor else inactiveColor)
            btnMech.setTextColor(if (pref == BikeTypePreference.MECHANICAL_ONLY) activeText else inactiveText)
            btnElec.setBackgroundColor(if (pref == BikeTypePreference.ELECTRIC_ONLY) activeColor else inactiveColor)
            btnElec.setTextColor(if (pref == BikeTypePreference.ELECTRIC_ONLY) activeText else inactiveText)

            // Refresh markers with new coloring
            viewModel.visibleStations.value?.let { updateStationMarkers(it) }
        }

        viewModel.favorites.observe(this) { favs ->
            currentFavorites = favs
            updateSaveFavoriteButton()
            updateFavoriteMarkers()
        }

        viewModel.nearbyDropoffs.observe(this) { dropoffs ->
            updateNearbyDropoffMarkers(dropoffs)
        }
    }

    // ════════════════════════════════════════
    // Station markers
    // ════════════════════════════════════════

    private fun updateStationMarkers(stations: List<BicilonaStation>) {
        if (!::googleMap.isInitialized) return

        val currentIds = stations.map { it.stationId }.toSet()
        val selectedPickupId = viewModel.selectedPickup.value?.stationId
        val nearestStationId = viewModel.nearestStation.value?.stationId
        val highlightId = selectedPickupId ?: nearestStationId

        // Remove markers for stations no longer visible
        val toRemove = stationMarkers.keys - currentIds
        toRemove.forEach { id ->
            stationMarkers.remove(id)?.remove()
        }

        // Add or update markers
        stations.forEach { station ->
            val icon = when {
                station.stationId == highlightId -> highlightedDotForStation(station)
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

    private fun updateFavoriteMarkers() {
        if (!::googleMap.isInitialized) return

        favoriteMarkers.forEach { it.remove() }
        favoriteMarkers.clear()

        currentFavorites.forEach { fav ->
            val marker = googleMap.addMarker(
                MarkerOptions()
                    .position(LatLng(fav.lat, fav.lon))
                    .icon(starIcon)
                    .anchor(0.5f, 0.5f)
                    .zIndex(0.5f)
                    .title("⭐ ${fav.name}")
            )
            marker?.tag = fav
            if (marker != null) {
                favoriteMarkers.add(marker)
            }
        }
    }

    private fun createStarIcon(): BitmapDescriptor {
        val density = resources.displayMetrics.density
        val size = (20 * density).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD700")
            style = android.graphics.Paint.Style.FILL
        }
        val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B8860B")
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }

        val cx = size / 2f
        val cy = size / 2f
        val outerR = size / 2f - 1.5f * density
        val innerR = outerR * 0.4f
        val path = android.graphics.Path()
        for (i in 0 until 5) {
            val outerAngle = Math.toRadians((i * 72 - 90).toDouble())
            val innerAngle = Math.toRadians((i * 72 + 36 - 90).toDouble())
            val ox = cx + outerR * Math.cos(outerAngle).toFloat()
            val oy = cy + outerR * Math.sin(outerAngle).toFloat()
            val ix = cx + innerR * Math.cos(innerAngle).toFloat()
            val iy = cy + innerR * Math.sin(innerAngle).toFloat()
            if (i == 0) path.moveTo(ox, oy) else path.lineTo(ox, oy)
            path.lineTo(ix, iy)
        }
        path.close()
        canvas.drawPath(path, paint)
        canvas.drawPath(path, strokePaint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun updateNearbyDropoffMarkers(dropoffs: List<BicilonaStation>) {
        if (!::googleMap.isInitialized) return

        // Clear previous nearby dropoff markers
        nearbyDropoffMarkers.forEach { it.remove() }
        nearbyDropoffMarkers.clear()

        // Only show when a route is active
        if (viewModel.route.value == null) return

        val dropoffId = viewModel.route.value?.dropoffStation?.stationId

        dropoffs.forEach { station ->
            // Skip the currently selected dropoff (already shown as purple highlighted)
            if (station.stationId == dropoffId) return@forEach

            val icon = MarkerFactory.createDot(this, Color.parseColor("#9C27B0"), strokeColor = Color.WHITE)
            val marker = googleMap.addMarker(
                MarkerOptions()
                    .position(LatLng(station.lat, station.lon))
                    .icon(icon)
                    .anchor(0.5f, 0.5f)
                    .zIndex(2f)
                    .title(station.name)
            )
            marker?.tag = station
            if (marker != null) {
                nearbyDropoffMarkers.add(marker)
            }
        }
    }

    // ════════════════════════════════════════
    // Route display
    // ════════════════════════════════════════

    private fun showRoute(route: BicilonaRoute, fitCamera: Boolean = true) {
        clearRouteFromMap()
        stopRideTimer()

        val pickup = route.pickupStation
        val dropoff = route.dropoffStation

        // Pickup marker (availability color)
        pickupMarker = googleMap.addMarker(
            MarkerOptions()
                .position(LatLng(pickup.lat, pickup.lon))
                .icon(highlightedDotForStation(pickup))
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
        pulseOnStation(pickup)

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

        // Fit camera to route (only on initial route, not dropoff re-selection)
        if (fitCamera) {
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
        }

        // Update bottom sheet
        val walkDist = LocationUtils.formatDistance(route.walkToPickupMeters)
        val walkTime = route.walkToPickupDuration ?: "${LocationUtils.walkingMinutes(route.walkToPickupMeters)} min"
        val rideDist = LocationUtils.formatDistance(route.rideMeters)
        val rideTime = route.rideDuration ?: "${LocationUtils.cyclingMinutes(route.rideMeters)} min"
        val destDist = LocationUtils.formatDistance(route.walkToDestinationMeters)
        val destTime = route.walkToDestDuration ?: "${LocationUtils.walkingMinutes(route.walkToDestinationMeters)} min"

        // Total estimated time
        val walkMin = parseMinutes(walkTime)
        val rideMin = parseMinutes(rideTime)
        val destMin = parseMinutes(destTime)
        val totalMin = walkMin + rideMin + destMin
        tvTotalTime.text = if (totalMin > 0) "${totalMin} min" else "Calculating…"
        tvRouteSummary.text = getString(R.string.route_summary_fmt, walkTime, rideTime, destTime)

        tvPickupStation.text = pickup.name
        tvPickupInfo.text = "$walkDist · $walkTime · ${getString(R.string.bikes_capacity, viewModel.relevantBikeCount(pickup), pickup.capacity)}"
        tvRideInfo.text = getString(R.string.ride_info_fmt, rideDist, rideTime)
        tvDropoffStation.text = dropoff.name
        tvDropoffInfo.text = getString(R.string.docks_capacity, dropoff.docksAvailable, dropoff.capacity)
        tvDestWalkInfo.text = getString(R.string.dest_walk_fmt, destDist, destTime)

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
        nearbyDropoffMarkers.forEach { it.remove() }
        nearbyDropoffMarkers.clear()
        routePolylines.forEach { it.remove() }
        routePolylines.clear()
        pulseAnimator?.stop()
        pulseAnimator = null
        destinationRadiusCircle?.remove()
        destinationRadiusCircle = null
    }

    /**
     * Pulse on a station (nearest or manually selected pickup).
     * Safe to call before map is ready — will no-op.
     */
    private fun pulseOnStation(station: BicilonaStation) {
        if (!::googleMap.isInitialized) return
        pulseAnimator?.stop()
        pulseAnimator = PulseAnimator(this, googleMap)
        pulseAnimator?.start(LatLng(station.lat, station.lon), Color.parseColor("#2196F3"))
    }

    private fun cancelRoute() {
        stopRideTimer()
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
        if (!Places.isInitialized()) {
            Log.w(TAG, "Places SDK not initialized, skipping search")
            return
        }

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
                Log.d(TAG, "Places search for '$query': ${response.autocompletePredictions.size} results")
                currentPredictions = response.autocompletePredictions
                val filteredFavs = filterFavorites(query)
                dropdownAdapter.setData(filteredFavs, currentPredictions)
                if (dropdownAdapter.count > 0 && !suppressDropdown) {
                    etDestination.showDropDown()
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Place autocomplete failed for '$query'", e)
            }
    }

    private fun fetchPlaceAndRoute(prediction: AutocompletePrediction) {
        if (!Places.isInitialized()) return

        val placesClient = Places.createClient(this)
        val request = FetchPlaceRequest.newInstance(
            prediction.placeId,
            listOf(Place.Field.LOCATION, Place.Field.DISPLAY_NAME)
        )

        placesClient.fetchPlace(request)
            .addOnSuccessListener { response ->
                val latLng = response.place.location
                if (latLng != null) {
                    etDestination.setText(prediction.getPrimaryText(null))
                    setDestination(latLng)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Fetch place failed", e)
                Toast.makeText(this, getString(R.string.could_not_get_place), Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, getString(R.string.no_results_found), Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun setDestination(latLng: LatLng) {
        suppressDropdown = true
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        etDestination.dismissDropDown()
        etDestination.clearFocus()
        hideKeyboard()
        viewModel.findRoute(latLng)
        updateSaveFavoriteButton()
    }

    // ════════════════════════════════════════
    // Favorites dialog
    // ════════════════════════════════════════

    private fun showFavoriteOptionsDialog(favorite: FavoritePlace) {
        MaterialAlertDialogBuilder(this)
            .setTitle("⭐ ${favorite.name}")
            .setItems(arrayOf("🗺️ ${getString(R.string.set_as_destination)}", "🗑️ ${getString(R.string.remove_from_favorites)}")) { _, which ->
                when (which) {
                    0 -> {
                        val latLng = LatLng(favorite.lat, favorite.lon)
                        etDestination.setText(favorite.name)
                        setDestination(latLng)
                    }
                    1 -> {
                        viewModel.deleteFavorite(favorite)
                        Toast.makeText(this, getString(R.string.removed_favorite_toast, favorite.name), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

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
                Toast.makeText(this, getString(R.string.saved_favorite_toast, name), Toast.LENGTH_SHORT).show()
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
            if (clazz.isInstance(child)) result.add(clazz.cast(child)!!)
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

    private fun filterFavorites(query: String): List<FavoritePlace> {
        if (query.isBlank()) return currentFavorites
        return currentFavorites.filter { it.name.contains(query, ignoreCase = true) }
    }

    /**
     * Extract numeric minutes from duration strings like "4 min", "1 hour 2 mins", etc.
     */
    private fun parseMinutes(duration: String): Int {
        var total = 0
        val hourMatch = Regex("(\\d+)\\s*hour").find(duration)
        if (hourMatch != null) total += hourMatch.groupValues[1].toInt() * 60
        val minMatch = Regex("(\\d+)\\s*min").find(duration)
        if (minMatch != null) total += minMatch.groupValues[1].toInt()
        return total
    }

    private fun updateStationStats(stations: List<BicilonaStation>) {
        val tvStats = findViewById<TextView>(R.id.tvStationStats)
        if (stations.isEmpty()) {
            tvStats.text = getString(R.string.no_stations_in_range)
            return
        }
        val totalBikes = stations.sumOf { it.bikesAvailable }
        val totalMech = stations.sumOf { it.mechanicalBikes }
        val totalElec = stations.sumOf { it.electricBikes }
        val totalDocks = stations.sumOf { it.docksAvailable }
        tvStats.text = getString(R.string.station_stats_fmt, stations.size, totalBikes, totalMech, totalElec, totalDocks)
    }

    private fun highlightedDotForStation(station: com.bicilona.data.model.BicilonaStation): BitmapDescriptor {
        return when {
            !station.isOperational -> highlightGray
            viewModel.relevantBikeCount(station) == 0 -> highlightRed
            viewModel.relevantBikeCount(station) <= 3 -> highlightOrange
            else -> highlightGreen
        }
    }

    private fun updateRadiusCircle() {
        if (!::googleMap.isInitialized) return
        val loc = viewModel.userLocation ?: return
        val radius = viewModel.radiusMeters

        radiusCircle?.remove()
        radiusCircle = googleMap.addCircle(
            com.google.android.gms.maps.model.CircleOptions()
                .center(loc)
                .radius(radius)
                .strokeWidth(2f)
                .strokeColor(Color.parseColor("#442196F3"))
                .fillColor(Color.parseColor("#112196F3"))
        )

        updateDestinationRadiusCircle()
    }

    private fun updateDestinationRadiusCircle() {
        if (!::googleMap.isInitialized) return
        destinationRadiusCircle?.remove()
        destinationRadiusCircle = null

        val dest = viewModel.currentDestination ?: return
        val radius = viewModel.radiusMeters

        destinationRadiusCircle = googleMap.addCircle(
            com.google.android.gms.maps.model.CircleOptions()
                .center(dest)
                .radius(radius)
                .strokeWidth(2f)
                .strokeColor(Color.parseColor("#449C27B0"))
                .fillColor(Color.parseColor("#119C27B0"))
        )
    }

    // ════════════════════════════════════════
    // Ride timer
    // ════════════════════════════════════════

    private fun startRideTimer() {
        val limitMinutes = viewModel.timeLimitMinutes
        val warningAtMinutes = viewModel.warningAtMinutes.value ?: 25
        val warningRemaining = limitMinutes - warningAtMinutes  // e.g. 30 - 25 = 5 min remaining
        val redirectEnabled = viewModel.redirectEnabled.value == true
        val redirectAtMin = viewModel.redirectMinutes.value ?: 29
        val redirectRemaining = if (redirectEnabled) (limitMinutes - redirectAtMin) else -1

        hasWarned = false
        hasRedirected = false

        // Show timer UI, hide navigate button
        findViewById<View>(R.id.btnNavigate).visibility = View.GONE
        findViewById<View>(R.id.rideTimerContainer).visibility = View.VISIBLE
        findViewById<View>(R.id.peekTimerContainer).visibility = View.VISIBLE

        val tvCountdown = findViewById<TextView>(R.id.tvTimerCountdown)
        val tvPeekCountdown = findViewById<TextView>(R.id.tvPeekTimerCountdown)
        val tvLabel = findViewById<TextView>(R.id.tvTimerLabel)
        tvCountdown.text = String.format("%02d:%02d", limitMinutes, 0)
        tvPeekCountdown.text = String.format("%02d:%02d", limitMinutes, 0)
        tvCountdown.setTextColor(Color.parseColor("#4CAF50"))
        tvPeekCountdown.setTextColor(Color.parseColor("#4CAF50"))
        tvLabel.text = getString(R.string.time_remaining)

        // Request notification permission (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
            }
        }

        // Set up direct callbacks from the service (same process)
        RideTimerService.onTick = { secsLeft ->
            val mins = secsLeft / 60
            val secs = secsLeft % 60
            val timeStr = String.format("%02d:%02d", mins, secs)
            tvCountdown.text = timeStr
            tvPeekCountdown.text = timeStr

            val warningThreshold = warningRemaining * 60
            val redirectThreshold = if (redirectEnabled) redirectRemaining * 60 else -1

            val color = when {
                redirectThreshold > 0 && secsLeft <= redirectThreshold -> {
                    tvLabel.text = getString(R.string.return_bike_now)
                    Color.parseColor("#E30613")
                }
                secsLeft <= warningThreshold -> {
                    tvLabel.text = getString(R.string.min_left_find_station, mins)
                    Color.parseColor("#FF9800")
                }
                else -> null
            }
            if (color != null) {
                tvCountdown.setTextColor(color)
                tvPeekCountdown.setTextColor(color)
            }
        }

        RideTimerService.onWarning = {
            val remaining = limitMinutes - warningAtMinutes
            Toast.makeText(this, getString(R.string.min_left_find_station, remaining), Toast.LENGTH_LONG).show()
        }

        RideTimerService.onRedirect = {
            // Navigation is launched by the service (from foreground context).
        }

        RideTimerService.findRedirectStation = {
            viewModel.findNearestDropoffToUser()?.let { station ->
                Pair(station.lat, station.lon)
            }
        }

        RideTimerService.onFinished = {
            tvCountdown.text = "00:00"
            tvPeekCountdown.text = "00:00"
            tvCountdown.setTextColor(Color.parseColor("#E30613"))
            tvPeekCountdown.setTextColor(Color.parseColor("#E30613"))
            tvLabel.text = getString(R.string.times_up)
            Toast.makeText(this, getString(R.string.free_ride_exceeded), Toast.LENGTH_LONG).show()
        }

        // Start foreground service
        val serviceIntent = Intent(this, RideTimerService::class.java).apply {
            action = RideTimerService.ACTION_START
            putExtra(RideTimerService.EXTRA_TIME_LIMIT_MINUTES, limitMinutes)
            putExtra(RideTimerService.EXTRA_WARNING_MINUTES, warningRemaining)
            putExtra(RideTimerService.EXTRA_REDIRECT_MINUTES, redirectRemaining)
        }
        startForegroundService(serviceIntent)

        // Expand bottom sheet to show timer
        bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun stopRideTimer() {
        // Stop the foreground service
        val serviceIntent = Intent(this, RideTimerService::class.java).apply {
            action = RideTimerService.ACTION_STOP
        }
        startService(serviceIntent)

        RideTimerService.clearCallbacks()
        hasWarned = false
        hasRedirected = false

        // Hide timer UI, show navigate button
        findViewById<View>(R.id.rideTimerContainer).visibility = View.GONE
        findViewById<View>(R.id.peekTimerContainer).visibility = View.GONE
        findViewById<View>(R.id.btnNavigate).visibility = View.VISIBLE
    }

    private fun showHelpDialog() {
        val message = android.text.Html.fromHtml(
            getString(R.string.help_text),
            android.text.Html.FROM_HTML_MODE_COMPACT
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.how_bicilona_works))
            .setMessage(message)
            .setPositiveButton(getString(R.string.got_it), null)
            .show()
    }

    private fun showSettingHelp(title: String, messageResId: Int) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(getString(messageResId))
            .setPositiveButton(getString(R.string.got_it), null)
            .show()
    }

    private fun updateLanguageButtons(buttons: Map<String, MaterialButton>, activeLang: String) {
        buttons.forEach { (lang, btn) ->
            if (lang == activeLang) {
                btn.setBackgroundColor(android.graphics.Color.parseColor("#4285F4"))
                btn.setTextColor(android.graphics.Color.WHITE)
            } else {
                btn.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                btn.setTextColor(android.graphics.Color.parseColor("#1A1A2E"))
            }
        }
    }

    private fun setAppLocale(langCode: String) {
        // No-op — locale is applied in attachBaseContext on recreate()
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = newBase.getSharedPreferences("bicilona_prefs", MODE_PRIVATE)
        val lang = prefs.getString("app_language", "en") ?: "en"
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    private fun showFavoritesDropdown() {
        if (suppressDropdown) return
        val query = etDestination.text?.toString()?.trim() ?: ""
        val filtered = filterFavorites(query)
        if (filtered.isNotEmpty()) {
            dropdownAdapter.showFavoritesOnly(filtered)
            etDestination.post { etDestination.showDropDown() }
        }
    }

    private fun isCurrentDestinationFavorite(): Boolean {
        val dest = viewModel.currentDestination ?: return false
        return currentFavorites.any { fav ->
            Math.abs(fav.lat - dest.latitude) < 0.0001 && Math.abs(fav.lon - dest.longitude) < 0.0001
        }
    }

    private fun updateSaveFavoriteButton() {
        val btn = findViewById<MaterialButton>(R.id.btnSaveFavorite)
        if (isCurrentDestinationFavorite()) {
            btn.text = getString(R.string.saved_favorite)
            btn.alpha = 0.5f
            btn.isClickable = false
        } else {
            btn.text = getString(R.string.save_favorite)
            btn.alpha = 1f
            btn.isClickable = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        RideTimerService.clearCallbacks()
        pulseAnimator?.stop()
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
    }
}
