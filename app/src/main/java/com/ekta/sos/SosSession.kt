package com.ekta.sos

import android.content.Context
import android.location.Location
import com.google.firebase.firestore.FirebaseFirestoreException
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
import java.util.UUID

/** How often the emergency contact gets a fresh location while an SOS is active. */
const val LIVE_UPDATE_EVERY_MS = 2 * 60 * 1000L

/** Live location updates stop on their own after this long. */
const val LIVE_UPDATE_FOR_MS = 30 * 60 * 1000L

enum class Step { IDLE, WORKING, DONE, FAILED, DEMO }

data class SosState(
    val active: Boolean = false,
    val alertId: String = "",
    val startedAt: Long = 0L,
    val location: Location? = null,
    val online: Boolean = false,      // internet right now
    val signal: Boolean = false,      // an SMS has gone out → mobile signal
    val upload: Step = Step.IDLE,
    val uploadNote: String = "",
    val mesh: Step = Step.IDLE,
    val meshNote: String = "",
    val contactSms: Step = Step.IDLE,
    val contactNote: String = "",
    val liveUpdatesSent: Int = 0,
    val committeeMessage: String = "",
    val callEnded: Boolean = false,
)

data class EmergencyContact(val name: String, val number: String)

/**
 * One SOS, from press to "I'm safe". Works with or without network:
 *  - Mesh (real): sent over Bluetooth / Wi-Fi Direct to nearby Ekta phones, which pass
 *    it on hop by hop until one with internet uploads it and texts her contact.
 *  - Internet (real): uploaded to Firestore now, or automatically once online.
 *  - SMS to her contact (real): queued and sent the moment there's signal; then a fresh
 *    location every 2 min. Skipped if a gateway phone already texted the contact.
 *  - 112 call and women's committee SMS: DEMO only.
 */
object SosSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(SosState())
    val state: StateFlow<SosState> = _state
    private var liveJob: Job? = null
    private var watchJob: Job? = null

    // ---------- Emergency contact & name ----------

    fun contact(context: Context): EmergencyContact? {
        val p = context.getSharedPreferences("ekta", Context.MODE_PRIVATE)
        val number = p.getString("contact_number", null) ?: return null
        return EmergencyContact(p.getString("contact_name", null) ?: number, number)
    }

    fun myName(context: Context) =
        context.getSharedPreferences("ekta", Context.MODE_PRIVATE).getString("my_name", "") ?: ""

    fun saveContact(context: Context, myName: String, name: String, number: String) {
        context.getSharedPreferences("ekta", Context.MODE_PRIVATE).edit()
            .putString("my_name", myName.trim())
            .putString("contact_name", name.ifBlank { number })
            .putString("contact_number", number.filter { it.isDigit() || it == '+' })
            .apply()
    }

    // ---------- Start / stop ----------

    fun start(context: Context) {
        if (_state.value.active) return
        val app = context.applicationContext
        Net.init(app)
        val id = UUID.randomUUID().toString()
        app.getSharedPreferences("ekta", Context.MODE_PRIVATE).edit().putString("last_alert_id", id).apply()
        _state.value = SosState(
            active = true,
            alertId = id,
            startedAt = System.currentTimeMillis(),
            online = Net.online.value,
            upload = Step.WORKING, uploadNote = "getting your location…",
            mesh = Step.WORKING, meshNote = "getting your location…",
            contactSms = Step.WORKING, contactNote = "getting your location…",
        )
        watch(app, id)

        scope.launch {
            // GPS works without any network, so this succeeds offline too.
            val loc = try { SosRepository.currentLocation(app) } catch (_: Exception) { null }
            if (loc == null) {
                _state.update {
                    it.copy(
                        upload = Step.FAILED, uploadNote = "couldn't get your location. Is GPS on?",
                        mesh = Step.FAILED, meshNote = "couldn't get your location",
                        contactSms = Step.FAILED, contactNote = "couldn't get your location",
                    )
                }
                return@launch
            }
            _state.update { it.copy(location = loc, committeeMessage = sosText(app, loc)) }
            val contact = contact(app)

            // 1. Mesh: hand it to every Ekta phone in Bluetooth / Wi-Fi range (real)
            if (MeshManager.hasPermissions(app)) {
                MeshManager.originate(
                    app,
                    MeshMessage(
                        type = MeshMessage.TYPE_SOS, id = id, sender = SosRepository.deviceId(app),
                        lat = loc.latitude, lng = loc.longitude, accuracy = loc.accuracy.toDouble(),
                        sentAt = System.currentTimeMillis(), hops = 0,
                        senderName = myName(app),
                        contactName = contact?.name ?: "", contactNumber = contact?.number ?: "",
                    ),
                )
                _state.update { it.copy(meshNote = "looking for Ekta phones in Bluetooth / Wi-Fi range…") }
            } else {
                _state.update { it.copy(mesh = Step.FAILED, meshNote = "Bluetooth / nearby-devices permission not given") }
            }

            // 2. Internet: upload now, or automatically once online (real)
            SosRepository.publishAlert(app, id, loc)
                .addOnSuccessListener {
                    _state.update {
                        if (it.alertId != id) it
                        else it.copy(upload = Step.DONE, uploadNote = "online · everyone with Ekta within ${ALERT_RADIUS_METERS.toInt()} m alerted")
                    }
                }
                .addOnFailureListener { e ->
                    val already = (e as? FirebaseFirestoreException)?.code ==
                        FirebaseFirestoreException.Code.PERMISSION_DENIED
                    _state.update {
                        if (it.alertId != id) it
                        else if (already) it.copy(upload = Step.DONE, uploadNote = "already online via a nearby phone")
                        else it.copy(upload = Step.FAILED, uploadNote = e.message ?: "upload failed")
                    }
                }

            // 3. SMS to her contact (real) — waits for signal by itself
            when {
                contact == null -> _state.update {
                    it.copy(contactSms = Step.FAILED, contactNote = "no emergency contact set")
                }
                !SmsOutbox.canSend(app) -> _state.update {
                    it.copy(contactSms = Step.FAILED, contactNote = "SMS permission not given")
                }
                else -> {
                    _state.update { it.copy(contactNote = "sending…") }
                    SmsOutbox.send(app, firstKey(id), contact.number, sosText(app, loc)) {
                        _state.update {
                            if (it.alertId != id) it
                            else it.copy(contactSms = Step.DONE, contactNote = "live location sent · updates every 2 min")
                        }
                    }
                    startLiveUpdates(app, id, contact)
                }
            }
        }
    }

    /** "I'm safe": stop updates and let the contact know (sent whenever there's signal). */
    fun stop(context: Context) {
        val app = context.applicationContext
        val s = _state.value
        liveJob?.cancel()
        watchJob?.cancel()
        SmsOutbox.cancel(firstKey(s.alertId))
        SmsOutbox.cancel(updateKey(s.alertId))
        val contact = contact(app)
        if (s.active && contact != null && SmsOutbox.canSend(app)) {
            SmsOutbox.send(app, "safe-${s.alertId}", contact.number, "Ekta: I'm safe now. Thank you for checking on me.")
        }
        _state.value = SosState()
    }

    fun endDemoCall() = _state.update { it.copy(callEnded = true) }

    /** A gateway phone got our alert online (and maybe texted our contact). */
    fun onAck(ack: MeshMessage) {
        _state.update { s ->
            if (!s.active || ack.id != s.alertId) return@update s
            var next = s
            if (s.upload != Step.DONE) {
                next = next.copy(upload = Step.DONE, uploadNote = "online through a nearby Ekta phone (gateway)")
            }
            if (ack.sms == MeshMessage.SMS_SENT && s.contactSms != Step.DONE) {
                SmsOutbox.cancel(firstKey(s.alertId)) // don't text the same alert twice
                next = next.copy(contactSms = Step.DONE, contactNote = "sent by a nearby Ekta phone with signal")
            } else if (ack.sms == MeshMessage.SMS_QUEUED && s.contactSms != Step.DONE) {
                next = next.copy(contactNote = "a nearby phone is texting your contact…")
            }
            next
        }
    }

    // ---------- helpers ----------

    private fun firstKey(id: String) = "sos-$id"
    private fun updateKey(id: String) = "upd-$id"

    private fun mapsLink(loc: Location) =
        String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", loc.latitude, loc.longitude)

    fun sosText(context: Context, loc: Location): String {
        val who = myName(context).ifBlank { "I" }
        return if (who == "I") "SOS! I need help. My live location: ${mapsLink(loc)} (sent by Ekta SOS)"
        else "SOS from $who! I need help. My live location: ${mapsLink(loc)} (sent by Ekta SOS)"
    }

    /** Keeps the screen in sync with network, signal and how far the mesh has spread. */
    private fun watch(app: Context, id: String) {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive && _state.value.alertId == id) {
                Net.refresh()
                val reached = MeshManager.reached.value[id] ?: 0
                _state.update { s ->
                    s.copy(
                        online = Net.online.value,
                        signal = SmsOutbox.lastSentAt.value >= s.startedAt, // only this SOS counts
                        mesh = when {
                            s.mesh == Step.FAILED -> Step.FAILED
                            reached > 0 -> Step.DONE
                            else -> Step.WORKING
                        },
                        meshNote = when {
                            s.mesh == Step.FAILED -> s.meshNote
                            reached > 0 -> "passed to $reached phone${if (reached == 1) "" else "s"} nearby · they pass it on"
                            s.location == null -> s.meshNote
                            else -> "looking for Ekta phones in Bluetooth / Wi-Fi range…"
                        },
                        uploadNote = if (s.upload == Step.WORKING && s.location != null && !Net.online.value)
                            "no internet · will upload by itself, or via a nearby phone" else s.uploadNote,
                        contactNote = if (s.contactSms == Step.WORKING && s.location != null &&
                            SmsOutbox.isPending(firstKey(id)) && SmsOutbox.lastSentAt.value < s.startedAt
                        ) "no signal · will send automatically when signal returns" else s.contactNote,
                    )
                }
                delay(1_000)
            }
        }
    }

    private fun startLiveUpdates(app: Context, id: String, contact: EmergencyContact) {
        liveJob?.cancel()
        liveJob = scope.launch {
            val started = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() - started < LIVE_UPDATE_FOR_MS) {
                delay(LIVE_UPDATE_EVERY_MS)
                val loc = try { SosRepository.currentLocation(app) } catch (_: Exception) { null } ?: continue
                _state.update { it.copy(location = loc) }
                // Same key → an unsent older update is replaced by this newer location.
                SmsOutbox.send(app, updateKey(id), contact.number, "Ekta SOS update - my live location: ${mapsLink(loc)}") {
                    _state.update {
                        if (it.alertId != id) it
                        else it.copy(
                            liveUpdatesSent = it.liveUpdatesSent + 1,
                            contactSms = Step.DONE,
                            contactNote = "live location sent · ${it.liveUpdatesSent + 1} update(s) so far",
                        )
                    }
                }
            }
        }
    }
}
