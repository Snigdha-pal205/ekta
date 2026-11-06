package com.ekta.sos

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Her exact position for the whole SOS. GPS stays on (Google's fused location AND the
 * phone's raw GPS chip, so it also works without Play services or network), and the
 * best fix so far is always available in [best]. GPS needs no internet or signal.
 */
object LiveLocation {
    /** A fix this old or newer counts as "where she is now". */
    const val FRESH_MS = 60_000L

    private val _best = MutableStateFlow<Location?>(null)
    /** Freshest good fix since tracking started (null = none yet). */
    val best: StateFlow<Location?> = _best

    private var running = false
    private var fusedCallback: LocationCallback? = null
    private var gpsListener: LocationListener? = null

    fun isGpsOn(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /** Start tracking (no-op if already running). */
    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        val app = context.applicationContext
        if (running) return
        running = true
        _best.value = null

        // A recent fix the phone already has makes the first alert instant.
        seedFromRecent(app)

        try {
            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.locations.forEach(::offer)
                }
            }
            LocationServices.getFusedLocationProviderClient(app).requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000)
                    .setMinUpdateIntervalMillis(1_000)
                    .setGranularity(Granularity.GRANULARITY_FINE)
                    .setWaitForAccurateLocation(false)
                    .build(),
                cb, Looper.getMainLooper(),
            )
            fusedCallback = cb
        } catch (_: Exception) {
        }

        // Raw GPS too: on some phones fused prefers Wi-Fi/cell positions (tens to
        // hundreds of metres off). The GPS chip gives a few metres outdoors.
        try {
            val lm = app.getSystemService(LocationManager::class.java)
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                val l = LocationListener { offer(it) }
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, l, Looper.getMainLooper())
                gpsListener = l
            }
        } catch (_: Exception) {
        }
    }

    /** GPS was switched on after tracking started: attach the raw GPS chip now too. */
    @SuppressLint("MissingPermission")
    fun ensureGps(context: Context) {
        if (!running || gpsListener != null) return
        try {
            val lm = context.applicationContext.getSystemService(LocationManager::class.java)
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                val l = LocationListener { offer(it) }
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, l, Looper.getMainLooper())
                gpsListener = l
            }
        } catch (_: Exception) {
        }
    }

    fun stop(context: Context) {
        if (!running) return
        running = false
        val app = context.applicationContext
        fusedCallback?.let {
            try { LocationServices.getFusedLocationProviderClient(app).removeLocationUpdates(it) } catch (_: Exception) {}
        }
        gpsListener?.let {
            try { app.getSystemService(LocationManager::class.java).removeUpdates(it) } catch (_: Exception) {}
        }
        fusedCallback = null
        gpsListener = null
    }

    /** First fix that is fresh (≤ [FRESH_MS] old), or null after [timeoutMs]. */
    suspend fun awaitFresh(timeoutMs: Long): Location? = withTimeoutOrNull(timeoutMs) {
        best.filterNotNull().first { ageMs(it) <= FRESH_MS }
    }

    /** First fix of any kind (keeps waiting while GPS searches). */
    suspend fun awaitAny(): Location = best.filterNotNull().first()

    /** Last position the phone knows, however old (only used if GPS finds nothing). */
    @SuppressLint("MissingPermission")
    suspend fun lastKnown(context: Context): Location? {
        val app = context.applicationContext
        val fused = try {
            LocationServices.getFusedLocationProviderClient(app).lastLocation.await()
        } catch (_: Exception) {
            null
        }
        val lm = app.getSystemService(LocationManager::class.java)
        val raw = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
        return (raw + listOfNotNull(fused)).maxByOrNull { it.time }
    }

    fun ageMs(loc: Location) = System.currentTimeMillis() - loc.time

    @SuppressLint("MissingPermission")
    private fun seedFromRecent(app: Context) {
        try {
            LocationServices.getFusedLocationProviderClient(app).lastLocation.addOnSuccessListener { l ->
                if (l != null && ageMs(l) <= 30_000 && l.accuracy <= 50f) offer(l)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Keep the better of the current best and [loc]: a newer fix wins unless it is
     * much less accurate; a much more accurate fix wins even if slightly older.
     */
    @Synchronized
    fun offer(loc: Location) {
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return
        val cur = _best.value
        if (cur == null) {
            _best.value = loc
            return
        }
        val newer = loc.time - cur.time
        val better = when {
            newer > 30_000 -> true                                  // current one is stale
            newer >= 0 && loc.accuracy <= cur.accuracy + 10f -> true // newer, about as good
            loc.accuracy < cur.accuracy * 0.5f && newer > -10_000 -> true // far more accurate
            else -> false
        }
        if (better) _best.value = loc
    }
}
