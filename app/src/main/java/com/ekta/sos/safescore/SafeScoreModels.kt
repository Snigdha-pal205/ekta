package com.ekta.sos.safescore

import com.ekta.sos.Lang
import com.ekta.sos.R

/**
 * Safe Score: how safe a place feels at a given hour, from 5 factors (0–10, 10 = safest).
 * Each factor lives in its own file; SafeScoreEngine combines them.
 */

/** Colour of a factor line or of the overall score (always shown with words and a number too). */
enum class Level { GREEN, ORANGE, RED, YELLOW }

/** One factor's result. [score] null = not available (left out of the average). */
data class FactorResult(
    val name: String,
    val score: Double?,
    val level: Level,
    val text: String,
)

/** The whole result, in the order the card shows it. */
data class SafeScoreResult(
    val placeName: String,
    val lat: Double,
    val lng: Double,
    val hour: Int,
    val factors: List<FactorResult>, // incidents, people, transport, police, community
    val safeScore: Double,
    val level: Level,
    val label: String,
    val message: String,
)

/** A place she can pick from the search suggestions. */
data class PlaceHit(val id: String, val name: String, val address: String, val lat: Double, val lng: Double)

/** Errors shown clearly on screen. */
class SafeScoreError(val res: Int, val arg: Any? = null) : Exception("safe score error") {
    fun text(c: android.content.Context): String = if (arg != null) Lang.s(c, res, arg) else Lang.s(c, res)
}

object Scoring {
    /** Hour bands: morning 6–12, afternoon 12–18, evening 18–22, night 22–6. */
    fun band(hour: Int): String = when (hour) {
        in 6..11 -> "morning"
        in 12..17 -> "afternoon"
        in 18..21 -> "evening"
        else -> "night"
    }

    /** 7–10 green, 4–6.9 orange, 0–3.9 red. */
    fun levelOf(score: Double): Level = when {
        score >= 7.0 -> Level.GREEN
        score >= 4.0 -> Level.ORANGE
        else -> Level.RED
    }

    /** Label and message for the overall safe score. */
    fun verdict(c: android.content.Context, score: Double): Triple<Level, String, String> = when {
        score >= 8.0 -> Triple(Level.GREEN, Lang.s(c, R.string.v_safe), Lang.s(c, R.string.v_safe_m))
        score >= 6.0 -> Triple(Level.YELLOW, Lang.s(c, R.string.v_fair), Lang.s(c, R.string.v_fair_m))
        score >= 4.0 -> Triple(Level.ORANGE, Lang.s(c, R.string.v_careful), Lang.s(c, R.string.v_careful_m))
        else -> Triple(Level.RED, Lang.s(c, R.string.v_unsafe), Lang.s(c, R.string.v_unsafe_m))
    }

    /** "9 PM", "12 AM", "12 PM". */
    fun hourLabel(hour: Int): String = when {
        hour == 0 -> "12 AM"
        hour < 12 -> "$hour AM"
        hour == 12 -> "12 PM"
        else -> "${hour - 12} PM"
    }
}
