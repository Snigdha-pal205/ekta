package com.ekta.sos

import androidx.compose.ui.res.stringResource
import android.app.NotificationManager
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/**
 * The full-page alert a nearby phone sees. It opens over the lock screen, buzzes and
 * plays the alarm sound on loop until the person taps a button (or 2 minutes pass).
 */
class AlertActivity : ComponentActivity() {

    private var alert by mutableStateOf<Alert?>(null)

    /** This phone's own live position while the alert is open (GPS on, high accuracy). */
    private var myLoc by mutableStateOf<Location?>(null)
    private var myAccAtAlert = -1f
    private var locCallback: LocationCallback? = null

    override fun onStart() {
        super.onStart()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                val l = r.lastLocation ?: return
                val cur = myLoc
                // keep the newer fix unless it is much worse
                if (cur == null || l.accuracy <= cur.accuracy + 10f || l.time - cur.time > 20_000) myLoc = l
            }
        }
        try {
            @SuppressLint("MissingPermission")
            val t = LocationServices.getFusedLocationProviderClient(this).requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000).setMinUpdateIntervalMillis(1_000).build(),
                cb, Looper.getMainLooper(),
            )
            locCallback = cb
        } catch (_: Exception) {
        }
    }

    override fun onStop() {
        locCallback?.let {
            try { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it) } catch (_: Exception) {}
        }
        locCallback = null
        super.onStop()
    }

    data class Alert(
        val lat: Double, val lng: Double, val distance: Int, val id: String, val at: Long,
        val hops: Int, // -1 = came over the internet, else Bluetooth / Wi-Fi hops
    )

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showOverLockScreen()
        handle(intent)
        setContent {
            val infos by AlertStatus.infos.collectAsState()
            val ringing by AlarmPlayer.ringingFor.collectAsState()
            alert?.let { a ->
                val info = infos[a.id]
                val status = info?.status ?: AlertStatus.ACTIVE
                // Live distance: her newest position vs. this phone's newest position.
                val herLat = info?.lat ?: a.lat
                val herLng = info?.lng ?: a.lng
                val me = myLoc
                val dist = if (me != null) {
                    val r = FloatArray(1)
                    Location.distanceBetween(me.latitude, me.longitude, herLat, herLng, r)
                    r[0].toInt()
                } else a.distance
                val myAcc = (me?.accuracy ?: myAccAtAlert).toDouble().coerceAtLeast(0.0)
                val unsure = Math.hypot(info?.accuracy ?: 0.0, myAcc)
                AlertScreen(
                    alert = a.copy(lat = herLat, lng = herLng, distance = dist),
                    uncertainty = unsure,
                    accuracy = info?.accuracy,
                    locationAt = info?.updatedAt ?: 0L,
                    status = status,
                    ringing = ringing == a.id,
                    onStopSound = { AlarmPlayer.stop(a.id) },
                    onOpenMap = {
                        // Her NEWEST position, as walking directions straight to her.
                        val lat = info?.lat ?: a.lat
                        val lng = info?.lng ?: a.lng
                        done(a.id)
                        val opened = listOf(
                            "google.navigation:q=$lat,$lng&mode=w",
                            "geo:$lat,$lng?q=$lat,$lng(SOS)",
                            "https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=walking",
                        ).any { uri ->
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                                true
                            } catch (_: Exception) {
                                false
                            }
                        }
                        if (opened) finish()
                    },
                    onDismiss = { done(a.id); finish() },
                )
                // Settings from the alert page too (the alarm keeps going until STOP SOUND).
                SettingsGear {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .putExtra(EXTRA_OPEN_SETTINGS, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent) // a newer alert / duress update replaces the one on screen
    }

    /** She's been seen: stop the sound and clear the alert notifications. */
    private fun done(id: String) {
        AlarmPlayer.stop(id)
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(id.hashCode())
        nm.cancel(AlertListenerService.duressNotificationId(id))
    }

    private fun handle(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        alert = Alert(
            lat = intent.getDoubleExtra(EXTRA_LAT, 0.0),
            lng = intent.getDoubleExtra(EXTRA_LNG, 0.0),
            distance = intent.getFloatExtra(EXTRA_DISTANCE, 0f).toInt(),
            id = id,
            at = System.currentTimeMillis(),
            hops = intent.getIntExtra(EXTRA_HOPS, -1),
        )
        myAccAtAlert = intent.getFloatExtra(EXTRA_MY_ACC, -1f)
        // Opening the page (tapping the notification, or it popping up) never silences
        // the alarm: it keeps ringing until she presses STOP SOUND / a button here.
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"
        private const val EXTRA_DISTANCE = "distance"
        private const val EXTRA_ID = "id"
        private const val EXTRA_HOPS = "hops"
        private const val EXTRA_MY_ACC = "my_acc"

        fun intent(
            context: Context, lat: Double, lng: Double, distance: Float, id: String, hops: Int = -1,
            myAccuracy: Float = -1f,
        ) =
            Intent(context, AlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng)
                .putExtra(EXTRA_DISTANCE, distance)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_HOPS, hops)
                .putExtra(EXTRA_MY_ACC, myAccuracy)
    }
}

private val AlertPink = Color(0xFFF06A9E)

@Composable
private fun AlertScreen(
    alert: AlertActivity.Alert,
    uncertainty: Double,
    accuracy: Double?,
    locationAt: Long,
    status: String,
    ringing: Boolean,
    onStopSound: () -> Unit,
    onOpenMap: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (status == AlertStatus.CANCELLED) return CancelledByHer(onDismiss)
    val pulse = rememberInfiniteTransition(label = "pulse")
    val t by pulse.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(AlertPink),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawSky() }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (status == AlertStatus.DURESS) {
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Ink)
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.duress), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 26.sp,
                        color = Lime, letterSpacing = 4.sp,
                    )
                    Text(
                        stringResource(R.string.duress_forced),
                        fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        color = Color.White, textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.duress_keeps),
                        fontFamily = Mono, fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.weight(1f))

            // Pulsing rings around the "!" badge
            Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    for (i in 0..2) {
                        val p = (t + i / 3f) % 1f
                        drawCircle(
                            Ink.copy(alpha = (1f - p) * 0.8f),
                            radius = size.minDimension / 2 * (0.35f + 0.65f * p),
                            style = Stroke(width = 3.dp.toPx()),
                        )
                    }
                }
                Box(
                    Modifier
                        .size(86.dp)
                        .background(Lime, shape = androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("!", fontSize = 56.sp, fontWeight = FontWeight.Black, color = Ink)
                }
            }

            Spacer(Modifier.height(18.dp))
            WobblyLogo("SOS")
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.someone_needs_help),
                fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 17.sp,
                color = Ink, letterSpacing = 1.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .background(Ink)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                // Two rough positions can't give an exact gap: if the gap is smaller than
                // how unsure they are, say "VERY CLOSE" instead of a misleading number.
                val close = alert.distance < 0 || alert.distance <= maxOf(uncertainty, 20.0)
                Text(
                    if (close) stringResource(R.string.very_close) else stringResource(R.string.m_away, alert.distance.toInt()),
                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Lime,
                )
            }
            if (alert.distance >= 0 && alert.distance <= maxOf(uncertainty, 20.0) && uncertainty > 20.0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.look_around, (Math.ceil(uncertainty / 10.0) * 10).toInt()),
                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(10.dp))
            // Her live location: how exact it is and how fresh.
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(1_000)
                    now = System.currentTimeMillis()
                }
            }
            val ago = if (locationAt > 0) ((now - locationAt) / 1000).coerceAtLeast(0) else -1
            val liveLabel = stringResource(R.string.live_location)
            val agoLabel = if (ago < 60) stringResource(R.string.updated_s_ago, ago.toInt()) else stringResource(R.string.updated_min_ago, (ago / 60).toInt())
            Text(
                buildString {
                    append(liveLabel)
                    if (accuracy != null && accuracy > 0) append(" · ±${accuracy.toInt()} m")
                    if (ago >= 0) append(" · $agoLabel")
                },
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.received_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(alert.at))),
                fontFamily = Mono, fontSize = 13.sp, color = Ink,
            )
            if (alert.hops >= 0) {
                Text(
                    if (alert.hops == 0) stringResource(R.string.via_direct)
                    else stringResource(R.string.via_hops, alert.hops),
                    fontFamily = Mono, fontSize = 11.sp, color = Ink, textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(26.dp))
            if (ringing) {
                ChunkyButton(width = 280.dp, height = 54.dp, color = Ink, onClick = onStopSound) {
                    Text(stringResource(R.string.stop_sound), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 17.sp, color = Lime, letterSpacing = 2.sp)
                }
                Spacer(Modifier.height(16.dp))
            }
            ChunkyButton(width = 280.dp, height = 74.dp, onClick = onOpenMap) {
                Text(stringResource(R.string.show_me_where), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 19.sp, color = Ink, letterSpacing = 1.sp)
                Text(stringResource(R.string.walking_dirs), fontFamily = Mono, fontSize = 11.sp, color = Ink)
            }
            Spacer(Modifier.height(18.dp))
            ChunkyButton(width = 280.dp, height = 54.dp, color = Color.White, onClick = onDismiss) {
                Text(stringResource(R.string.dismiss), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink, letterSpacing = 2.sp)
            }
            Spacer(Modifier.weight(1.2f))
        }
    }
}

/** She ended the SOS with her real PIN. */
@Composable
private fun CancelledByHer(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(SkyBottom),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawSky() }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(96.dp)
                    .background(Lime, shape = androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("✓", fontSize = 56.sp, fontWeight = FontWeight.Black, color = Ink) }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.alert_cancelled_caps), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 22.sp,
                color = Ink, letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.she_is_safe_text),
                fontFamily = Mono, fontSize = 14.sp, color = Ink, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(36.dp))
            ChunkyButton(width = 280.dp, height = 60.dp, color = Color.White, onClick = onDismiss) {
                Text(stringResource(R.string.ok), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 18.sp, color = Ink, letterSpacing = 2.sp)
            }
            Spacer(Modifier.weight(1.2f))
        }
    }
}
