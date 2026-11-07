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
    val status: String = AlertStatus.ACTIVE, // active / duress (cancelled resets the state)
    val hidden: Boolean = false,             // duress: alert keeps running but her screen looks normal
    val gpsOn: Boolean = true,               // Location (GPS) switched on in phone settings
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
    private var startJob: Job? = null
    private var trackJob: Job? = null

    /** Secret keys for the running alert. Only their hashes leave the phone with the SOS. */
    private var cancelKey = ""
    private var duressKey = ""
    private var locKey = "" // proves her live-location updates are really from her

    /** How long GPS keeps tracking her (and sending her position) at most. */
    private const val TRACK_FOR_MS = 60 * 60 * 1000L

    /** A first fix worse than this gets an "exact location" SMS once GPS locks on. */
    private const val ROUGH_FIX_M = 50f
    private const val EXACT_FIX_M = 30f

    /** Alerts we cancelled, so a late "SMS sent" note from a gateway can still be matched. */
    private val finished = HashSet<String>()

    /** How long silent duress tracking keeps sending her location. */
    private const val DURESS_TRACK_MS = 60 * 60 * 1000L

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
        val cur = _state.value
        if (cur.active) {
            // SOS pressed again while silently in duress: show the SOS page again.
            if (cur.hidden) _state.update { it.copy(hidden = false) }
            return
        }
        val app = context.applicationContext
        Net.init(app)
        val id = UUID.randomUUID().toString()
        cancelKey = PinStore.randomHex(16)
        duressKey = PinStore.randomHex(16)
        locKey = PinStore.randomHex(16)
        val cancelHash = PinStore.sha256Hex(cancelKey)
        val duressHash = PinStore.sha256Hex(duressKey)
        val locHash = PinStore.sha256Hex(locKey)
        app.getSharedPreferences("ekta", Context.MODE_PRIVATE).edit().putString("last_alert_id", id).apply()
        _state.value = SosState(
            active = true,
            alertId = id,
            startedAt = System.currentTimeMillis(),
            online = Net.online.value,
            gpsOn = LiveLocation.isGpsOn(app),
            upload = Step.WORKING, uploadNote = "getting your exact location…",
            mesh = Step.WORKING, meshNote = "getting your exact location…",
            contactSms = Step.WORKING, contactNote = "getting your exact location…",
        )
        watch(app, id)
        // GPS on for the whole SOS: works with no internet and no signal.
        LiveLocation.start(app)

        startJob?.cancel()
        startJob = scope.launch {
            val contact = contact(app)
            // 1) A fresh fix (usually a few seconds; GPS needs a view of the sky).
            var loc = LiveLocation.awaitFresh(15_000)
            // 2) None yet: use the last position the phone knows, clearly marked as old,
            //    and keep tracking — the exact position follows as soon as GPS has it.
            if (loc == null) loc = LiveLocation.lastKnown(app)?.also { LiveLocation.offer(it) }
            // 3) Nothing at all (GPS off / brand-new phone): don't wait silently. Text her
            //    contact now, and send everything the moment a position arrives.
            if (loc == null) {
                val msg = "no location yet · " + if (LiveLocation.isGpsOn(app))
                    "searching GPS, go near a window or outside" else "turn on Location (GPS)!"
                _state.update { it.copy(upload = Step.WORKING, uploadNote = msg, mesh = Step.WORKING, meshNote = msg) }
                if (contact != null && SmsOutbox.canSend(app)) {
                    SmsOutbox.send(app, noLocKey(id), contact.number, noLocationText(app))
                    _state.update { it.copy(contactNote = "SOS texted · location follows as soon as GPS finds you") }
                }
                loc = LiveLocation.awaitAny()
            }
            if (_state.value.alertId != id) return@launch
            _state.update { it.copy(location = loc, committeeMessage = sosText(app, loc)) }

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
                        cancelHash = cancelHash, duressHash = duressHash, locHash = locHash,
                    ),
                )
                _state.update { it.copy(meshNote = "looking for Ekta phones in Bluetooth / Wi-Fi range…") }
            } else {
                _state.update { it.copy(mesh = Step.FAILED, meshNote = "Bluetooth / nearby-devices permission not given") }
            }

            // 2. Internet: upload now, or automatically once online (real)
            SosRepository.publishAlert(app, id, loc, cancelHash, duressHash, locHash)
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

            // Nearby pharmacies / shops / police booths within 500 m (Google Places).
            // DEMO: only listed in Settings → Debug panel; nobody is called or messaged.
            NearbyPlaces.search(app, id, loc.latitude, loc.longitude)
            // Women's committee contacts (DEMO: listed in Settings → WOMEN COMMITTEE, never contacted).
            WomenHelp.search(app, id, loc.latitude, loc.longitude)

            // 3. Live location: every move goes to nearby phones (mesh + server).
            startTracking(app, id, sentLoc = loc, contact = contact)

            // 4. SMS to her contact (real) — waits for signal by itself
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

    /**
     * GPS keeps running: each time she moves (or the fix gets more exact) the new
     * position goes to nearby phones over Bluetooth and to the server, so helpers
     * always walk to where she is NOW. Also sends her contact an "exact location" SMS
     * once if the first fix was rough or old.
     */
    private fun startTracking(app: Context, id: String, sentLoc: Location, contact: EmergencyContact?) {
        trackJob?.cancel()
        trackJob = scope.launch {
            val started = System.currentTimeMillis()
            var last = sentLoc
            var lastSentAt = System.currentTimeMillis()
            var exactSmsSent = sentLoc.accuracy <= ROUGH_FIX_M && LiveLocation.ageMs(sentLoc) <= LiveLocation.FRESH_MS
            LiveLocation.best.collect { loc ->
                if (loc == null || _state.value.alertId != id) return@collect
                if (System.currentTimeMillis() - started > TRACK_FOR_MS) {
                    LiveLocation.stop(app)
                    return@collect
                }
                _state.update { if (it.alertId == id) it.copy(location = loc) else it }

                if (!exactSmsSent && contact != null && SmsOutbox.canSend(app) &&
                    loc.accuracy <= EXACT_FIX_M && LiveLocation.ageMs(loc) <= LiveLocation.FRESH_MS
                ) {
                    exactSmsSent = true
                    SmsOutbox.send(app, exactKey(id), contact.number, exactText(app, loc))
                }

                val now = System.currentTimeMillis()
                val moved = distance(last, loc)
                val sharper = loc.accuracy < last.accuracy - 10f && loc.accuracy < last.accuracy * 0.7f
                val due = (moved > maxOf(15f, loc.accuracy) || sharper) && now - lastSentAt >= 10_000 ||
                    (now - lastSentAt >= 60_000 && loc.time > last.time)
                if (!due) return@collect
                last = loc
                lastSentAt = now
                sendLocation(app, id, loc)
            }
        }
    }

    /** One live-location point → mesh (Bluetooth) + server, proven with the right key. */
    private fun sendLocation(app: Context, id: String, loc: Location) {
        val duress = _state.value.status == AlertStatus.DURESS
        val at = System.currentTimeMillis()
        MeshManager.originate(
            app,
            MeshMessage(
                type = MeshMessage.TYPE_STATUS, id = id, sender = SosRepository.deviceId(app),
                lat = loc.latitude, lng = loc.longitude, accuracy = loc.accuracy.toDouble(), sentAt = at,
                senderName = myName(app), contactNumber = contact(app)?.number ?: "",
                status = if (duress) AlertStatus.DURESS else AlertStatus.ACTIVE,
                secret = if (duress) duressKey else locKey,
            ),
        )
        if (duress) SosRepository.updateDuressLocation(id, loc.latitude, loc.longitude, loc.accuracy.toDouble(), at)
        else SosRepository.updateLocation(id, locKey, loc.latitude, loc.longitude, loc.accuracy.toDouble(), at)
    }

    private fun distance(a: Location, b: Location): Float {
        val r = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, r)
        return r[0]
    }

    private fun stopTracking(app: Context) {
        startJob?.cancel()
        trackJob?.cancel()
        LiveLocation.stop(app)
    }

    /**
     * She typed a PIN on the cancel pad.
     *  NORMAL → really cancel: tell everyone (mesh + server) and text "I'm safe".
     *  DURESS → her screen shows the same "Alert cancelled", but the alert keeps running
     *           silently and everyone is told she was forced to cancel.
     *  WRONG  → nothing changes.
     */
    fun cancelWithPin(context: Context, pin: String): PinStore.Result {
        val app = context.applicationContext
        val result = PinStore.check(app, pin)
        val s = _state.value
        if (!s.active) return result // nothing running: the screen still says "Alert cancelled"
        when (result) {
            // Works from a normal alert and from a silent duress alert ("I'm safe now").
            PinStore.Result.NORMAL -> cancelForReal(app)
            // Duress PIN again while already in duress: keep running silently, change nothing.
            PinStore.Result.DURESS -> if (s.status != AlertStatus.DURESS) goDuress(app)
            PinStore.Result.WRONG -> Unit
        }
        return result
    }

    private fun cancelForReal(app: Context) {
        val s = _state.value
        val id = s.alertId
        stopTracking(app)
        SmsOutbox.cancel(noLocKey(id))
        SmsOutbox.cancel(exactKey(id))
        liveJob?.cancel()
        watchJob?.cancel()
        SmsOutbox.cancel(firstKey(id))
        SmsOutbox.cancel(updateKey(id))
        SmsOutbox.cancel(duressSmsKey(id))
        MeshManager.drop("sos:$id") // stop spreading the SOS
        finished += id
        broadcastStatus(app, id, AlertStatus.CANCELLED, cancelKey, LiveLocation.best.value ?: s.location)
        val safeText = "Ekta: ${myName(app).ifBlank { "She" }} is safe, alert cancelled (cancelled with her PIN). Thank you."
        contact(app)?.let { SmsOutbox.send(app, safeKey(id), it.number, safeText) }
        DemoNotify.committee(safeText)
        DemoNotify.police(safeText)
        _state.value = SosState()
    }

    private fun goDuress(app: Context) {
        val s = _state.value
        val id = s.alertId
        // Her phone: looks exactly like a normal cancel. No sound, vibration or text.
        _state.update { it.copy(hidden = true, status = AlertStatus.DURESS, callEnded = true) }
        // Everyone else: she was forced to cancel. (The SOS keeps spreading too.)
        broadcastStatus(app, id, AlertStatus.DURESS, duressKey, LiveLocation.best.value ?: s.location)
        DemoNotify.committee(duressText(app, s.location))
        DemoNotify.police(duressText(app, s.location))
        contact(app)?.let { c ->
            SmsOutbox.send(app, duressSmsKey(id), c.number, duressText(app, s.location))
            startDuressTracking(app, id, c)
        } ?: startDuressTracking(app, id, null)
    }

    /** Send a cancel / duress update through the Bluetooth mesh and the server. */
    private fun broadcastStatus(app: Context, id: String, status: String, key: String, loc: Location?) {
        val now = System.currentTimeMillis()
        val lat = loc?.latitude ?: 0.0
        val lng = loc?.longitude ?: 0.0
        val acc = loc?.accuracy?.toDouble() ?: 0.0
        MeshManager.originate(
            app,
            MeshMessage(
                type = MeshMessage.TYPE_STATUS, id = id, sender = SosRepository.deviceId(app),
                lat = lat, lng = lng, accuracy = acc, sentAt = now,
                senderName = myName(app), contactNumber = contact(app)?.number ?: "",
                status = status, secret = key,
            ),
        )
        SosRepository.updateStatus(id, status, key, lat, lng, acc, now) // queued if offline
    }

    /**
     * Duress: keep sending her location silently. Bluetooth + server points come from
     * the live tracker (now marked "duress"); her contact gets an SMS every 2 min.
     */
    private fun startDuressTracking(app: Context, id: String, contact: EmergencyContact?) {
        liveJob?.cancel()
        liveJob = scope.launch {
            val started = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() - started < DURESS_TRACK_MS) {
                delay(LIVE_UPDATE_EVERY_MS)
                val loc = LiveLocation.best.value ?: continue
                contact?.let {
                    SmsOutbox.send(app, updateKey(id), it.number, "EKTA DURESS update - live location: ${mapsLink(loc)}${accuracyNote(loc)} Do NOT call her phone.")
                }
            }
            // Tracking window over: end quietly.
            if (_state.value.alertId == id) {
                stopTracking(app)
                watchJob?.cancel()
                _state.value = SosState()
            }
        }
    }

    private fun duressText(app: Context, loc: Location?): String {
        val who = myName(app).ifBlank { "She" }
        val where = loc?.let { " Live location: ${mapsLink(it)}${accuracyNote(it)}" } ?: ""
        return "EKTA DURESS: $who was forced to cancel her SOS and may still be in danger. Do NOT call her phone.$where"
    }

    fun endDemoCall() = _state.update { it.copy(callEnded = true) }

    /** A gateway phone got our alert online (and maybe texted our contact). */
    fun onAck(ack: MeshMessage) {
        // A gateway texted her contact about the cancel / duress: don't text it again.
        if (ack.sms == MeshMessage.SMS_SENT && ack.status == AlertStatus.CANCELLED) SmsOutbox.cancel(safeKey(ack.id))
        if (ack.sms == MeshMessage.SMS_SENT && ack.status == AlertStatus.DURESS) SmsOutbox.cancel(duressSmsKey(ack.id))
        if (ack.status.isNotEmpty()) return
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
    private fun safeKey(id: String) = "safe-$id"
    private fun duressSmsKey(id: String) = "duress-$id"
    private fun noLocKey(id: String) = "noloc-$id"
    private fun exactKey(id: String) = "exact-$id"

    /** " (accurate to 8 m)", or how old the position is if it isn't fresh. */
    private fun accuracyNote(loc: Location): String {
        // Plain GSM characters only (no "±"): keeps every SMS in the basic alphabet,
        // which every phone and network sends reliably.
        val acc = " (accurate to ${loc.accuracy.toInt().coerceAtLeast(1)} m)"
        val age = LiveLocation.ageMs(loc)
        return if (age <= LiveLocation.FRESH_MS) acc
        else " (last known position, ${age / 60_000} min old - exact location follows)"
    }

    private fun noLocationText(context: Context): String {
        val who = myName(context).ifBlank { "I" }
        val head = if (who == "I") "SOS! I need help." else "SOS from $who! I need help."
        return "$head My phone is still finding my exact location - it will be texted to you in a moment. (sent by Ekta SOS)"
    }

    private fun exactText(context: Context, loc: Location): String {
        val who = myName(context).ifBlank { "My" }
        val whose = if (who == "My") "My" else "$who's"
        return "Ekta SOS: $whose EXACT location now: ${mapsLink(loc)}${accuracyNote(loc)}"
    }

    private fun mapsLink(loc: Location) =
        String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", loc.latitude, loc.longitude)

    fun sosText(context: Context, loc: Location): String {
        val who = myName(context).ifBlank { "I" }
        return if (who == "I") "SOS! I need help. My live location: ${mapsLink(loc)}${accuracyNote(loc)} (sent by Ekta SOS)"
        else "SOS from $who! I need help. My live location: ${mapsLink(loc)}${accuracyNote(loc)} (sent by Ekta SOS)"
    }

    /** Keeps the screen in sync with network, signal and how far the mesh has spread. */
    private fun watch(app: Context, id: String) {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive && _state.value.alertId == id) {
                Net.refresh()
                val gps = LiveLocation.isGpsOn(app)
                if (gps) LiveLocation.ensureGps(app)
                val reached = MeshManager.reached.value[id] ?: 0
                _state.update { s ->
                    s.copy(
                        online = Net.online.value,
                        gpsOn = gps,
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
                val loc = LiveLocation.best.value ?: continue
                // Same key → an unsent older update is replaced by this newer location.
                SmsOutbox.send(app, updateKey(id), contact.number, "Ekta SOS update - my live location: ${mapsLink(loc)}${accuracyNote(loc)}") {
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
