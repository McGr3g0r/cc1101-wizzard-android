/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Minimal location access using the framework [LocationManager] (no Play
 * Services dependency). Returns the freshest last-known fix across the GPS and
 * network providers. Requires ACCESS_FINE/COARSE_LOCATION to be granted.
 */
class LocationHelper(private val context: Context) {

    data class Fix(val lat: Double, val lon: Double, val timeMs: Long, val provider: String, val accuracyM: Float?)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Best available last-known fix, or null if none / no permission. */
    fun lastKnown(): Fix? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        try {
            for (provider in lm.getProviders(true)) {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.time > best!!.time) best = loc
            }
        } catch (_: SecurityException) {
            return null
        }
        val b = best ?: return null
        return Fix(b.latitude, b.longitude, b.time, b.provider ?: "?",
            if (b.hasAccuracy()) b.accuracy else null)
    }
}
