package com.ekta.sos

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ola Maps (free: 100,000 calls a month per API). Knows many more Indian shops,
 * pharmacies and police stations than OpenStreetMap, but rarely has phone numbers.
 * Used together with OpenStreetMap; only places with a phone end up in the list.
 */
object OlaPlaces {
    const val DEFAULT_BASE_URL = "https://api.olamaps.io"

    /** One call per type: (Ola type, our category, how far to look). */
    val QUERIES = listOf(
        Triple("police", NearbyPlaces.Category.POLICE, OsmPlaces.FAR_POLICE_PHARMACY_M),
        Triple("pharmacy", NearbyPlaces.Category.PHARMACY, OsmPlaces.FAR_POLICE_PHARMACY_M),
        Triple("supermarket", NearbyPlaces.Category.SHOP, OsmPlaces.FAR_SHOP_M),
        Triple("convenience_store", NearbyPlaces.Category.SHOP, OsmPlaces.FAR_SHOP_M),
        Triple("grocery_or_supermarket", NearbyPlaces.Category.SHOP, OsmPlaces.FAR_SHOP_M),
        Triple("store", NearbyPlaces.Category.SHOP, OsmPlaces.FAR_SHOP_M),
    )

    /** All type calls at once (each takes well under a second). */
    fun search(baseUrl: String, key: String, lat: Double, lng: Double): OsmPlaces.Result {
        val pool = Executors.newFixedThreadPool(QUERIES.size)
        try {
            val futures = QUERIES.map { (type, cat, radius) ->
                pool.submit<OsmPlaces.Result> {
                    try {
                        OsmPlaces.Result(parse(get(url(baseUrl, key, type, lat, lng, radius)), cat), null, false)
                    } catch (e: OlaHttpError) {
                        OsmPlaces.Result(emptyList(), "Ola Maps ($type): ${e.message}", false, busy = e.busy)
                    } catch (e: IOException) {
                        OsmPlaces.Result(emptyList(), "Ola Maps ($type): network problem (${e.javaClass.simpleName})", true)
                    } catch (t: Throwable) {
                        OsmPlaces.Result(emptyList(), "Ola Maps ($type): unexpected reply (${t.javaClass.simpleName})", false)
                    }
                }
            }
            val results = futures.map { f ->
                try {
                    f.get(25, TimeUnit.SECONDS)
                } catch (t: Throwable) {
                    OsmPlaces.Result(emptyList(), "Ola Maps: no answer", false, busy = true)
                }
            }
            val places = results.flatMap { it.places }
            val errors = results.mapNotNull { it.error }
            return if (errors.size == results.size) {
                OsmPlaces.Result(emptyList(), errors.first(), results.all { it.network }, results.any { it.busy })
            } else {
                OsmPlaces.Result(places, null, false)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    internal fun url(baseUrl: String, key: String, type: String, lat: Double, lng: Double, radius: Int): String =
        "${baseUrl.trimEnd('/')}/places/v1/nearbysearch/advanced?layers=venue&types=$type" +
            "&location=" + String.format(Locale.US, "%.6f,%.6f", lat, lng) +
            "&radius=$radius&limit=20&api_key=" + URLEncoder.encode(key, "UTF-8")

    class OlaHttpError(message: String, val busy: Boolean) : Exception(message)

    private fun get(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("X-Request-Id", UUID.randomUUID().toString())
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val why = when (code) {
                    401, 403 -> "HTTP $code · key not accepted"
                    429 -> "HTTP 429 · free limit reached for now"
                    else -> "HTTP $code"
                }
                throw OlaHttpError(why, busy = code == 429 || code >= 500)
            }
            val json = JSONObject(text)
            val status = json.optString("status")
            if (status.isNotEmpty() && status != "ok") {
                throw OlaHttpError("status $status ${json.optString("error_message").take(80)}", busy = false)
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(json: JSONObject, cat: NearbyPlaces.Category): List<NearbyPlaces.Place> {
        val arr = json.optJSONArray("predictions") ?: return emptyList()
        val out = ArrayList<NearbyPlaces.Place>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val f = p.optJSONObject("structured_formatting")
            val name = f?.optString("main_text").orEmpty().ifBlank { p.optString("description").substringBefore(",") }
            if (name.isBlank()) continue
            val distance = p.optInt("distance_meters", -1)
            if (distance < 0) continue
            out += NearbyPlaces.Place(
                id = "ola:" + p.optString("place_id").ifBlank { name },
                name = name,
                category = cat,
                lat = 0.0, lng = 0.0, // Ola gives the distance directly; no position needed for the list
                distanceM = distance,
                phone = phoneOf(p),
                address = f?.optString("secondary_text")?.ifBlank { null },
            )
        }
        return out
    }

    /** Ola leaves phone fields empty or "NA" when it doesn't know the number. */
    internal fun phoneOf(p: JSONObject): String? =
        listOf("formatted_phone_number", "international_phone_number")
            .map { p.optString(it).trim() }
            .firstOrNull { it.isNotEmpty() && !it.equals("NA", true) && !it.equals("null", true) }
}
