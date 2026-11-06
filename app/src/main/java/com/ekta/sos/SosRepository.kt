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

    /**
     * Publishes her own SOS. Uses the alert id as the document id, so the same alert
     * arriving through a mesh gateway can't be created twice. If she's offline the
     * Firestore SDK keeps the write and sends it as soon as there's internet.
     */
    fun publishAlert(context: Context, id: String, loc: Location, cancelHash: String, duressHash: String, locHash: String) =
        FirebaseFirestore.getInstance().collection(COLLECTION).document(id).set(
            mapOf(
                "lat" to loc.latitude,
                "lng" to loc.longitude,
                "accuracy" to loc.accuracy.toDouble(),
                "cell" to cellOf(loc.latitude, loc.longitude),
                "senderId" to deviceId(context),
                "createdAt" to Timestamp.now(),
                "sentAt" to System.currentTimeMillis(),
                "via" to "direct",
                "hops" to 0,
                "status" to AlertStatus.ACTIVE,
                "hashC" to cancelHash,
                "hashD" to duressHash,
                "hashL" to locHash,
                "statusAt" to loc.time,
            ),
        )

    /** A gateway uploading someone else's SOS that reached it over the mesh. */
    suspend fun publishRelayed(msg: MeshMessage, gatewayId: String) {
        FirebaseFirestore.getInstance().collection(COLLECTION).document(msg.id).set(
            mapOf(
                "lat" to msg.lat,
                "lng" to msg.lng,
                "accuracy" to msg.accuracy,
                "cell" to cellOf(msg.lat, msg.lng),
                "senderId" to msg.sender,
                "createdAt" to Timestamp.now(), // when it reached the internet
                "sentAt" to msg.sentAt,         // when she pressed SOS
                "via" to "mesh",
                "hops" to msg.hops,
                "gateway" to gatewayId,
                "status" to AlertStatus.ACTIVE,
                "hashC" to msg.cancelHash,
                "hashD" to msg.duressHash,
                "hashL" to msg.locHash,
                "statusAt" to msg.sentAt,
            ),
        ).await()
    }

    /**
     * Cancel / duress, proven with the secret key (the server checks its hash).
     * Works offline too: Firestore keeps the write and sends it once online.
     */
    fun updateStatus(id: String, status: String, key: String, lat: Double, lng: Double, accuracy: Double, at: Long) =
        FirebaseFirestore.getInstance().collection(COLLECTION).document(id).update(
            mapOf(
                "status" to status,
                "statusKey" to key,
                "statusAt" to at,
                "lat" to lat,
                "lng" to lng,
                "accuracy" to accuracy,
            ),
        )

    /**
     * Her new live location while the SOS is active, proven with the location key
     * (the server checks its hash). Older points can't overwrite newer ones.
     */
    fun updateLocation(id: String, key: String, lat: Double, lng: Double, accuracy: Double, at: Long) =
        FirebaseFirestore.getInstance().collection(COLLECTION).document(id).update(
            mapOf("lat" to lat, "lng" to lng, "accuracy" to accuracy, "statusAt" to at, "locKey" to key),
        )

    /** Later live-location points while in duress (status stays "duress"). */
    fun updateDuressLocation(id: String, lat: Double, lng: Double, accuracy: Double, at: Long) =
        FirebaseFirestore.getInstance().collection(COLLECTION).document(id).update(
            mapOf("lat" to lat, "lng" to lng, "accuracy" to accuracy, "statusAt" to at),
        )

    /** Who uploaded alert [id], if it's online already (null if unknown/offline). */
    suspend fun gatewayOf(id: String): String? = try {
        FirebaseFirestore.getInstance().collection(COLLECTION).document(id)
            .get(com.google.firebase.firestore.Source.SERVER).await().getString("gateway")
    } catch (_: Exception) {
        null
    }
}
