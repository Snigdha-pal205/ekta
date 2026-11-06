package com.ekta.sos

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The "first phone with internet" role. When an SOS reaches us over the mesh and we're
 * online, we upload it. Firestore only lets the *first* upload of an alert id succeed,
 * so exactly one phone becomes the gateway — and only that phone texts her contact.
 * If we're offline we keep it and try again the moment we get internet.
 */
object Gateway {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val waiting = LinkedHashMap<String, MeshMessage>() // alert id -> SOS to upload
    private val busy = HashSet<String>()
    private val handled = HashSet<String>()                    // alerts we were gateway for
    private lateinit var app: Context

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        Net.onOnline { scope.launch { flush() } }
    }

    private val waitingStatus = LinkedHashMap<String, MeshMessage>() // msg key -> cancel/duress to upload

    /** A cancel / duress update from someone else arrived over the mesh. */
    fun considerStatus(context: Context, msg: MeshMessage) {
        init(context)
        if (msg.type != MeshMessage.TYPE_STATUS) return
        if (msg.sender == SosRepository.deviceId(app)) return
        if (msg.status == AlertStatus.CANCELLED) waiting.remove(msg.id) // cancelled: don't upload the SOS any more
        if (msg.status == AlertStatus.ACTIVE) {
            // Live location: only the newest point matters.
            if (waitingStatus.values.any { it.id == msg.id && it.status == msg.status && it.sentAt >= msg.sentAt }) return
            waitingStatus.values.removeAll { it.id == msg.id && it.status == msg.status }
        }
        waitingStatus.putIfAbsent(msg.key, msg)
        scope.launch { flush() }
        startRetryLoop()
    }

    private suspend fun flushStatus() {
        for (msg in waitingStatus.values.toList()) {
            if (!busy.add(msg.key)) continue
            if (msg.status == AlertStatus.ACTIVE) {
                // Her new live location (no SMS: her own phone texts updates).
                try {
                    withTimeout(20_000) {
                        SosRepository.updateLocation(msg.id, msg.secret, msg.lat, msg.lng, msg.accuracy, msg.sentAt).await()
                    }
                    waitingStatus.remove(msg.key)
                } catch (e: FirebaseFirestoreException) {
                    // NOT_FOUND: her SOS isn't online yet, retry later. Anything else
                    // (cancelled, older than what's stored): drop it.
                    if (e.code != FirebaseFirestoreException.Code.NOT_FOUND) waitingStatus.remove(msg.key)
                } catch (_: Exception) {
                } finally {
                    busy.remove(msg.key)
                }
                continue
            }
            try {
                withTimeout(20_000) {
                    SosRepository.updateStatus(
                        msg.id, msg.status, msg.secret, msg.lat, msg.lng, msg.accuracy, msg.sentAt,
                    ).await()
                }
                // We were first to get this cancel/duress online → we tell her contact.
                waitingStatus.remove(msg.key)
                statusGateway(msg)
            } catch (e: FirebaseFirestoreException) {
                when (e.code) {
                    // Already cancelled/duress online. For duress, this is a later location point.
                    FirebaseFirestoreException.Code.PERMISSION_DENIED -> {
                        waitingStatus.remove(msg.key)
                        if (msg.status == AlertStatus.DURESS) {
                            try {
                                SosRepository.updateDuressLocation(msg.id, msg.lat, msg.lng, msg.accuracy, msg.sentAt).await()
                            } catch (_: Exception) {
                            }
                        }
                    }
                    // Her SOS isn't online yet: upload it first, the status follows on the next try.
                    FirebaseFirestoreException.Code.NOT_FOUND -> Unit
                    else -> Unit
                }
            } catch (_: Exception) {
            } finally {
                busy.remove(msg.key)
            }
        }
    }

    private fun statusGateway(msg: MeshMessage) {
        val me = SosRepository.deviceId(app)
        fun ack(sms: String) = MeshManager.originate(
            app, MeshMessage(type = MeshMessage.TYPE_ACK, id = msg.id, gateway = me, sms = sms, status = msg.status),
        )
        if (msg.contactNumber.isBlank() || !SmsOutbox.canSend(app)) return ack(MeshMessage.SMS_NONE)
        val who = msg.senderName.ifBlank { "Your contact" }
        val link = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", msg.lat, msg.lng)
        val text = if (msg.status == AlertStatus.CANCELLED) {
            "EKTA: $who is safe, alert cancelled (she cancelled it with her PIN). (relayed by a nearby Ekta phone)"
        } else {
            "EKTA DURESS: $who was forced to cancel her SOS and may still be in danger. Do NOT call her phone. " +
                "Last location: $link (relayed by a nearby Ekta phone)"
        }
        DemoNotify.committee(text)
        DemoNotify.police(text)
        ack(MeshMessage.SMS_QUEUED)
        SmsOutbox.send(app, "gw-${msg.status}-${msg.id}", msg.contactNumber, text) { ack(MeshMessage.SMS_SENT) }
    }

    /** An SOS from someone else arrived over the mesh. */
    fun consider(context: Context, msg: MeshMessage) {
        init(context)
        if (msg.type != MeshMessage.TYPE_SOS) return
        if (msg.sender == SosRepository.deviceId(app)) return
        if (System.currentTimeMillis() - msg.sentAt > RELAY_MAX_AGE_MS) return
        waiting.putIfAbsent(msg.id, msg)
        scope.launch { flush() }
        startRetryLoop()
    }

    /** While we hold alerts to upload, re-check every 30 s (don't rely only on events). */
    private var retryJob: kotlinx.coroutines.Job? = null

    private fun startRetryLoop() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (waiting.isNotEmpty() || waitingStatus.isNotEmpty()) {
                kotlinx.coroutines.delay(30_000)
                waiting.values.removeAll { System.currentTimeMillis() - it.sentAt > RELAY_MAX_AGE_MS }
                waitingStatus.values.removeAll { System.currentTimeMillis() - it.sentAt > RELAY_MAX_AGE_MS }
                Net.refresh()
                flush()
            }
        }
    }

    private suspend fun flush() {
        if (!Net.online.value) return
        flushSos()
        flushStatus()
    }

    private suspend fun flushSos() {
        for (msg in waiting.values.toList()) {
            if (!busy.add(msg.id)) continue
            val me = SosRepository.deviceId(app)
            try {
                withTimeout(20_000) { SosRepository.publishRelayed(msg, me) }
                waiting.remove(msg.id)
                if (handled.add(msg.id)) becameGateway(msg)
            } catch (e: FirebaseFirestoreException) {
                // PERMISSION_DENIED = the alert already exists. Usually another phone (or
                // she herself) got it online first — but it may be our own earlier upload
                // that timed out here and went through later. Check who the gateway is.
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    waiting.remove(msg.id)
                    if (SosRepository.gatewayOf(msg.id) == me && handled.add(msg.id)) becameGateway(msg)
                }
            } catch (_: Exception) {
                // timeout / flaky network: keep it and retry on the next "online" event
            } finally {
                busy.remove(msg.id)
            }
        }
    }

    private fun becameGateway(msg: MeshMessage) {
        val me = SosRepository.deviceId(app)
        fun ack(status: String) = MeshManager.originate(
            app, MeshMessage(type = MeshMessage.TYPE_ACK, id = msg.id, gateway = me, sms = status),
        )

        if (msg.contactNumber.isNotBlank() && SmsOutbox.canSend(app)) {
            ack(MeshMessage.SMS_QUEUED)
            SmsOutbox.send(app, "gw-${msg.id}", msg.contactNumber, gatewaySms(msg)) {
                ack(MeshMessage.SMS_SENT)
            }
        } else {
            ack(MeshMessage.SMS_NONE)
        }
        tellThisPhone(msg)
    }

    private fun gatewaySms(msg: MeshMessage): String {
        val who = msg.senderName.ifBlank { "Someone who saved you as their emergency contact" }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(msg.sentAt))
        val link = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", msg.lat, msg.lng)
        return "EKTA SOS: $who pressed SOS at $time in an area with no network and needs help. " +
            "Location: $link (relayed by a nearby Ekta phone)"
    }

    /** Be open with the gateway phone's owner: their phone sent an SMS for someone. */
    private fun tellThisPhone(msg: MeshMessage) {
        AlertListenerService.createChannels(app)
        val n = NotificationCompat.Builder(app, AlertListenerService.RELAY_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sos)
            .setContentTitle("Your phone relayed an SOS")
            .setContentText("Someone nearby had no network. Ekta put their alert online and texted their emergency contact.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Someone nearby had no network. Ekta passed their SOS through ${msg.hops} phone(s) to yours, " +
                        "put it online, and texted their emergency contact from your SIM.",
                ),
            )
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(("gw" + msg.id).hashCode(), n)
    }
}
