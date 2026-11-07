package com.ekta.sos

import android.content.Context
import android.location.Location
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * When SOS is pressed: find pharmacies, shops and police booths within 1 km of her,
 * with name and phone number. Source: free OpenStreetMap data by default (no key);
 * Google Places API (New) "Nearby Search" when a Google key has been added.
 *
 * HACKATHON DEMO: nobody on this list is ever called or messaged. The list is only
 * shown in Settings → Debug panel. (No code here dials, texts or opens any app.)
 */
object NearbyPlaces {
    const val RADIUS_M = 1000.0
    /** "1 km" — how the search radius is written on screen. */
    val RADIUS_LABEL: String get() = if (RADIUS_M >= 1000) "${(RADIUS_M / 1000).toInt()} km" else "${RADIUS_M.toInt()} m"
    private const val DEFAULT_BASE_URL = "https://places.googleapis.com"
    private const val PREFS = "ekta"
    private const val PREF_KEY = "places_api_key"        // key pasted in the Debug panel
    private const val PREF_BASE_URL = "places_base_url"  // debug builds only: test server
    private const val PREF_OSM_URLS = "osm_base_urls"     // debug builds only: test servers (comma-separated)
    const val SOURCE_OSM = "OpenStreetMap (free, no key)"
    const val SOURCE_FREE = "Ola Maps + OpenStreetMap (free)"
    private const val PREF_OLA_URL = "ola_base_url"        // debug builds only: test server, or "off"
    private const val PREF_TOMTOM_URL = "tomtom_base_url"  // debug builds only: test server, or "off"
    const val SOURCE_GOOGLE = "Google Places"
    private const val PREF_LAST = "nearby_last"          // last lookup, so the panel survives restarts

    /** One request per category, so a problem with one still shows the others. */
    enum class Category(val label: String, val includedTypes: List<String>) {
        POLICE("Police booth", listOf("police")),
        PHARMACY("Pharmacy", listOf("pharmacy", "drugstore")),
        SHOP(
            "Shop",
            listOf("convenience_store", "grocery_store", "supermarket", "department_store", "general_store", "market", "store"),
        ),
    }

    /** Only these fields are requested (phone numbers are in Google's "Enterprise" tier). */
    const val FIELD_MASK = "places.id,places.displayName,places.primaryType,places.types,places.location," +
        "places.formattedAddress,places.nationalPhoneNumber,places.internationalPhoneNumber"

    data class Place(
        val id: String,
        val name: String,
        val category: Category,
        val lat: Double,
        val lng: Double,
        val distanceM: Int,
        val phone: String?,
        val address: String?,
    ) {
        /** Further than the main radius: shown under "nearest further away". */
        val far: Boolean get() = distanceM > RADIUS_M
    }

    /** Most places listed inside the main radius, and how many of each type to add from further away. */
    const val MAX_NEAR = 40
    const val FAR_PER_TYPE = 3

    enum class Status { IDLE, WAITING_INTERNET, SEARCHING, RETRYING, DONE, ERROR }

    data class Lookup(
        val status: Status = Status.IDLE,
        val message: String = "",
        val alertId: String = "",
        val lat: Double = 0.0,
        val lng: Double = 0.0,
        val at: Long = 0L,
        val places: List<Place> = emptyList(),
        val errors: List<String> = emptyList(), // per-category problems (others may still be listed)
        val source: String = "",
    )

    /** Nothing in this demo feature may ever crash the app (and stop SOS alerts). */
    private val noCrash = CoroutineExceptionHandler { _, e ->
        android.util.Log.w("EktaPlaces", "nearby lookup failed", e)
        _state.value = _state.value.copy(status = Status.ERROR, message = "Lookup failed (${e.javaClass.simpleName}) · tap SEARCH AGAIN")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + noCrash)
    private val _state = MutableStateFlow(Lookup())
    val state: StateFlow<Lookup> = _state
    private var loaded = false
    private var waitingForNet = false
    private var retries = 0           // automatic retries for the current lookup
    private const val MAX_RETRIES = 3
    private const val RETRY_AFTER_MS = 30_000L

    /** The key to use: one pasted in the Debug panel, else the one built into the app. */
    fun apiKey(context: Context): String =
        prefs(context).getString(PREF_KEY, null)?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.PLACES_API_KEY

    fun saveApiKey(context: Context, key: String) =
        prefs(context).edit().putString(PREF_KEY, key.trim()).apply()

    /** Debug builds only: point at a local test server instead of Google. */
    fun setTestBaseUrl(context: Context, url: String?) {
        if (!BuildConfig.DEBUG) return
        prefs(context).edit().apply { if (url.isNullOrBlank()) remove(PREF_BASE_URL) else putString(PREF_BASE_URL, url) }.apply()
    }

    /** Debug builds only: point the OpenStreetMap lookup at local test servers. */
    fun setTestOsmUrls(context: Context, urls: String?) {
        if (!BuildConfig.DEBUG) return
        prefs(context).edit().apply { if (urls.isNullOrBlank()) remove(PREF_OSM_URLS) else putString(PREF_OSM_URLS, urls) }.apply()
    }

    /** Debug builds only: point Ola at a test server ("off" = don't use Ola). */
    fun setTestOlaUrl(context: Context, url: String?) {
        if (!BuildConfig.DEBUG) return
        prefs(context).edit().apply { if (url.isNullOrBlank()) remove(PREF_OLA_URL) else putString(PREF_OLA_URL, url) }.apply()
    }

    /** Debug builds only: point TomTom at a test server ("off" = don't use TomTom). */
    fun setTestTomTomUrl(context: Context, url: String?) {
        if (!BuildConfig.DEBUG) return
        prefs(context).edit().apply { if (url.isNullOrBlank()) remove(PREF_TOMTOM_URL) else putString(PREF_TOMTOM_URL, url) }.apply()
    }

    /** TomTom base URL, or null when TomTom isn't set up (no key) or switched off for a test. */
    private fun tomTomBase(context: Context): String? {
        val override = if (BuildConfig.DEBUG) prefs(context).getString(PREF_TOMTOM_URL, null) else null
        if (override == "off") return null
        if (BuildConfig.TOMTOM_API_KEY.isBlank()) return null
        return override ?: TomTomPlaces.DEFAULT_BASE_URL
    }

    /** Ola base URL, or null when Ola isn't set up (no key) or switched off for a test. */
    private fun olaBase(context: Context): String? {
        val override = if (BuildConfig.DEBUG) prefs(context).getString(PREF_OLA_URL, null) else null
        if (override == "off") return null
        if (BuildConfig.OLA_MAPS_API_KEY.isBlank()) return null
        return override ?: OlaPlaces.DEFAULT_BASE_URL
    }

    private fun osmServers(context: Context): List<String> =
        (if (BuildConfig.DEBUG) prefs(context).getString(PREF_OSM_URLS, null) else null)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: OsmPlaces.SERVERS

    private fun baseUrl(context: Context): String =
        (if (BuildConfig.DEBUG) prefs(context).getString(PREF_BASE_URL, null) else null) ?: DEFAULT_BASE_URL

    /** Show the last lookup (also after the app restarts). */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        prefs(context).getString(PREF_LAST, null)?.let { s ->
            try { _state.value = fromJson(JSONObject(s)) } catch (_: Exception) {}
        }
    }

    /**
     * Look up places around [lat],[lng] for alert [alertId]. Runs in the background;
     * with no internet it waits and runs as soon as the phone is online.
     */
    fun search(context: Context, alertId: String, lat: Double, lng: Double) {
        retries = 0
        doSearch(context.applicationContext, alertId, lat, lng)
    }

    private fun doSearch(app: Context, alertId: String, lat: Double, lng: Double) {
        load(app)
        val key = apiKey(app)
        // No Google key → free OpenStreetMap data (no key, no billing needed).
        val ola = olaBase(app)
        val tomTom = tomTomBase(app)
        val source = when {
            key.isNotBlank() -> SOURCE_GOOGLE
            ola == null && tomTom == null -> SOURCE_OSM
            else -> listOfNotNull(tomTom?.let { "TomTom" }, ola?.let { "Ola Maps" }, "OpenStreetMap").joinToString(" + ") + " (free)"
        }
        val base = Lookup(alertId = alertId, lat = lat, lng = lng, at = System.currentTimeMillis(), source = source)
        Net.init(app)
        Net.refresh()
        if (!Net.online.value) {
            publish(app, base.copy(status = Status.WAITING_INTERNET, message = "No internet · will search as soon as you're online"))
            waitUntilOnline(app)
            return
        }
        publish(app, base.copy(status = Status.SEARCHING, message = "Searching within $RADIUS_LABEL…"))
        scope.launch {
            val result = if (source == SOURCE_GOOGLE) runSearch(baseUrl(app), key, base) else runOsm(osmServers(app), base, ola, tomTom)
            // A newer search (or a different SOS) may have started meanwhile.
            val cur = _state.value
            if (cur.alertId == alertId && cur.at == base.at) {
                publish(app, result)
                if (result.status == Status.WAITING_INTERNET || result.status == Status.RETRYING) {
                    Net.refresh()
                    if (!Net.online.value) {
                        waitUntilOnline(app)
                    } else if (retries < MAX_RETRIES) {
                        // Online but the server didn't answer in time: try again shortly.
                        retries++
                        kotlinx.coroutines.delay(RETRY_AFTER_MS)
                        val now = _state.value
                        if (now.alertId == alertId && now.at == result.at) doSearch(app, alertId, lat, lng)
                    } else {
                        publish(app, result.copy(status = Status.ERROR, message = "No answer from ${source.substringBefore(" (")} · tap SEARCH AGAIN"))
                    }
                }
            }
        }
    }

    private fun waitUntilOnline(app: Context) {
        if (waitingForNet) return
        waitingForNet = true
        Net.onOnline {
            val s = _state.value
            if (waitingForNet && s.status == Status.WAITING_INTERNET) {
                waitingForNet = false
                doSearch(app, s.alertId, s.lat, s.lng)
            }
        }
    }

    /**
     * Free sources: OpenStreetMap (one request for all three kinds) and, when set up,
     * Ola Maps (one quick request per type) at the same time. Ola knows more places,
     * OpenStreetMap more phone numbers; the same place from both is merged into one.
     */
    internal suspend fun runOsm(servers: List<String>, base: Lookup, olaBase: String? = null, tomTomBase: String? = null): Lookup {
        val (osm, others) = coroutineScope {
            val o = async(Dispatchers.IO) { OsmPlaces.search(servers, base.lat, base.lng, RADIUS_M.toInt()) }
            val t = async(Dispatchers.IO) {
                tomTomBase?.let { TomTomPlaces.search(it, BuildConfig.TOMTOM_API_KEY, base.lat, base.lng) }
            }
            val a = async(Dispatchers.IO) {
                olaBase?.let { OlaPlaces.search(it, BuildConfig.OLA_MAPS_API_KEY, base.lat, base.lng) }
            }
            o.await() to listOfNotNull(t.await(), a.await())
        }
        // TomTom first (most phone numbers), then Ola, then OpenStreetMap; one source
        // answering is enough, and only if every source failed is it an error.
        val ok = others.filter { it.error == null }
        val r = if (osm.error != null && ok.isNotEmpty()) OsmPlaces.Result(ok.flatMap { it.places }, null, false)
        else OsmPlaces.Result(ok.flatMap { it.places } + osm.places, osm.error, osm.network, osm.busy)
        val found = merge(r.places).sortedBy { it.distanceM }
        // Only places with a phone number are useful here (in a real version they'd be called).
        val all = found.filter { !it.phone.isNullOrBlank() }
        val hiddenNear = found.count { !it.far && it.phone.isNullOrBlank() }
        val near = all.filter { !it.far }.take(MAX_NEAR)
        // Not enough of a type nearby: add the nearest ones of that type from further away.
        val extra = Category.entries.flatMap { cat ->
            val have = near.count { it.category == cat }
            if (have >= FAR_PER_TYPE) emptyList()
            else all.filter { it.far && it.category == cat }.take(FAR_PER_TYPE - have)
        }.sortedBy { it.distanceM }
        val places = near + extra
        return when {
            r.error != null && r.network -> base.copy(
                status = Status.WAITING_INTERNET,
                message = "Couldn't reach OpenStreetMap · will retry",
                errors = listOf(r.error),
            )
            r.error != null && r.busy -> base.copy(
                status = Status.RETRYING,
                message = "Map servers are busy · trying again in ${RETRY_AFTER_MS / 1000} s",
                errors = listOf(r.error),
            )
            r.error != null -> base.copy(status = Status.ERROR, message = r.error, errors = listOf(r.error))
            else -> base.copy(
                status = Status.DONE,
                message = phoneMessage(near.size, extra.size, hiddenNear),
                places = places,
            )
        }
    }

    /** Same place from two sources (same name, under 200 m apart): keep one, preferring the one with a phone. */
    internal fun merge(places: List<Place>): List<Place> {
        val out = ArrayList<Place>()
        for (p in places.distinctBy { it.id }) {
            val key = norm(p.name)
            val i = out.indexOfFirst { norm(it.name) == key && Math.abs(it.distanceM - p.distanceM) < 200 }
            if (i < 0) { out += p; continue }
            val old = out[i]
            if (old.phone.isNullOrBlank() && !p.phone.isNullOrBlank()) out[i] = p.copy(address = p.address ?: old.address)
        }
        return out
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** e.g. "2 places with a phone within 1 km · 3 nearest further away · 5 without a phone hidden". */
    private fun phoneMessage(near: Int, extra: Int, hidden: Int) =
        "$near place${if (near == 1) "" else "s"} with a phone within $RADIUS_LABEL" +
            (if (extra > 0) " · $extra nearest further away" else "") +
            (if (hidden > 0) " · $hidden without a phone hidden" else "")

    private class CategoryResult(val places: List<Place>, val error: String?, val network: Boolean)

    /**
     * All three category requests, sent at the same time (a slow answer can't hold up
     * the others). One failing doesn't hide the others' results.
     */
    internal suspend fun runSearch(baseUrl: String, key: String, base: Lookup): Lookup {
        val results = coroutineScope {
            Category.entries.map { cat ->
                async(Dispatchers.IO) {
                    try {
                        CategoryResult(
                            parsePlaces(post(baseUrl, key, requestBody(cat, base.lat, base.lng)), cat, base.lat, base.lng),
                            null, false,
                        )
                    } catch (e: PlacesHttpError) {
                        CategoryResult(emptyList(), "${cat.label}: ${e.message}", false)
                    } catch (e: IOException) {
                        CategoryResult(emptyList(), "${cat.label}: network problem (${e.javaClass.simpleName})", true)
                    } catch (e: Exception) {
                        CategoryResult(emptyList(), "${cat.label}: unexpected reply (${e.javaClass.simpleName})", false)
                    }
                }
            }.awaitAll()
        }
        // Same place in two categories: keep the first (police, then pharmacy, then shop).
        val found = LinkedHashMap<String, Place>()
        results.forEach { r -> r.places.forEach { found.putIfAbsent(it.id, it) } }
        val errors = results.mapNotNull { it.error }
        val offline = results.any { it.network }
        val inRange = found.values.filter { it.distanceM <= RADIUS_M }.sortedBy { it.distanceM }
        val places = inRange.filter { !it.phone.isNullOrBlank() } // only places that can be called
        val hidden = inRange.size - places.size
        return when {
            errors.size == Category.entries.size && offline -> base.copy(
                status = Status.WAITING_INTERNET,
                message = "Couldn't reach Google Places · will retry",
                errors = errors,
            )
            errors.size == Category.entries.size -> base.copy(
                status = Status.ERROR,
                message = errors.first(),
                errors = errors,
            )
            else -> base.copy(
                status = Status.DONE,
                message = phoneMessage(places.size, 0, hidden),
                places = places,
                errors = errors,
            )
        }
    }

    internal fun requestBody(cat: Category, lat: Double, lng: Double): JSONObject = JSONObject().apply {
        put("includedTypes", JSONArray(cat.includedTypes))
        put("maxResultCount", 20)
        put("rankPreference", "DISTANCE")
        put("languageCode", "en")
        put(
            "locationRestriction",
            JSONObject().put(
                "circle",
                JSONObject()
                    .put("center", JSONObject().put("latitude", lat).put("longitude", lng))
                    .put("radius", RADIUS_M),
            ),
        )
    }

    class PlacesHttpError(message: String) : Exception(message)

    private fun post(baseUrl: String, key: String, body: JSONObject): JSONObject {
        val conn = URL("${baseUrl.trimEnd('/')}/v1/places:searchNearby").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Goog-Api-Key", key)
            conn.setRequestProperty("X-Goog-FieldMask", FIELD_MASK)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw PlacesHttpError(describeError(code, text))
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /** Turns Google's error reply into a short readable reason. */
    internal fun describeError(code: Int, text: String): String {
        val err = try { JSONObject(text).optJSONObject("error") } catch (_: Exception) { null }
        val status = err?.optString("status").orEmpty()
        val msg = err?.optString("message").orEmpty()
        val hint = when {
            msg.contains("billing", true) -> "billing is not enabled on the Google Cloud project"
            msg.contains("has not been used", true) || msg.contains("is disabled", true) || status == "PERMISSION_DENIED" && msg.contains("API", true) ->
                "Places API (New) is not enabled for this key's project, or the key isn't allowed to use it"
            msg.contains("API key not valid", true) || status == "INVALID_ARGUMENT" && msg.contains("key", true) -> "the API key is not valid"
            else -> null
        }
        val detail = hint ?: msg.ifBlank { "no details" }
        return "HTTP $code${if (status.isNotEmpty()) " $status" else ""} · $detail"
    }

    internal fun parsePlaces(resp: JSONObject, cat: Category, myLat: Double, myLng: Double): List<Place> {
        val arr = resp.optJSONArray("places") ?: return emptyList() // no results = "{}"
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val loc = p.optJSONObject("location") ?: continue
            if (!loc.has("latitude") || !loc.has("longitude")) continue
            val lat = loc.optDouble("latitude")
            val lng = loc.optDouble("longitude")
            val r = FloatArray(1)
            Location.distanceBetween(myLat, myLng, lat, lng, r)
            val phone = p.optString("nationalPhoneNumber").ifBlank { p.optString("internationalPhoneNumber") }.ifBlank { null }
            out += Place(
                id = p.optString("id").ifBlank { "$lat,$lng,${p.optJSONObject("displayName")?.optString("text")}" },
                name = p.optJSONObject("displayName")?.optString("text")?.ifBlank { null } ?: "(no name)",
                category = cat,
                lat = lat,
                lng = lng,
                distanceM = Math.round(r[0]),
                phone = phone,
                address = p.optString("formattedAddress").ifBlank { null },
            )
        }
        return out
    }

    private fun publish(app: Context, l: Lookup) {
        _state.value = l
        try { prefs(app).edit().putString(PREF_LAST, toJson(l).toString()).apply() } catch (_: Exception) {}
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun toJson(l: Lookup) = JSONObject().apply {
        put("status", l.status.name); put("message", l.message); put("alertId", l.alertId)
        put("lat", l.lat); put("lng", l.lng); put("at", l.at)
        put("errors", JSONArray(l.errors)); put("source", l.source)
        put("places", JSONArray().apply {
            l.places.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("name", p.name); put("cat", p.category.name)
                    put("lat", p.lat); put("lng", p.lng); put("d", p.distanceM)
                    put("phone", p.phone ?: ""); put("addr", p.address ?: "")
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): Lookup {
        val ps = o.optJSONArray("places") ?: JSONArray()
        val es = o.optJSONArray("errors") ?: JSONArray()
        // A search interrupted by a restart is shown as not finished.
        val st = (Status.entries.firstOrNull { it.name == o.optString("status") } ?: Status.IDLE)
            .let { if (it == Status.SEARCHING) Status.ERROR else it }
        return Lookup(
            status = st,
            message = if (st == Status.ERROR && o.optString("status") == "SEARCHING") "Search was interrupted · tap SEARCH AGAIN" else o.optString("message"),
            alertId = o.optString("alertId"),
            lat = o.optDouble("lat"), lng = o.optDouble("lng"), at = o.optLong("at"),
            places = (0 until ps.length()).map { i ->
                val p = ps.getJSONObject(i)
                Place(
                    p.optString("id"), p.optString("name"), Category.valueOf(p.optString("cat", "SHOP")),
                    p.optDouble("lat"), p.optDouble("lng"), p.optInt("d"),
                    p.optString("phone").ifBlank { null }, p.optString("addr").ifBlank { null },
                )
            },
            errors = (0 until es.length()).map { es.getString(it) },
            source = o.optString("source"),
        )
    }
}
