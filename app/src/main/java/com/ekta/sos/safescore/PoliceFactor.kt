package com.ekta.sos.safescore

import android.content.Context
import com.ekta.sos.Lang
import com.ekta.sos.R

/** Factor 4 — The nearest police station or booth. */
object PoliceFactor {
    private const val SEARCH_RADIUS_M = 5000
    private const val POLICE = "7322"

    fun score(context: Context, lat: Double, lng: Double): FactorResult {
        val nearest = MapApi.nearby(lat, lng, POLICE, SEARCH_RADIUS_M).firstOrNull()
            ?: return FactorResult(Lang.s(context, R.string.f_police), 2.0, Scoring.levelOf(2.0), Lang.s(context, R.string.pol_none))
        val d = nearest.distanceM
        val score = when {
            d < 500 -> 10.0
            d <= 1000 -> 8.0
            d <= 2000 -> 6.0
            d <= 3000 -> 4.0
            else -> 2.0
        }
        val away = if (d >= 1000) String.format(java.util.Locale.US, "%.1f km", d / 1000.0) else "$d m"
        return FactorResult(Lang.s(context, R.string.f_police), score, Scoring.levelOf(score), Lang.s(context, R.string.pol_away, away))
    }
}
