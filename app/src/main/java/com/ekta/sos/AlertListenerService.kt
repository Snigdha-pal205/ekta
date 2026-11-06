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
import android.os.Bundle
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
        if (intent?.action == ACTION_PUSH) intent.extras?.let(::onPush)
        if (listening) return START_STICKY
        listening = true
        // Make sure this phone has a push address (FCM token) for server alerts.
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
                getSharedPreferences("ekta", MODE_PRIVATE).edit().putString("fcm_token", t).apply()
            }
        } catch (_: Exception) {
        }
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
                snap?.documentChanges?.forEach { onAlertDoc(it) }
            }
    }

    /** A new alert, or a change to one (cancelled / duress / duress location), from the server. */
    private fun onAlertDoc(change: DocumentChange) {
        val doc = change.document
        // Only trust what the server has confirmed. A local, not-yet-confirmed write (for
        // example a forged cancel this phone tried to upload as gateway) shows up here
        // first and is only rejected by the server afterwards.
        if (doc.metadata.hasPendingWrites()) return
        onServerAlert(
            id = doc.id,
            senderId = doc.getString("senderId") ?: "",
            lat = doc.getDouble("lat"),
            lng = doc.getDouble("lng"),
            createdAt = doc.getTimestamp("createdAt")?.toDate()?.time,
            status = doc.getString("status") ?: AlertStatus.ACTIVE,
            statusAt = doc.getLong("statusAt") ?: 0L,
            cancelHash = doc.getString("hashC") ?: "",
            duressHash = doc.getString("hashD") ?: "",
            locHash = doc.getString("hashL") ?: "",
            accuracy = doc.getDouble("accuracy"),
            isNew = change.type == DocumentChange.Type.ADDED,
        )
    }

    /**
     * An alert from the server — through the live Firestore listener or a push (FCM).
     * Both are server-confirmed, so cancel / duress here are genuine.
     */
    private fun onServerAlert(
        id: String, senderId: String, lat: Double?, lng: Double?, createdAt: Long?,
        status: String, statusAt: Long, cancelHash: String, duressHash: String, isNew: Boolean,
        locHash: String = "", accuracy: Double? = null,
    ) {
        if (senderId == SosRepository.deviceId(this)) return // never alert her own phone
        AlertStatus.rememberHashes(id, cancelHash, duressHash, locHash)
        // Status change, or simply her newest live location (an open alert page follows it).
        applyStatus(id, status, lat, lng, statusAt, accuracy)
        if (isNew && status != AlertStatus.CANCELLED && lat != null && lng != null && createdAt != null) {
            handleAlert(id, lat, lng, createdAt)
        }
    }

    /** A push from the server (FCM), forwarded by [EktaMessagingService]. */
    private fun onPush(data: Bundle) {
        onServerAlert(
            id = data.getString("id") ?: return,
            senderId = data.getString("senderId") ?: "",
            lat = data.getString("lat")?.toDoubleOrNull(),
            lng = data.getString("lng")?.toDoubleOrNull(),
            createdAt = data.getString("createdAt")?.toLongOrNull() ?: System.currentTimeMillis(),
            status = data.getString("status") ?: AlertStatus.ACTIVE,
            statusAt = data.getString("statusAt")?.toLongOrNull() ?: System.currentTimeMillis(),
            cancelHash = data.getString("hashC") ?: "",
            duressHash = data.getString("hashD") ?: "",
            locHash = data.getString("hashL") ?: "",
            accuracy = data.getString("accuracy")?.toDoubleOrNull(),
            isNew = true,
        )
    }

    /** She ended the SOS with a PIN (or it's a newer duress location). */
    private fun applyStatus(id: String, status: String, lat: Double?, lng: Double?, at: Long, accuracy: Double? = null) {
        val changed = AlertStatus.set(id, status, lat, lng, at, accuracy)
        when (status) {
            AlertStatus.CANCELLED -> {
                MeshManager.drop("sos:$id") // stop spreading it
                AlarmPlayer.stop(id)
                val nm = getSystemService(NotificationManager::class.java)
                nm.cancel(id.hashCode())
                nm.cancel(duressNotificationId(id))
                // A normal, quiet notification — not the alarm. (An open alert page
                // switches to "ALERT CANCELLED" by itself.)
                if (changed && AlertStatus.wasAlerted(id)) showSafeNotification(id)
            }
            AlertStatus.DURESS -> if (changed && AlertStatus.wasAlerted(id)) {
                // Bring the alert back up with the duress warning.
                val l = AlertStatus.infos.value[id]
                val me = lastLocation
                val d = if (me != null && l?.lat != null && l.lng != null) {
                    val r = FloatArray(1)
                    Location.distanceBetween(me.latitude, me.longitude, l.lat, l.lng, r)
                    r[0]
                } else -1f
                showSosNotification(l?.lat ?: 0.0, l?.lng ?: 0.0, d, id)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleAlert(id: String, lat: Double, lng: Double, createdAt: Long) {
        if (!seen.add(id)) return
        if (System.currentTimeMillis() - createdAt > ALERT_MAX_AGE_MS) return

        val known = lastLocation
        val fresh = known != null &&
            System.currentTimeMillis() - known.time < FRESH_LOCATION_MS
        if (fresh) {
            checkDistance(known!!, lat, lng, id)
        } else {
            // Our position is old or unknown: get a precise fix before deciding.
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    val me = loc ?: known ?: return@addOnSuccessListener
                    lastLocation = me
                    checkDistance(me, lat, lng, id)
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
            MeshMessage.TYPE_STATUS -> {
                if (msg.sender == SosRepository.deviceId(this)) return
                Gateway.considerStatus(this, msg) // put it online if we have internet
                // Act on it only if the key matches the SOS we saw (so nobody can fake a cancel).
                if (AlertStatus.verify(msg.id, msg.status, msg.secret)) {
                    applyStatus(msg.id, msg.status, msg.lat, msg.lng, msg.sentAt, msg.accuracy)
                }
            }
            MeshMessage.TYPE_SOS -> {
                if (msg.sender == SosRepository.deviceId(this)) return
                AlertStatus.rememberHashes(msg.id, msg.cancelHash, msg.duressHash, msg.locHash)
                if (AlertStatus.statusOf(msg.id) == AlertStatus.CANCELLED) return
                AlertStatus.set(msg.id, AlertStatus.ACTIVE, msg.lat, msg.lng, msg.sentAt, msg.accuracy)
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

    /**
     * Every alert (Bluetooth mesh, server, push, duress update) looks and sounds the same:
     * one notification on the "safeher_alerts" channel + the looping alarm, until she
     * opens the alert or taps "Stop sound".
     */
    private fun showSosNotification(lat: Double, lng: Double, distance: Float, id: String, hops: Int = -1) {
        AlertStatus.markAlerted(id)
        val duress = AlertStatus.statusOf(id) == AlertStatus.DURESS
        val notifyId = if (duress) duressNotificationId(id) else id.hashCode()
        val nm = getSystemService(NotificationManager::class.java)
        if (duress) nm.cancel(id.hashCode()) // the duress warning replaces the plain alert

        val page = AlertActivity.intent(this, lat, lng, distance, id, hops)
        val fullScreen = PendingIntent.getActivity(
            this, notifyId, page,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Tapping the notification opens the full alert page; the sound keeps going
        // until STOP SOUND (on the page or the notification).
        val open = PendingIntent.getActivity(
            this, notifyId + 1, page,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopSound = PendingIntent.getBroadcast(
            this, notifyId + 2,
            Intent(this, StopSoundReceiver::class.java).putExtra(StopSoundReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, AlarmPlayer.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sos)
            .setContentTitle(if (duress) "DURESS: she was forced to cancel. Do NOT call her phone." else "SOS nearby!")
            .setContentText(
                when {
                    duress -> "Her alert is still active. Tap to see where she is."
                    distance >= 0 -> "Someone ${distance.toInt()} m from you needs help. Tap to see where."
                    else -> "Someone near you needs help. Tap to see where."
                },
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFFE84A8A.toInt())
            .setContentIntent(open)
            // Shows the alert page over the lock screen / when the screen is off.
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, "Stop sound", stopSound)
            .setAutoCancel(false) // stays (with "Stop sound") until the page is dismissed
            .build()
        nm.notify(notifyId, n)

        // The channel plays the sound once as the notification appears; the alarm
        // player then keeps the same sound looping until it's stopped.
        AlarmPlayer.start(this, id, delayMs = AlarmPlayer.CLIP_MS, key = AlarmPlayer.ringKeyFor(id, duress))

        // While the phone is in use, Android shows full-screen intents as a small
        // banner. With "Display over other apps" we can open the full page directly.
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(page)
            } catch (_: Exception) {
            }
        }
    }

    /** "She is safe, alert cancelled": a normal, quiet notification (no alarm). */
    private fun showSafeNotification(id: String) {
        val n = NotificationCompat.Builder(this, AlarmPlayer.QUIET_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sos)
            .setContentTitle("She is safe, alert cancelled")
            .setContentText("She cancelled her SOS with her PIN. Thank you for being ready to help.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(("safe$id").hashCode(), n)
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
            AlarmPlayer.createChannels(context) // "safeher_alerts" + quiet updates
        }

        fun duressNotificationId(id: String) = ("duress$id").hashCode()

        const val ACTION_PUSH = "com.ekta.sos.PUSH"

        /** Hand a server push (FCM data) to the running service. */
        fun deliverPush(context: Context, data: Map<String, String>) {
            val i = Intent(context, AlertListenerService::class.java).setAction(ACTION_PUSH)
            data.forEach { (k, v) -> i.putExtra(k, v) }
            try {
                ContextCompat.startForegroundService(context, i)
            } catch (_: Exception) {
            }
        }
    }
}
