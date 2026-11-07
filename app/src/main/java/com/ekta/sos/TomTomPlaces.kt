package com.ekta.sos

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TomTom Search API "Nearby Search" (free: 2,500 requests a month, no card; over the
 * limit requests are refused, not charged). Best free source of phone numbers for
 * Indian pharmacies, shops and police stations. 3 requests per lookup.
 */
object TomTomPlaces {
    const val DEFAULT_BASE_URL = "https://api.tomtom.com"

    /** (TomTom category IDs, our category, how far to look). IDs from TomTom's poiCategories list. */
    val QUERIES = listOf(
        Triple("7322", NearbyPlaces.Category.POLICE, OsmPlaces.FAR_POLICE_PHARMACY_M),           // Police Station
        Triple("7326,9361051", NearbyPlaces.Category.PHARMACY, OsmPlaces.FAR_POLICE_PHARMACY_M), // Pharmacy, Drug Store
        Triple("7332005,9361009,9361023", NearbyPlaces.Category.SHOP, OsmPlaces.FAR_SHOP_M),     // Supermarkets, Convenience, Grocery
    )

    fun search(baseUrl: String, key: String, lat: Double, lng: Double): OsmPlaces.Result {
        val pool = Executors.newFixedThreadPool(QUERIES.size)
        try {
            val futures = QUERIES.map { (cats, cat, radius) ->
                pool.submit<OsmPlaces.Result> {
                    try {
                        OsmPlaces.Result(parse(get(url(baseUrl, key, cats, lat, lng, radius)), cat), null, false)
                    } catch (e: TomTomHttpError) {
                        OsmPlaces.Result(emptyList(), "TomTom (${cat.label}): ${e.message}", false, busy = e.busy)
                    } catch (e: IOException) {
                        OsmPlaces.Result(emptyList(), "TomTom (${cat.label}): network problem (${e.javaClass.simpleName})", true)
                    } catch (t: Throwable) {
                        OsmPlaces.Result(emptyList(), "TomTom (${cat.label}): unexpected reply (${t.javaClass.simpleName})", false)
                    }
                }
            }
            val results = futures.map { f ->
                try {
                    f.get(25, TimeUnit.SECONDS)
                } catch (t: Throwable) {
                    OsmPlaces.Result(emptyList(), "TomTom: no answer", false, busy = true)
                }
            }
            val errors = results.mapNotNull { it.error }
            return if (errors.size == results.size) {
                OsmPlaces.Result(emptyList(), errors.first(), results.all { it.network }, results.any { it.busy })
            } else {
                OsmPlaces.Result(results.flatMap { it.places }, null, false)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    internal fun url(baseUrl: String, key: String, cats: String, lat: Double, lng: Double, radius: Int): String =
        "${baseUrl.trimEnd('/')}/search/2/nearbySearch/.json?key=" + URLEncoder.encode(key, "UTF-8") +
            String.format(Locale.US, "&lat=%.6f&lon=%.6f", lat, lng) +
            "&radius=$radius&limit=100&countrySet=IN&language=en-GB&categorySet=" + URLEncoder.encode(cats, "UTF-8")

    class TomTomHttpError(message: String, val busy: Boolean) : Exception(message)

    private fun get(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val why = when (code) {
                    401, 403 -> "HTTP $code · key not accepted (or free monthly limit used up)"
                    429 -> "HTTP 429 · too many requests right now"
                    else -> "HTTP $code"
                }
                throw TomTomHttpError(why, busy = code == 429 || code >= 500)
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(json: JSONObject, cat: NearbyPlaces.Category): List<NearbyPlaces.Place> {
        val arr = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<NearbyPlaces.Place>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val poi = r.optJSONObject("poi") ?: continue
            val name = poi.optString("name").trim()
            if (name.isEmpty() || !r.has("dist")) continue
            val pos = r.optJSONObject("position")
            out += NearbyPlaces.Place(
                id = "tomtom:" + r.optString("id").ifBlank { name },
                name = name,
                category = cat,
                lat = pos?.optDouble("lat") ?: 0.0,
                lng = pos?.optDouble("lon") ?: 0.0,
                distanceM = Math.round(r.optDouble("dist")).toInt(),
                phone = poi.optString("phone").trim().ifBlank { null },
                address = r.optJSONObject("address")?.optString("freeformAddress")?.ifBlank { null },
            )
        }
        return out
    }
}
