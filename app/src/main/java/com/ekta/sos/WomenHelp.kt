package com.ekta.sos

import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Settings → WOMEN COMMITTEE: who the women's committee SMS would go to.
 *  - National women's helplines and the National Commission for Women (always shown,
 *    need no internet).
 *  - Nearby women's offices (women's commission, women cell, One Stop Centre, women's
 *    NGOs and welfare offices) with a phone number, from TomTom (free).
 *
 * HACKATHON DEMO: nobody here is ever called or messaged.
 */
object WomenHelp {
    const val OFFICE_RADIUS_M = 15_000
    private const val PREF_LAST = "women_last"
    private const val PREF_TOMTOM_URL = "women_tomtom_url" // debug builds only: test server, or "off"

    data class Contact(val name: String, val kind: String, val distanceM: Int?, val phone: String, val address: String?)

    /** Public national numbers (verify on ncw.nic.in before any real use). */
    val HELPLINES = listOf(
        Contact("Women Helpline (24x7)", "National helpline", null, "181", null),
        Contact("Women Helpline (Police)", "National helpline", null, "1091", null),
        Contact("National Commission for Women", "National helpline", null, "7827170170", null),
    )

    data class Lookup(
        val status: NearbyPlaces.Status = NearbyPlaces.Status.IDLE,
        val message: String = "",
        val alertId: String = "",
        val lat: Double = 0.0,
        val lng: Double = 0.0,
        val at: Long = 0L,
        val offices: List<Contact> = emptyList(),
        val police: List<Contact> = emptyList(),
        val error: String? = null,
    ) {
        /** How many numbers the (demo) women's committee SMS would go to. */
        val total: Int get() = HELPLINES.size + offices.size
    }

    private val noCrash = CoroutineExceptionHandler { _, e ->
        _state.value = _state.value.copy(status = NearbyPlaces.Status.ERROR, message = "Lookup failed (${e.javaClass.simpleName})")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + noCrash)
    private val _state = MutableStateFlow(Lookup())
    val state: StateFlow<Lookup> = _state
    private var loaded = false

    private val WOMEN = Regex("women|woman|mahila|one stop cent|sakhi|aayog|nari|stree|anti.?romeo|pink", RegexOption.IGNORE_CASE)
    private val NOT_HELP = Regex(
        "cloth|boutique|salon|parlou?r|beauty|fashion|store|shop|mart|hostel|\\bpg\\b|clinic|hospital|college|school|gym|fitness|park|kosh",
        RegexOption.IGNORE_CASE,
    )

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        prefs(context).getString(PREF_LAST, null)?.let { s -> try { _state.value = fromJson(JSONObject(s)) } catch (_: Exception) {} }
    }

    /** Debug builds only: point the TomTom lookup at a test server ("off" = skip it). */
    fun setTestTomTomUrl(context: Context, url: String?) {
        if (!BuildConfig.DEBUG) return
        prefs(context).edit().apply { if (url.isNullOrBlank()) remove(PREF_TOMTOM_URL) else putString(PREF_TOMTOM_URL, url) }.apply()
    }

    private fun tomTomBase(context: Context): String? {
        val o = if (BuildConfig.DEBUG) prefs(context).getString(PREF_TOMTOM_URL, null) else null
        if (o == "off" || BuildConfig.TOMTOM_API_KEY.isBlank()) return null
        return o ?: TomTomPlaces.DEFAULT_BASE_URL
    }

    fun search(context: Context, alertId: String, lat: Double, lng: Double) {
        val app = context.applicationContext
        load(app)
        val base = Lookup(alertId = alertId, lat = lat, lng = lng, at = System.currentTimeMillis())
        val tt = tomTomBase(app)
        if (tt == null) {
            publish(app, base.copy(status = NearbyPlaces.Status.DONE, message = "National helplines only (no TomTom key for nearby offices)"))
            return
        }
        Net.init(app)
        Net.refresh()
        if (!Net.online.value) {
            publish(app, base.copy(status = NearbyPlaces.Status.WAITING_INTERNET, message = "No internet · national helplines shown; nearby search when online"))
            Net.onOnline {
                val s = _state.value
                if (s.status == NearbyPlaces.Status.WAITING_INTERNET && s.at == base.at) search(app, s.alertId, s.lat, s.lng)
            }
            return
        }
        publish(app, base.copy(status = NearbyPlaces.Status.SEARCHING, message = "Searching nearby women's offices…"))
        scope.launch {
            val result = withContext(Dispatchers.IO) { lookup(tt, BuildConfig.TOMTOM_API_KEY, base) }
            if (_state.value.at == base.at) publish(app, result)
        }
    }

    internal fun lookup(baseUrl: String, key: String, base: Lookup): Lookup {
        return try {
            // 1) Women's offices: government, NGO, welfare, community, legal, police — names that say women's help.
            val offices = nearby(baseUrl, key, base, "7367,9152,9153,7363,9352023,7322", OFFICE_RADIUS_M)
                .filter { WOMEN.containsMatchIn(it.name) && !NOT_HELP.containsMatchIn(it.name) }
                .map { it.copy(kind = "Women's office") }
            base.copy(
                status = NearbyPlaces.Status.DONE,
                message = "${offices.size} nearby women's office${if (offices.size == 1) "" else "s"} within ${OFFICE_RADIUS_M / 1000} km",
                offices = offices,
            )
        } catch (e: IOException) {
            base.copy(status = NearbyPlaces.Status.ERROR, message = "Couldn't reach TomTom (${e.javaClass.simpleName}) · national helplines still shown")
        } catch (e: Exception) {
            base.copy(status = NearbyPlaces.Status.ERROR, message = "Lookup failed (${e.message ?: e.javaClass.simpleName}) · national helplines still shown")
        }
    }

    /** TomTom nearby search; only places with a phone number, nearest first. */
    private fun nearby(baseUrl: String, key: String, base: Lookup, cats: String, radius: Int): List<Contact> {
        val url = "${baseUrl.trimEnd('/')}/search/2/nearbySearch/.json?key=" + URLEncoder.encode(key, "UTF-8") +
            String.format(Locale.US, "&lat=%.6f&lon=%.6f", base.lat, base.lng) +
            "&radius=$radius&limit=100&countrySet=IN&language=en-GB&categorySet=" + URLEncoder.encode(cats, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val arr = json.optJSONArray("results") ?: return emptyList()
            val out = ArrayList<Contact>()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val poi = r.optJSONObject("poi") ?: continue
                val phone = poi.optString("phone").trim()
                val name = poi.optString("name").trim()
                if (phone.isEmpty() || name.isEmpty()) continue
                out += Contact(name, "", Math.round(r.optDouble("dist")).toInt(), phone,
                    r.optJSONObject("address")?.optString("freeformAddress")?.ifBlank { null })
            }
            return out.distinctBy { it.name + it.phone }.sortedBy { it.distanceM }
        } finally {
            conn.disconnect()
        }
    }

    private fun publish(app: Context, l: Lookup) {
        _state.value = l
        try { prefs(app).edit().putString(PREF_LAST, toJson(l).toString()).apply() } catch (_: Exception) {}
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ekta", Context.MODE_PRIVATE)

    private fun toJson(l: Lookup) = JSONObject().apply {
        put("status", l.status.name); put("message", l.message); put("alertId", l.alertId)
        put("lat", l.lat); put("lng", l.lng); put("at", l.at)
        put("offices", JSONArray(l.offices.map { c(it) })); put("police", JSONArray(l.police.map { c(it) }))
    }

    private fun c(x: Contact) = JSONObject().put("n", x.name).put("k", x.kind).put("d", x.distanceM ?: -1).put("p", x.phone).put("a", x.address ?: "")

    private fun fromJson(o: JSONObject): Lookup {
        fun list(k: String) = (o.optJSONArray(k) ?: JSONArray()).let { a ->
            (0 until a.length()).map { i ->
                val j = a.getJSONObject(i)
                Contact(j.optString("n"), j.optString("k"), j.optInt("d").takeIf { it >= 0 }, j.optString("p"), j.optString("a").ifBlank { null })
            }
        }
        val st = NearbyPlaces.Status.entries.firstOrNull { it.name == o.optString("status") } ?: NearbyPlaces.Status.IDLE
        return Lookup(
            status = if (st == NearbyPlaces.Status.SEARCHING) NearbyPlaces.Status.ERROR else st,
            message = o.optString("message"), alertId = o.optString("alertId"),
            lat = o.optDouble("lat"), lng = o.optDouble("lng"), at = o.optLong("at"),
            offices = list("offices"), police = list("police"),
        )
    }
}
