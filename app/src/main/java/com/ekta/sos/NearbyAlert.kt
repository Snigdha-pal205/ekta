package com.ekta.sos

import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * What nearby pharmacies, shops and police booths are told when she presses SOS.
 *
 * TODAY (hackathon): DEMO. [REAL_MODE] is false, so nothing is called or texted; the
 * SOS page only shows the places as "called (demo)" and the message they would get.
 *
 * FOR REAL: set [REAL_MODE] = true. Then, once per SOS, the [MAX_REAL] nearest places
 * that have a mobile number get an SMS from her SIM with the message and a map link.
 * Automatic voice calls with a recorded message need a paid calling service
 * (for example Exotel or Twilio, through a small server); a phone app cannot play a
 * recording into a normal call by itself. That part is not built yet.
 */
object NearbyAlert {
    /** Keep false for the demo. True = really text nearby places. */
    const val REAL_MODE = false

    /** Most places contacted for real per SOS (nearest first), so it can't turn into spam. */
    const val MAX_REAL = 10

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main +
        CoroutineExceptionHandler { _, e -> android.util.Log.w("EktaNearbyAlert", "failed", e) })

    /** "Marvel Vinyls Limited, Connaught Place": the nearest named place, plus its area if known. */
    fun landmark(places: List<NearbyPlaces.Place>): String? {
        val p = places.minByOrNull { it.distanceM } ?: return null
        val area = p.address?.split(",")?.map { it.trim() }
            ?.firstOrNull { it.length > 3 && it.any(Char::isLetter) && !it.equals(p.name, true) && !it.contains("Delhi", true) }
        return if (area != null) "${p.name}, $area" else p.name
    }

    /** What the recorded voice call would say. */
    fun voiceText(landmark: String?): String =
        "A woman near you needs help. Location: ${landmark ?: "near you"}. Please help or call 112."

    /** The SMS: the same words plus a map link to her exact location. */
    fun smsText(landmark: String?, lat: Double, lng: Double): String =
        voiceText(landmark) + " Map: " +
            String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lng) + " (sent by Ekta SOS)"

    /** Indian mobile numbers in a phone field ("+91 98189 43035, 011 2332 4064" -> +919818943035). */
    fun mobileNumbers(phone: String?): List<String> =
        phone.orEmpty().split(',', ';', '/').mapNotNull { part ->
            val d = part.filter(Char::isDigit).let { if (it.length > 10) it.takeLast(10) else it }
            if (d.length == 10 && d[0] in '6'..'9') "+91$d" else null
        }.distinct()

    /**
     * Called when SOS starts. Waits for the nearby list of this alert, then (only in
     * REAL_MODE, and only while the SOS is still running) texts the nearest places.
     */
    fun onSos(context: Context, alertId: String) {
        if (!REAL_MODE) return // DEMO: never call or message anyone
        val app = context.applicationContext
        scope.launch {
            val lookup = NearbyPlaces.state.first { it.alertId == alertId && it.status == NearbyPlaces.Status.DONE }
            val sos = SosSession.state.value
            if (!sos.active || sos.alertId != alertId || !SmsOutbox.canSend(app)) return@launch
            val text = smsText(landmark(lookup.places), sos.location?.latitude ?: lookup.lat, sos.location?.longitude ?: lookup.lng)
            lookup.places.filter { !it.far }.sortedBy { it.distanceM }
                .flatMap { p -> mobileNumbers(p.phone).take(1) }
                .distinct().take(MAX_REAL)
                .forEach { n -> SmsOutbox.send(app, "nearby:$alertId:$n", n, text) }
        }
    }
}
