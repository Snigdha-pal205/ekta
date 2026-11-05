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
import android.provider.Settings
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
 * full-screen alert for any SOS sent within 500 m. Only the sender's location is ever
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

    private var listening = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshFix() }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels(this)
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (android.os.Build.VERSION.SDK_INT >= 29 && MeshManager.hasPermissions(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        try {
            ServiceCompat.startForeground(this, ONGOING_ID, ongoingNotification(), types)
        } catch (e: Exception) {
            // Not allowed to run right now (e.g. location permission was revoked).
            stopSelf()
            return START_NOT_STICKY
        }
        // (Re)join the mesh every start, e.g. after Bluetooth permission was just granted.
        Net.init(this)
        Gateway.init(this)
        MeshManager.start(this, ::onMeshMessage)
        if (listening) return START_STICKY
        listening = true
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000)
            .setMinUpdateIntervalMillis(10_000)
            .build()
        fused.requestLocationUpdates(request, locationCallback, mainLooper)
        refreshFix()
        return START_STICKY
    }

    /**
     * Gets a precise fix now, and keeps doing so: every 30 s until we have one (right
     * after a restart Android may not give location for a while), then every 5 min if
     * low-power updates have gone quiet. Without a position we can't watch for alerts.
     */
    @SuppressLint("MissingPermission")
    private fun refreshFix() {
        handler.removeCallbacks(refreshRunnable)
        val last = lastLocation
        if (last != null && System.currentTimeMillis() - last.time < REFRESH_EVERY_MS) {
            handler.postDelayed(refreshRunnable, REFRESH_EVERY_MS)
            return
        }
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnCompleteListener { task ->
                    val loc = if (task.isSuccessful) task.result else null
                    if (loc != null) {
                        onMoved(loc)
                    } else if (lastLocation == null) {
                        // No fresh fix yet (common right after a restart): start watching
                        // from the phone's last known position meanwhile.
                        fused.lastLocation.addOnSuccessListener { it?.let(::onMoved) }
                    }
                    handler.postDelayed(
                        refreshRunnable, if (lastLocation == null) RETRY_EVERY_MS else REFRESH_EVERY_MS,
                    )
                }
        } catch (_: Exception) {
            handler.postDelayed(refreshRunnable, RETRY_EVERY_MS)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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

    private fun checkDistance(me: Location, lat: Double, lng: Double, id: String, hops: Int = -1) {
        val result = FloatArray(1)
        Location.distanceBetween(me.latitude, me.longitude, lat, lng, result)
        val distance = result[0]
        if (distance <= ALERT_RADIUS_METERS) showSosNotification(lat, lng, distance, id, hops)
    }

    /** Something arrived over Bluetooth / Wi-Fi Direct from a nearby Ekta phone. */
    private fun onMeshMessage(msg: MeshMessage) {
        when (msg.type) {
            MeshMessage.TYPE_ACK -> SosSession.onAck(msg)
            MeshMessage.TYPE_SOS -> {
                if (msg.sender == SosRepository.deviceId(this)) return
                if (System.currentTimeMillis() - msg.sentAt > RELAY_MAX_AGE_MS) return
                Gateway.consider(this, msg) // uploads it if we have internet (now or later)
                if (!seen.add(msg.id)) return // already shown via the internet
                val me = lastLocation
                if (me != null) {
                    checkDistance(me, msg.lat, msg.lng, msg.id, msg.hops)
                } else {
                    // We don't know where we are, but we're in Bluetooth range of the
                    // chain, so better to alert than to stay silent.
                    showSosNotification(msg.lat, msg.lng, -1f, msg.id, msg.hops)
                }
            }
        }
    }

    private fun showSosNotification(lat: Double, lng: Double, distance: Float, id: String, hops: Int = -1) {
        val page = AlertActivity.intent(this, lat, lng, distance, id, hops)
        val fullScreen = PendingIntent.getActivity(
            this, id.hashCode(), page,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, SOS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sos)
            .setContentTitle("SOS nearby!")
            .setContentText(
                if (distance >= 0) "Someone ${distance.toInt()} m from you needs help. Tap to see where."
                else "Someone near you needs help. Tap to see where.",
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(0xFFE84A8A.toInt())
            .setVibrate(longArrayOf(0, 600, 300, 600, 300, 600))
            .setContentIntent(fullScreen)
            // Shows the alert page over the lock screen / when the screen is off.
            .setFullScreenIntent(fullScreen, true)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(id.hashCode(), n)

        // While the phone is in use, Android shows full-screen intents as a small
        // banner. With "Display over other apps" we can open the full page directly.
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(page)
            } catch (_: Exception) {
            }
        }
    }

    private fun ongoingNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sos)
            .setContentTitle("Ekta is listening")
            .setContentText("You'll be alerted if someone within 500 m needs help, even with no network.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val ONGOING_ID = 1
        private const val FRESH_LOCATION_MS = 2 * 60 * 1000L
        private const val RETRY_EVERY_MS = 30 * 1000L
        private const val REFRESH_EVERY_MS = 5 * 60 * 1000L
        private const val ONGOING_CHANNEL = "ongoing"
        const val SOS_CHANNEL = "sos_alerts"
        const val RELAY_CHANNEL = "relay"

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
                NotificationChannel(RELAY_CHANNEL, "Relayed SOS", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When your phone passed someone's offline SOS to the internet"
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(SOS_CHANNEL, "SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alerts from people within 500 m who pressed SOS"
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
