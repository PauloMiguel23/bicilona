package com.bicilona.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.bicilona.R
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng

class MainActivity : AppCompatActivity() {

    private lateinit var googleMap: GoogleMap
    private var destinationSelected: Boolean = false
    
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync { map ->
            googleMap = map
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(41.3874, 2.1686), 13f))
            enableMyLocation()
            googleMap.setOnMapLoadedCallback {
                android.util.Log.i("MainActivity", "Map loaded. Initializing station loading...")
                loadClosestStations()
            }
            checkDestinationState()
        }
    }

    private fun enableMyLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            googleMap.isMyLocationEnabled = true
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    private fun loadClosestStations() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val fusedLocationProviderClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
            fusedLocationProviderClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    Toast.makeText(this, "Loading stations near: ${location.latitude}, ${location.longitude}", Toast.LENGTH_SHORT).show()
                    viewModel.userLocation = com.google.android.gms.maps.model.LatLng(location.latitude, location.longitude)
                    viewModel.loadStations()

                    viewModel.visibleStations.observe(this) { stations ->
                        googleMap.clear() // Clear existing markers
                        stations.forEach { station ->
                            googleMap.addMarker(
                                com.google.android.gms.maps.model.MarkerOptions()
                                    .position(com.google.android.gms.maps.model.LatLng(station.lat, station.lon))
                                    .title(station.name)
                            )
                        }
                    }
                } else {
                    Toast.makeText(this, "Failed to get current location.", Toast.LENGTH_SHORT).show()
                    android.util.Log.e("MainActivity", "Failed to retrieve last known location.")
                }
            }
        }
    }

    private fun checkDestinationState() {
        if (!destinationSelected) {
            Toast.makeText(this, "No destination selected. Showing map instead.", Toast.LENGTH_SHORT).show()
            // Logic to ensure only the map screen is displayed and route UI is hidden
        }
    }
}