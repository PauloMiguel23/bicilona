package com.bicilona.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Transparent, no-UI activity that wakes the screen, shows over the lock screen,
 * launches Google Maps navigation to the given coordinates, and finishes itself.
 *
 * Used by RideTimerService to redirect the user to the nearest station even when
 * the phone is locked and in a pocket.
 */
class RedirectActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LAT = "redirect_lat"
        const val EXTRA_LNG = "redirect_lng"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake screen & show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)

        if (lat != 0.0 && lng != 0.0) {
            launchNavigation(lat, lng)
        }

        finish()
    }

    private fun launchNavigation(lat: Double, lng: Double) {
        val uri = Uri.parse("google.navigation:q=$lat,$lng&mode=b")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Fallback: directions URL
            val fallback = Uri.parse(
                "https://www.google.com/maps/dir/?api=1" +
                "&destination=$lat,$lng&travelmode=bicycling"
            )
            try {
                startActivity(Intent(Intent.ACTION_VIEW, fallback).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {}
        }
    }
}
