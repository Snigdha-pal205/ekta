package com.ekta.sos

import android.location.Location
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Free source for the nearby list: OpenStreetMap, via the public Overpass API.
 * No key, no billing. One request covers all three kinds of place. Many places in
 * OpenStreetMap have no phone number listed (shown as "not listed").
 */
object OsmPlaces {
    /** Public Overpass servers. All are asked at once; the first good answer wins. */
    val SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )
    private const val USER_AGENT = "EktaSOS/1.0 (hackathon demo)"

    /** [network] = no internet; [busy] = server overloaded/slow (worth retrying soon). */
    class Result(val places: List<NearbyPlaces.Place>, val error: String?, val network: Boolean, val busy: Boolean = false)

    /** Police and pharmacies are looked for this far out (they're sparse), shops this far. */
    const val FAR_POLICE_PHARMACY_M = 5000
    const val FAR_SHOP_M = 3000

    /**
     * The Overpass query. One request gets everything: police and pharmacies up to
     * 5 km and shops up to 3 km, so if nothing is within the main radius the nearest
     * ones further away can still be shown.
     */
    fun query(lat: Double, lng: Double, @Suppress("UNUSED_PARAMETER") radius: Int): String {
        val far = String.format(Locale.US, "around:%d,%.6f,%.6f", FAR_POLICE_PHARMACY_M, lat, lng)
        val shops = String.format(Locale.US, "around:%d,%.6f,%.6f", FAR_SHOP_M, lat, lng)
        return """
            [out:json][timeout:40];
            (
              nwr["amenity"="police"]($far);
              nwr["amenity"="pharmacy"]($far);
              nwr["healthcare"="pharmacy"]($far);
              nwr["shop"="chemist"]($far);
              nwr["shop"]($shops);
            );
            out center tags;
        """.trimIndent()
    }

    /**
     * Ask every server at the same time and use the first proper answer (the free
     * servers are often slow or busy). Fails only if all of them fail.
     */
    fun search(servers: List<String>, lat: Double, lng: Double, radius: Int): Result {
        val body = "data=" + URLEncoder.encode(query(lat, lng, radius), "UTF-8")
        val answers = LinkedBlockingQueue<Result>()
        val pool = Executors.newFixedThreadPool(servers.size.coerceAtLeast(1))
        try {
            // offer() never blocks or throws: a server still answering when we've already
            // picked a winner (and cancelled it) must never crash the app.
            servers.forEach { url ->
                pool.execute {
                    val r = try {
                        askOne(url, body, lat, lng)
                    } catch (t: Throwable) {
                        Result(emptyList(), "OpenStreetMap: cancelled", false)
                    }
                    answers.offer(r)
                }
            }
            var failures = 0
            var lastError: Result? = null
            var allNetwork = true
            var anyBusy = false
            while (failures < servers.size) {
                val r = answers.poll(READ_TIMEOUT_MS + 15_000L, TimeUnit.MILLISECONDS) ?: break
                if (r.error == null) return r // first good answer wins
                failures++
                if (lastError == null || r.busy) lastError = r
                if (!r.network) allNetwork = false
                if (r.busy) anyBusy = true
            }
            if (failures < servers.size) anyBusy = true // some never answered: slow, not broken
            return Result(emptyList(), lastError?.error ?: "OpenStreetMap: no server answered", allNetwork, anyBusy)
        } finally {
            pool.shutdownNow() // stop waiting for the slower servers
        }
    }

    private fun askOne(url: String, body: String, lat: Double, lng: Double): Result = try {
        val json = post(url, body)
        val remark = json.optString("remark")
        if (remark.contains("error", true)) { // e.g. "runtime error: Query timed out"
            Result(emptyList(), "OpenStreetMap: ${remark.take(120)}", false, busy = remark.contains("timed out", true))
        } else {
            Result(parse(json, lat, lng), null, false)
        }
    } catch (e: OsmHttpError) {
        Result(emptyList(), "OpenStreetMap: ${e.message}", false, busy = e.busy)
    } catch (e: java.net.SocketTimeoutException) {
        Result(emptyList(), "OpenStreetMap: server too slow (no answer in ${READ_TIMEOUT_MS / 1000} s)", false, busy = true)
    } catch (e: IOException) {
        Result(emptyList(), "OpenStreetMap: network problem (${e.javaClass.simpleName})", true)
    } catch (e: Exception) {
        Result(emptyList(), "OpenStreetMap: unexpected reply (${e.javaClass.simpleName})", false)
    }

    class OsmHttpError(message: String, val busy: Boolean) : Exception(message)

    /** Busy public servers often need ~35 s; give them a full minute. */
    const val READ_TIMEOUT_MS = 60_000

    private fun post(url: String, body: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw OsmHttpError(
                    when (code) {
                        429 -> "HTTP 429 · server busy (too many requests)"
                        502, 503, 504 -> "HTTP $code · server busy"
                        400 -> "HTTP 400 · query rejected"
                        else -> "HTTP $code"
                    },
                    busy = code == 429 || code in 500..599,
                )
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(json: JSONObject, myLat: Double, myLng: Double): List<NearbyPlaces.Place> {
        val arr = json.optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<NearbyPlaces.Place>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val tags = e.optJSONObject("tags") ?: JSONObject()
            // Nodes have lat/lon; ways and relations have a "center" (from "out center").
            val c = if (e.has("lat")) e else e.optJSONObject("center") ?: continue
            if (!c.has("lat") || !c.has("lon")) continue
            val lat = c.optDouble("lat")
            val lng = c.optDouble("lon")
            val cat = categoryOf(tags) ?: continue
            val r = FloatArray(1)
            Location.distanceBetween(myLat, myLng, lat, lng, r)
            out += NearbyPlaces.Place(
                id = "osm:${e.optString("type")}/${e.optLong("id")}",
                name = tags.optString("name").ifBlank { tags.optString("name:en") }
                    .ifBlank { "Unnamed ${cat.label.lowercase()}" },
                category = cat,
                lat = lat,
                lng = lng,
                distanceM = Math.round(r[0]),
                phone = phoneOf(tags),
                address = addressOf(tags),
            )
        }
        return out
    }

    /** Police first, then pharmacy, then shop (a supermarket with a pharmacy counts as pharmacy). */
    internal fun categoryOf(t: JSONObject): NearbyPlaces.Category? = when {
        t.optString("amenity") == "police" -> NearbyPlaces.Category.POLICE
        t.optString("amenity") == "pharmacy" || t.optString("healthcare") == "pharmacy" ||
            t.optString("shop") == "chemist" -> NearbyPlaces.Category.PHARMACY
        t.optString("shop").isNotEmpty() -> NearbyPlaces.Category.SHOP
        else -> null
    }

    /** First one or two numbers ("phone" may hold several, separated by ";"). */
    internal fun phoneOf(t: JSONObject): String? {
        val raw = listOf("phone", "contact:phone", "contact:mobile", "mobile")
            .map { t.optString(it) }.firstOrNull { it.isNotBlank() } ?: return null
        val nums = raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        return nums.take(2).joinToString(", ").ifBlank { null }
    }

    private fun addressOf(t: JSONObject): String? {
        val parts = listOf("addr:housenumber", "addr:street", "addr:suburb", "addr:city")
            .map { t.optString(it) }.filter { it.isNotBlank() }
        return parts.joinToString(", ").ifBlank { null }
    }
}
