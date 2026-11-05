package com.ekta.sos

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    data class Alert(
        val lat: Double, val lng: Double, val distance: Int, val id: String, val at: Long,
        val hops: Int, // -1 = came over the internet, else Bluetooth / Wi-Fi hops
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showOverLockScreen()
        handle(intent)
        setContent {
            alert?.let { a ->
                AlertScreen(
                    alert = a,
                    onOpenMap = {
                        stopAlarm()
                        try {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("geo:${a.lat},${a.lng}?q=${a.lat},${a.lng}(SOS)")),
                            )
                        } catch (_: Exception) {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=${a.lat},${a.lng}")),
                            )
                        }
                        finish()
                    },
                    onDismiss = { stopAlarm(); finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent) // a newer alert replaces the one on screen
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
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
        // The page itself rings now, so drop the notification (and its one-off sound).
        getSystemService(NotificationManager::class.java).cancel(id.hashCode())
        startAlarm()
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

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private fun startAlarm() {
        stopAlarm()
        // Buzz: long pulses, repeating
        vibrator().vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
        // Ring: alarm sound on the alarm stream, so it plays even in silent mode
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@AlertActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
        }
        handler.postDelayed({ stopAlarm() }, AUTO_STOP_MS)
    }

    private fun stopAlarm() {
        handler.removeCallbacksAndMessages(null)
        vibrator().cancel()
        player?.run { try { stop() } catch (_: Exception) {}; release() }
        player = null
    }

    companion object {
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"
        private const val EXTRA_DISTANCE = "distance"
        private const val EXTRA_ID = "id"
        private const val EXTRA_HOPS = "hops"
        private const val AUTO_STOP_MS = 2 * 60 * 1000L

        fun intent(context: Context, lat: Double, lng: Double, distance: Float, id: String, hops: Int = -1) =
            Intent(context, AlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng)
                .putExtra(EXTRA_DISTANCE, distance)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_HOPS, hops)
    }
}

private val AlertPink = Color(0xFFF06A9E)

@Composable
private fun AlertScreen(alert: AlertActivity.Alert, onOpenMap: () -> Unit, onDismiss: () -> Unit) {
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
                "SOMEONE NEARBY NEEDS HELP",
                fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 17.sp,
                color = Ink, letterSpacing = 1.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .background(Ink)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    if (alert.distance >= 0) "${alert.distance} m away" else "very close",
                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Lime,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Alert received at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(alert.at))}",
                fontFamily = Mono, fontSize = 13.sp, color = Ink,
            )
            if (alert.hops >= 0) {
                Text(
                    if (alert.hops == 0) "via Bluetooth/Wi-Fi straight from her phone · no network needed"
                    else "via Bluetooth/Wi-Fi through ${alert.hops} phone(s) · no network needed",
                    fontFamily = Mono, fontSize = 11.sp, color = Ink, textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(34.dp))
            ChunkyButton(width = 280.dp, height = 74.dp, onClick = onOpenMap) {
                Text("SHOW ME WHERE", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 19.sp, color = Ink, letterSpacing = 1.sp)
                Text("opens the map", fontFamily = Mono, fontSize = 11.sp, color = Ink)
            }
            Spacer(Modifier.height(18.dp))
            ChunkyButton(width = 280.dp, height = 54.dp, color = Color.White, onClick = onDismiss) {
                Text("DISMISS", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink, letterSpacing = 2.sp)
            }
            Spacer(Modifier.weight(1.2f))
        }
    }
}
