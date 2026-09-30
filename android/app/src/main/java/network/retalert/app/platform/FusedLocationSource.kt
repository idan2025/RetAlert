package network.retalert.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.location.Location
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import dagger.hilt.android.qualifiers.ApplicationContext
import network.retalert.domain.Fix
import network.retalert.domain.FixSource
import javax.inject.Inject

/** Android [FixSource] backed by Play Services FusedLocationProvider. Returns a
 *  fresh high-accuracy fix (blocking; call off the main thread — [GeoTracker]
 *  already runs its share loop on a daemon thread). Falls back to the last known
 *  location if a current fix is unavailable. Null when the location permission is
 *  not granted.
 *
 *  Parity with `geo_tracker.py` `AndroidFixSource` (platform build step). */
class FusedLocationSource @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : FixSource {

    override val name: String = "android"

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(ctx) }

    override fun getFix(): Fix? {
        if (!hasPermission()) return null
        return runCatching {
            val req = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .build()
            val loc: Location? = Tasks.await(fused.getCurrentLocation(req, null))
                ?: Tasks.await(fused.lastLocation)
            loc?.toFix()
        }.getOrNull()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun Location.toFix(): Fix = Fix(
        lat = latitude,
        lon = longitude,
        accuracy = if (hasAccuracy()) accuracy.toDouble() else null,
        altitude = if (hasAltitude()) altitude else null,
        source = "android",
    )
}