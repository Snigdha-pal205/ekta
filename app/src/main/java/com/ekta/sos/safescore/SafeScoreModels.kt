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
    val nightCapped: Boolean = false, // late-night Delhi/Noida limit applied
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

    /**
     * Late night in Delhi / Noida (11 PM to 6 AM) the score is capped at this, and
     * it then always reads "Be careful" (8+ Safe, 7–7.9 Fairly safe, 5–6.9 Be careful, below 5 Unsafe).
     */
    const val NIGHT_CAP = 6.5

    /** 23:00–05:59. */
    fun isLateNight(hour: Int) = hour >= 23 || hour < 6

    /** Delhi NCT + Noida / Greater Noida (a simple box; also takes in the edges of Ghaziabad and Gurugram). */
    fun inDelhiNoida(lat: Double, lng: Double) = lat in 28.38..28.89 && lng in 76.83..77.60

    /** Factor colours, same bands as the verdict: 7–10 green, 5–6.9 orange, below 5 red. */
    fun levelOf(score: Double): Level = when {
        score >= 7.0 -> Level.GREEN
        score >= 5.0 -> Level.ORANGE
        else -> Level.RED
    }

    enum class Verdict { SAFE, FAIRLY_SAFE, BE_CAREFUL, UNSAFE }

    /** 8+ Safe, 7–7.9 Fairly safe, 5–6.9 Be careful, below 5 Unsafe. */
    fun verdictOf(score: Double): Verdict = when {
        score >= 8.0 -> Verdict.SAFE
        score >= 7.0 -> Verdict.FAIRLY_SAFE
        score >= 5.0 -> Verdict.BE_CAREFUL
        else -> Verdict.UNSAFE
    }

    /** Weights, in card order: incidents, people around, transport, police, community. */
    val WEIGHTS = listOf(0.25, 0.30, 0.20, 0.15, 0.10)

    /**
     * Weighted average of the factors that have data (null = "no data" / "no ratings yet",
     * left out), rounded to 1 decimal. What changes with the hour counts most.
     */
    fun combine(scores: List<Double?>): Double {
        val used = scores.zip(WEIGHTS).filter { it.first != null }
        if (used.isEmpty()) return 0.0
        return Math.round(used.sumOf { (s, w) -> s!! * w } / used.sumOf { it.second } * 10) / 10.0
    }

    /** Late night in Delhi / Noida: never above [NIGHT_CAP]. Returns the score and whether it was lowered. */
    fun applyNightCap(score: Double, hour: Int, lat: Double, lng: Double): Pair<Double, Boolean> =
        if (isLateNight(hour) && inDelhiNoida(lat, lng) && score > NIGHT_CAP) NIGHT_CAP to true else score to false

    /** Label and message for the overall safe score. */
    fun verdict(c: android.content.Context, score: Double): Triple<Level, String, String> = when (verdictOf(score)) {
        Verdict.SAFE -> Triple(Level.GREEN, Lang.s(c, R.string.v_safe), Lang.s(c, R.string.v_safe_m))
        Verdict.FAIRLY_SAFE -> Triple(Level.YELLOW, Lang.s(c, R.string.v_fair), Lang.s(c, R.string.v_fair_m))
        Verdict.BE_CAREFUL -> Triple(Level.ORANGE, Lang.s(c, R.string.v_careful), Lang.s(c, R.string.v_careful_m))
        Verdict.UNSAFE -> Triple(Level.RED, Lang.s(c, R.string.v_unsafe), Lang.s(c, R.string.v_unsafe_m))
    }

    /** "9 PM", "12 AM", "12 PM". */
    fun hourLabel(hour: Int): String = when {
        hour == 0 -> "12 AM"
        hour < 12 -> "$hour AM"
        hour == 12 -> "12 PM"
        else -> "${hour - 12} PM"
    }
}
