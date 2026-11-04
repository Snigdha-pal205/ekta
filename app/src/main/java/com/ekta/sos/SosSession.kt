package com.ekta.sos

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/** How often the emergency contact gets a fresh location while an SOS is active. */
const val LIVE_UPDATE_EVERY_MS = 2 * 60 * 1000L

/** Live location updates stop on their own after this long. */
const val LIVE_UPDATE_FOR_MS = 30 * 60 * 1000L

/** Demo stand-in for the nearest women's committee. Nothing is ever sent to it. */
const val DEMO_COMMITTEE_NAME = "Women's Committee (demo)"

enum class Step { IDLE, WORKING, DONE, FAILED, DEMO }

data class SosState(
    val active: Boolean = false,
    val startedAt: Long = 0L,
    val location: Location? = null,
    val nearby: Step = Step.IDLE,
    val nearbyNote: String = "",
    val contactSms: Step = Step.IDLE,
    val contactNote: String = "",
    val liveUpdatesSent: Int = 0,
    val committeeMessage: String = "",
    val callEnded: Boolean = false,
)

data class EmergencyContact(val name: String, val number: String)

/**
 * One SOS from start ("press") to finish ("I'm safe"):
 *  - alerts nearby phones through Firestore (real)
 *  - texts the emergency contact a maps link, then a fresh one every 2 min (real)
 *  - shows what would be texted to the women's committee (demo, not sent)
 *  - shows a call to 112 (demo, no call is placed)
 */
object SosSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(SosState())
    val state: StateFlow<SosState> = _state
    private var liveJob: Job? = null
    private var receiverRegistered = false

    private const val ACTION_SMS_SENT = "com.ekta.sos.SMS_SENT"

    // ---------- Emergency contact ----------

    fun contact(context: Context): EmergencyContact? {
        val p = context.getSharedPreferences("ekta", Context.MODE_PRIVATE)
        val number = p.getString("contact_number", null) ?: return null
        return EmergencyContact(p.getString("contact_name", null) ?: number, number)
    }

    fun saveContact(context: Context, name: String, number: String) {
        context.getSharedPreferences("ekta", Context.MODE_PRIVATE).edit()
            .putString("contact_name", name.ifBlank { number })
            .putString("contact_number", number.filter { it.isDigit() || it == '+' })
            .apply()
    }

    // ---------- Start / stop ----------

    fun start(context: Context) {
        if (_state.value.active) return
        val app = context.applicationContext
        registerSmsReceiver(app)
        _state.value = SosState(
            active = true,
            startedAt = System.currentTimeMillis(),
            nearby = Step.WORKING,
            contactSms = Step.WORKING,
            contactNote = "getting your location…",
        )

        scope.launch {
            val loc = try {
                SosRepository.currentLocation(app)
            } catch (_: Exception) {
                null
            }
            if (loc == null) {
                _state.update {
                    it.copy(
                        nearby = Step.FAILED, nearbyNote = "couldn't get your location. Is GPS on?",
                        contactSms = Step.FAILED, contactNote = "couldn't get your location",
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(location = loc, committeeMessage = sosMessage(loc))
            }

            // 1. Nearby phones (real)
            launch {
                _state.update {
                    try {
                        SosRepository.publishAlert(app, loc)
                        it.copy(nearby = Step.DONE, nearbyNote = "everyone with Ekta within ${ALERT_RADIUS_METERS.toInt()} m")
                    } catch (e: Exception) {
                        it.copy(nearby = Step.FAILED, nearbyNote = e.message ?: "no internet?")
                    }
                }
            }

            // 2. Emergency contact SMS (real), then live updates
            val contact = contact(app)
            when {
                contact == null -> _state.update {
                    it.copy(contactSms = Step.FAILED, contactNote = "no emergency contact set")
                }
                !canSendSms(app) -> _state.update {
                    it.copy(contactSms = Step.FAILED, contactNote = "SMS permission not given")
                }
                else -> {
                    sendSms(app, contact.number, sosMessage(loc), tag = "first")
                    startLiveUpdates(app, contact)
                }
            }
        }
    }

    /** "I'm safe": stop live updates and let the contact know. */
    fun stop(context: Context) {
        val app = context.applicationContext
        liveJob?.cancel()
        val contact = contact(app)
        if (_state.value.active && contact != null && canSendSms(app)) {
            sendSms(app, contact.number, "Ekta: I'm safe now. Thank you for checking on me.", tag = "safe")
        }
        _state.value = SosState()
    }

    fun endDemoCall() = _state.update { it.copy(callEnded = true) }

    // ---------- SMS ----------

    fun sosMessage(loc: Location) =
        "SOS! I need help. My live location: ${mapsLink(loc)} (sent by Ekta SOS)"

    private fun mapsLink(loc: Location) =
        String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", loc.latitude, loc.longitude)

    fun canSendSms(context: Context) = ContextCompat.checkSelfPermission(
        context, Manifest.permission.SEND_SMS,
    ) == PackageManager.PERMISSION_GRANTED

    private fun startLiveUpdates(app: Context, contact: EmergencyContact) {
        liveJob?.cancel()
        liveJob = scope.launch {
            val started = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() - started < LIVE_UPDATE_FOR_MS) {
                delay(LIVE_UPDATE_EVERY_MS)
                val loc = try { SosRepository.currentLocation(app) } catch (_: Exception) { null } ?: continue
                _state.update { it.copy(location = loc) }
                sendSms(app, contact.number, "Ekta SOS update - my live location: ${mapsLink(loc)}", tag = "update")
            }
        }
    }

    private fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()

    private fun sendSms(app: Context, number: String, text: String, tag: String) {
        try {
            val sent = PendingIntent.getBroadcast(
                app, tag.hashCode() + (System.currentTimeMillis() % 10_000).toInt(),
                Intent(ACTION_SMS_SENT).setPackage(app.packageName).putExtra("tag", tag),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val sms = smsManager(app)
            val parts = sms.divideMessage(text)
            if (parts.size == 1) {
                sms.sendTextMessage(number, null, text, sent, null)
            } else {
                sms.sendMultipartTextMessage(number, null, parts, arrayListOf(sent).apply {
                    repeat(parts.size - 1) { add(null) }
                }, null)
            }
        } catch (e: Exception) {
            if (tag != "safe") _state.update {
                it.copy(contactSms = Step.FAILED, contactNote = "SMS failed: ${e.message ?: "unknown"}")
            }
        }
    }

    private fun registerSmsReceiver(app: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val tag = intent.getStringExtra("tag") ?: return
                if (tag == "safe" || !_state.value.active) return
                val ok = resultCode == android.app.Activity.RESULT_OK
                _state.update {
                    when {
                        !ok -> it.copy(contactSms = Step.FAILED, contactNote = "SMS not sent. Check balance/SIM")
                        tag == "update" -> it.copy(
                            liveUpdatesSent = it.liveUpdatesSent + 1,
                            contactNote = "live location sent · ${it.liveUpdatesSent + 1} update(s) so far",
                        )
                        else -> it.copy(
                            contactSms = Step.DONE,
                            contactNote = "live location sent · updates every 2 min",
                        )
                    }
                }
            }
        }, IntentFilter(ACTION_SMS_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
