package com.ekta.sos

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Runs in the background while the app is "on duty". It keeps track of where this
 * phone is, listens for SOS alerts in the surrounding grid cells, and raises a loud
 * notification for any alert sent within 200 m. Only the sender's location is ever
 * uploaded; listeners do the distance check locally.
 */
class AlertListenerService : Service() {

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var registration: ListenerRegistration? = null
    private var watchedCells: List<String> = emptyList()
    private var lastLocation: Location? = null
    private val seen = HashSet<String>()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onMoved)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels(this)
        try {
            ServiceCompat.startForeground(
                this, ONGOING_ID, ongoingNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            // Not allowed to run right now (e.g. location permission was revoked).
            stopSelf()
            return START_NOT_STICKY
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000)
            .setMinUpdateIntervalMillis(10_000)
            .build()
        fused.requestLocationUpdates(request, locationCallback, mainLooper)
        // Get a precise fix right away, so listening starts even where
        // low-power (Wi-Fi/cell) location isn't available.
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { it?.let(::onMoved) }
        return START_STICKY
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(locationCallback)
        registration?.remove()
        super.onDestroy()
    }

    private fun onMoved(location: Location) {
        lastLocation = location
        val cells = cellsAround(location.latitude, location.longitude)
        if (cells == watchedCells) return
        watchedCells = cells
        registration?.remove()
        registration = FirebaseFirestore.getInstance()
            .collection(SosRepository.COLLECTION)
            .whereIn("cell", cells)
            .addSnapshotListener { snap, _ ->
                snap?.documentChanges
                    ?.filter { it.type == DocumentChange.Type.ADDED }
                    ?.forEach { handleAlert(it) }
            }
    }

    @SuppressLint("MissingPermission")
    private fun handleAlert(change: DocumentChange) {
        val doc = change.document
        if (!seen.add(doc.id)) return
        if (doc.getString("senderId") == SosRepository.deviceId(this)) return
        val created = doc.getTimestamp("createdAt")?.toDate()?.time ?: return
        if (System.currentTimeMillis() - created > ALERT_MAX_AGE_MS) return
        val lat = doc.getDouble("lat") ?: return
        val lng = doc.getDouble("lng") ?: return

        val known = lastLocation
        val fresh = known != null &&
            System.currentTimeMillis() - known.time < FRESH_LOCATION_MS
        if (fresh) {
            checkDistance(known!!, lat, lng, doc.id)
        } else {
            // Our position is old or unknown: get a precise fix before deciding.
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    val me = loc ?: known ?: return@addOnSuccessListener
                    lastLocation = me
                    checkDistance(me, lat, lng, doc.id)
                }
        }
    }

    private fun checkDistance(me: Location, lat: Double, lng: Double, id: String) {
        val result = FloatArray(1)
        Location.distanceBetween(me.latitude, me.longitude, lat, lng, result)
        val distance = result[0]
        if (distance <= ALERT_RADIUS_METERS) showSosNotification(lat, lng, distance, id)
    }

    private fun showSosNotification(lat: Double, lng: Double, distance: Float, id: String) {
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(SOS)"))
        val pending = PendingIntent.getActivity(
            this, id.hashCode(), maps,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, SOS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sos)
            .setContentTitle("SOS nearby!")
            .setContentText("Someone ${distance.toInt()} m from you needs help. Tap to see where.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(0xFFE84A8A.toInt())
            .setVibrate(longArrayOf(0, 600, 300, 600, 300, 600))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(id.hashCode(), n)
    }

    private fun ongoingNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sos)
            .setContentTitle("Ekta is listening")
            .setContentText("You'll be alerted if someone within 200 m needs help.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val ONGOING_ID = 1
        private const val FRESH_LOCATION_MS = 2 * 60 * 1000L
        private const val ONGOING_CHANNEL = "ongoing"
        private const val SOS_CHANNEL = "sos_alerts"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, AlertListenerService::class.java),
                )
            } catch (_: Exception) {
                // Background start not allowed; it'll start next time the app is opened.
            }
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(ONGOING_CHANNEL, "Listening status", NotificationManager.IMPORTANCE_MIN),
            )
            nm.createNotificationChannel(
                NotificationChannel(SOS_CHANNEL, "SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alerts from people within 200 m who pressed SOS"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                    setBypassDnd(true)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                },
            )
        }
    }
}
