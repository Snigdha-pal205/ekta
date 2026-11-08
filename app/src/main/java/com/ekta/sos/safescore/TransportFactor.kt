package com.ekta.sos.safescore

import com.ekta.sos.R
import com.ekta.sos.Lang
import android.content.Context
import org.json.JSONObject

/**
 * Factor 3 — Transport within 500 m that's running at that hour.
 * Bus stops, metro stations and auto/taxi stands from the map; service hours from
 * assets/transport_hours.json.
 */
object TransportFactor {
    private const val RADIUS_M = 500
    // Public transport stops (bus, tram/metro, taxi/auto), railway & subway stations, subway entrances.
    private const val CATEGORIES = "9942,7380,7389999"

    private var hours: Map<String, Pair<Int, Int>>? = null

    private fun serviceHours(context: Context): Map<String, Pair<Int, Int>> = hours ?: run {
        val o = JSONObject(context.assets.open("transport_hours.json").bufferedReader().use { it.readText() })
        o.keys().asSequence().filter { !it.startsWith("_") }.associateWith { k ->
            val a = o.getJSONArray(k); a.getInt(0) to a.getInt(1)
        }.also { hours = it }
    }

    fun score(context: Context, lat: Double, lng: Double, hour: Int): FactorResult {
        val stops = MapApi.nearby(lat, lng, CATEGORIES, RADIUS_M)
        val h = serviceHours(context)
        fun running(kind: String) = h[kind]?.let { (from, to) -> PeopleAroundFactor.inRange(hour, from, to) } ?: true
        val hasMetro = stops.any { isMetro(it) } && running("metro")
        val hasBus = stops.any { "bus stop" in it.categories } && running("bus")
        val hasAuto = stops.any { "taxi stand" in it.categories } && running("auto")
        val (score, text) = when {
            hasMetro && hasBus -> 10.0 to Lang.s(context, R.string.tr_both)
            hasBus -> 7.0 to Lang.s(context, R.string.tr_bus)
            hasMetro -> 7.0 to Lang.s(context, R.string.tr_metro)
            hasAuto -> 4.0 to Lang.s(context, R.string.tr_auto)
            else -> 1.0 to Lang.s(context, R.string.tr_none)
        }
        return FactorResult(Lang.s(context, R.string.f_transport), score, Scoring.levelOf(score), text)
    }

    /** Metro: subway stations/entrances, or stops the map calls tram stops / names "metro". */
    private fun isMetro(p: MapApi.Poi) =
        p.categories.any { it == "subway station" || it == "subway entrance" || it == "tram stop" } ||
            p.name.contains("metro", ignoreCase = true)
}
