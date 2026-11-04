package com.ekta.sos

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.UUID

object SosRepository {
    const val COLLECTION = "alerts"

    fun isFirebaseConfigured(context: Context) = FirebaseApp.getApps(context).isNotEmpty()

    /** Random per-install id, so a phone never alerts itself. */
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("ekta", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    @SuppressLint("MissingPermission") // caller checks ACCESS_FINE_LOCATION
    suspend fun currentLocation(context: Context): Location? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        return client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            ?: client.lastLocation.await()
    }

    /** Publishes an SOS at [loc] so nearby phones get alerted. Throws on failure. */
    suspend fun publishAlert(context: Context, loc: Location) {
        val alert = mapOf(
            "lat" to loc.latitude,
            "lng" to loc.longitude,
            "accuracy" to loc.accuracy.toDouble(),
            "cell" to cellOf(loc.latitude, loc.longitude),
            "senderId" to deviceId(context),
            "createdAt" to Timestamp.now(),
        )
        FirebaseFirestore.getInstance().collection(COLLECTION).add(alert).await()
    }
}
