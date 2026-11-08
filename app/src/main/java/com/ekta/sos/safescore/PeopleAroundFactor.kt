package com.ekta.sos.safescore

import com.ekta.sos.R
import com.ekta.sos.Lang
import android.content.Context
import org.json.JSONObject

/**
 * Factor 2 — People around: places open within 300 m at that hour.
 * Opening hours come from the map data when known; otherwise typical hours by
 * category from assets/typical_hours.json.
 */
object PeopleAroundFactor {
    private const val RADIUS_M = 300
    // Restaurants, cafés/pubs, shops, petrol stations, pharmacies, hospitals, hotels, supermarkets.
    private const val CATEGORIES = "7315,9376,9361,7311,7326,7321,7314,7332"

    private var typical: Map<String, Pair<Int, Int>>? = null

    private fun typicalHours(context: Context): Map<String, Pair<Int, Int>> = typical ?: run {
        val o = JSONObject(context.assets.open("typical_hours.json").bufferedReader().use { it.readText() })
        o.keys().asSequence().filter { !it.startsWith("_") }.associateWith { k ->
            val a = o.getJSONArray(k); a.getInt(0) to a.getInt(1)
        }.also { typical = it }
    }

    fun score(context: Context, lat: Double, lng: Double, hour: Int): FactorResult {
        val places = MapApi.nearby(lat, lng, CATEGORIES, RADIUS_M, withHours = true)
        val hours = typicalHours(context)
        val open = places.count { isOpen(it, hour, hours) }
        val (score, text) = when {
            open >= 10 -> 10.0 to Lang.s(context, R.string.ppl_many)
            open >= 5 -> 7.0 to Lang.s(context, R.string.ppl_some)
            open >= 2 -> 4.0 to Lang.s(context, R.string.ppl_few)
            else -> 1.0 to Lang.s(context, R.string.ppl_none)
        }
        return FactorResult(Lang.s(context, R.string.f_people), score, Scoring.levelOf(score), text)
    }

    /** Open at [hour] (today)? Real opening hours first, else typical hours for its category. */
    internal fun isOpen(p: MapApi.Poi, hour: Int, typical: Map<String, Pair<Int, Int>>): Boolean {
        p.openingHours?.optJSONArray("timeRanges")?.let { ranges ->
            if (ranges.length() > 0) {
                val firstDate = ranges.getJSONObject(0).getJSONObject("startTime").optString("date")
                for (i in 0 until ranges.length()) {
                    val r = ranges.getJSONObject(i)
                    val s = r.getJSONObject("startTime")
                    val e = r.getJSONObject("endTime")
                    if (s.optString("date") != firstDate) continue // only today's ranges
                    val start = s.optInt("hour") + s.optInt("minute") / 60.0
                    // End on a later day = runs past midnight.
                    val end = e.optInt("hour") + e.optInt("minute") / 60.0 + if (e.optString("date") != firstDate) 24 else 0
                    if (hour + 0.5 in start..end) return true
                }
                return false
            }
        }
        val (from, to) = p.categories.firstNotNullOfOrNull { typical[it] } ?: typical["default"] ?: (10 to 20)
        return inRange(hour, from, to)
    }

    /** [from, to) in hours; to may be past 24 (after midnight). */
    internal fun inRange(hour: Int, from: Int, to: Int): Boolean =
        if (to - from >= 24) true else (hour in from until to) || (hour + 24 in from until to)
}
