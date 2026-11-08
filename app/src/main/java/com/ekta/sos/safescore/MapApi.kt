package com.ekta.sos.safescore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ekta.sos.BuildConfig
import com.ekta.sos.R
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale

/**
 * Map data for Safe Score, from TomTom (free, no card): place search suggestions,
 * nearby places, and a small map image with the pin. Results for the same spot are
 * cached for 10 minutes to stay within the free tier.
 */
object MapApi {
    private const val BASE = "https://api.tomtom.com"
    private const val CACHE_MS = 10 * 60 * 1000L

    /** A nearby place: name, TomTom category names, distance, and opening hours if known. */
    data class Poi(val name: String, val categories: List<String>, val distanceM: Int, val openingHours: JSONObject?)

    private data class Cached(val at: Long, val value: Any)
    private val cache = HashMap<String, Cached>()

    private fun key(): String = BuildConfig.TOMTOM_API_KEY.ifBlank {
        throw SafeScoreError(R.string.err_no_key)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(k: String, load: () -> T): T {
        synchronized(cache) {
            cache[k]?.let { if (System.currentTimeMillis() - it.at < CACHE_MS) return it.value as T }
        }
        val v = load()
        synchronized(cache) { cache[k] = Cached(System.currentTimeMillis(), v as Any) }
        return v
    }

    /** Search suggestions as she types (near where she is, India only). */
    fun suggest(query: String, nearLat: Double?, nearLng: Double?): List<PlaceHit> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        return cached("s:${q.lowercase()}:${nearLat?.let { "%.2f".format(Locale.US, it) }}") {
            val near = if (nearLat != null && nearLng != null) String.format(Locale.US, "&lat=%.5f&lon=%.5f", nearLat, nearLng) else ""
            val json = get("$BASE/search/2/search/${URLEncoder.encode(q, "UTF-8").replace("+", "%20")}.json" +
                "?key=${enc(key())}&typeahead=true&limit=6&countrySet=IN&language=en-GB$near")
            val arr = json.optJSONArray("results") ?: return@cached emptyList<PlaceHit>()
            (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONObject(i) ?: return@mapNotNull null
                val pos = r.optJSONObject("position") ?: return@mapNotNull null
                val address = r.optJSONObject("address")?.optString("freeformAddress").orEmpty()
                val name = r.optJSONObject("poi")?.optString("name")?.ifBlank { null } ?: address.substringBefore(",")
                PlaceHit(r.optString("id"), name, address, pos.optDouble("lat"), pos.optDouble("lon"))
            }
        }
    }

    /** Places of the given TomTom categories within [radiusM], nearest first. */
    fun nearby(lat: Double, lng: Double, categories: String, radiusM: Int, withHours: Boolean = false): List<Poi> =
        cached(String.format(Locale.US, "n:%.4f:%.4f:%s:%d:%b", lat, lng, categories, radiusM, withHours)) {
            val json = get("$BASE/search/2/nearbySearch/.json?key=${enc(key())}" +
                String.format(Locale.US, "&lat=%.6f&lon=%.6f", lat, lng) +
                "&radius=$radiusM&limit=100&countrySet=IN&language=en-GB&categorySet=${enc(categories)}" +
                if (withHours) "&openingHours=nextSevenDays" else "")
            val arr = json.optJSONArray("results") ?: return@cached emptyList<Poi>()
            (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONObject(i) ?: return@mapNotNull null
                val poi = r.optJSONObject("poi") ?: return@mapNotNull null
                val cats = poi.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.optString(it).lowercase() } }.orEmpty()
                Poi(poi.optString("name"), cats, Math.round(r.optDouble("dist")).toInt(), poi.optJSONObject("openingHours"))
            }.sortedBy { it.distanceM }
        }

    /** A small street map centred on the place (the pin is drawn on top by the app). */
    fun mapImage(lat: Double, lng: Double, widthPx: Int = 600, heightPx: Int = 340): Bitmap? = try {
        cached(String.format(Locale.US, "m:%.5f:%.5f", lat, lng)) {
            val url = "$BASE/map/1/staticimage?key=${enc(key())}&zoom=15&format=png&layer=basic&style=main" +
                String.format(Locale.US, "&center=%.6f,%.6f", lng, lat) + "&width=$widthPx&height=$heightPx"
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                if (conn.responseCode !in 200..299) throw IOException("map HTTP ${conn.responseCode}")
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                conn.disconnect()
            }
        }
    } catch (_: Exception) {
        null // the result card still works without the picture
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** GET JSON with clear errors: no internet, limit reached, other problems. */
    private fun get(url: String): JSONObject {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw SafeScoreError(R.string.err_no_internet)
        }
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            val code = try {
                conn.responseCode
            } catch (e: UnknownHostException) {
                throw SafeScoreError(R.string.err_no_internet)
            } catch (e: IOException) {
                throw SafeScoreError(R.string.err_no_answer)
            }
            when {
                code == 403 || code == 429 -> throw SafeScoreError(R.string.err_limit)
                code !in 200..299 -> throw SafeScoreError(R.string.err_http, code)
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}
