package com.dschat.app.agent.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Shared one-shot location fix — used by get_location, get_weather, and the weather monitor. */
object LocationUtil {

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Last-known (GPS→NETWORK→PASSIVE) then a single live fix (≤[timeoutMs]); null if unavailable. */
    suspend fun currentLocation(ctx: Context, timeoutMs: Long = 10_000L): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                ?: requestSingle(lm, timeoutMs)
        } catch (e: SecurityException) {
            null
        }
    }

    private suspend fun requestSingle(lm: LocationManager, timeoutMs: Long): Location? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val provider = runCatching {
                    lm.getBestProvider(Criteria().apply {
                        accuracy = Criteria.ACCURACY_COARSE
                        powerRequirement = Criteria.POWER_LOW
                    }, true)
                }.getOrNull() ?: LocationManager.NETWORK_PROVIDER
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) { if (cont.isActive) cont.resume(l) }
                    override fun onProviderDisabled(p: String) {}
                    override fun onProviderEnabled(p: String) {}
                    @Deprecated("required by older API levels")
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                }
                try {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            }
        }
}
