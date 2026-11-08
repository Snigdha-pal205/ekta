package com.ekta.sos.safescore

import com.ekta.sos.R
import com.ekta.sos.Lang
import android.content.Context
import android.location.Location
import org.json.JSONObject

/**
 * Factor 1 — Past incidents within 1 km, in the same time of day.
 * Uses assets/incidents.json: SAMPLE DATA for the hackathon, not real reports.
 */
object IncidentsFactor {
    private const val RADIUS_M = 1000f

    private data class Incident(val lat: Double, val lng: Double, val hour: Int)
    private var data: List<Incident>? = null

    private fun load(context: Context): List<Incident> = data ?: run {
        val json = JSONObject(context.assets.open("incidents.json").bufferedReader().use { it.readText() })
        val arr = json.getJSONArray("incidents")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Incident(o.getDouble("lat"), o.getDouble("lng"), o.getInt("hour"))
        }.also { data = it }
    }

    fun score(context: Context, lat: Double, lng: Double, hour: Int): FactorResult {
        val band = Scoring.band(hour)
        val d = FloatArray(1)
        val count = load(context).count { inc ->
            Scoring.band(inc.hour) == band && run {
                Location.distanceBetween(lat, lng, inc.lat, inc.lng, d)
                d[0] <= RADIUS_M
            }
        }
        val (score, text) = when {
            count == 0 -> 10.0 to Lang.s(context, R.string.inc_none)
            count <= 2 -> 8.0 to Lang.s(context, R.string.inc_few)
            count <= 5 -> 6.0 to Lang.s(context, R.string.inc_some)
            count <= 10 -> 4.0 to Lang.s(context, R.string.inc_some)
            else -> 2.0 to Lang.s(context, R.string.inc_many)
        }
        return FactorResult(Lang.s(context, R.string.f_incidents), score, Scoring.levelOf(score), text)
    }
}
