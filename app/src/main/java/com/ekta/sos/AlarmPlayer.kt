package com.ekta.sos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one alarm for every incoming alert — Bluetooth mesh, server, push (FCM) and
 * duress updates all ring the same way: res/raw/safeher_alarm on the ALARM stream
 * (so it rings in silent mode) with a strong vibration, looping until the receiver
 * opens the alert or taps "Stop sound".
 *
 * Never used for her own SOS: the sender's phone stays silent (and in duress, must).
 */
object AlarmPlayer {
    /** The single channel for alerts. */
    const val CHANNEL = "safeher_alerts"

    /** Quiet channel for "She is safe, alert cancelled" and similar updates. */
    const val QUIET_CHANNEL = "safeher_updates"

    /** Length of res/raw/safeher_alarm (one loop). */
    const val CLIP_MS = 2_000L

    private const val SAFETY_STOP_MS = 3 * 60 * 1000L // never ring forever if nobody's there
    private val VIBRATION = longArrayOf(0, 900, 250, 900, 250, 900, 600)
    private val AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255, 0)

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private val _ringingFor = MutableStateFlow<String?>(null)
    /** Alert id currently ringing, or null. */
    val ringingFor: StateFlow<String?> = _ringingFor

    /** What is ringing: the alert id, plus ":duress" for a duress update. */
    private var ringKey: String? = null

    /** Rings she already stopped. The same alert won't ring again; a new event (duress) will. */
    private val acknowledged = HashSet<String>()

    fun ringKeyFor(alertId: String, duress: Boolean) = if (duress) "$alertId:duress" else alertId

    fun soundUri(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/${R.raw.safeher_alarm}")

    val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "SafeHer alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Someone nearby pressed SOS, or is under duress. Rings even in silent mode."
                setSound(soundUri(context), alarmAttributes)
                enableVibration(true)
                vibrationPattern = VIBRATION
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(QUIET_CHANNEL, "Alert updates", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Quiet updates, e.g. \"She is safe, alert cancelled\""
                setSound(null, null)
                enableVibration(false)
            },
        )
        // The old channel from earlier versions is replaced by the one above.
        nm.deleteNotificationChannel("sos_alerts")
    }

    /**
     * Start looping the alarm for [alertId] (replaces anything already ringing).
     * [delayMs] lets the notification channel play the first loop itself.
     */
    fun start(context: Context, alertId: String, delayMs: Long = 0, key: String = alertId) {
        handler.post {
            if (key in acknowledged) return@post // she already stopped this one
            if (ringKey == key) return@post        // already ringing / about to
            stopNow()
            ringKey = key
            _ringingFor.value = alertId // "Stop sound" works from this moment on
            handler.postDelayed({ begin(context, alertId) }, delayMs)
        }
    }

    private fun begin(context: Context, alertId: String) {
        run {
            if (_ringingFor.value != alertId) return
            val app = context.applicationContext
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(alarmAttributes)
                    setDataSource(app, soundUri(app))
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (_: Exception) {
                player = null
            }
            vibrator = vibrator(app).also {
                it.vibrate(VibrationEffect.createWaveform(VIBRATION, AMPLITUDES, 0))
            }
            handler.postDelayed(::stopNow, SAFETY_STOP_MS)
        }
    }

    /** Stop the sound (any alert, or only if [alertId] is the one ringing). */
    fun stop(alertId: String? = null) = handler.post {
        if (alertId == null || _ringingFor.value == alertId) {
            ringKey?.let { acknowledged += it }
            stopNow()
        }
    }

    fun isPlayingMedia() = try { player?.isPlaying == true } catch (_: Exception) { false }

    private fun stopNow() {
        handler.removeCallbacksAndMessages(null)
        player?.run {
            try { stop() } catch (_: Exception) {}
            release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        ringKey = null
        _ringingFor.value = null
    }

    private fun vibrator(c: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
}
