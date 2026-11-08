package com.ekta.sos.safescore

import com.ekta.sos.R
import com.ekta.sos.Lang
import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * Factor 5 — Community rating: women rate places 1–5 stars in the app.
 * Stored in Firestore at ratings/{placeId}/votes/{device_band} (one vote per phone
 * per time of day, so it can be changed but not stuffed).
 * Score = average stars × 2, for ratings in the same time of day.
 */
object CommunityFactor {
    private fun votes(placeId: String) =
        FirebaseFirestore.getInstance().collection("ratings").document(safeId(placeId)).collection("votes")

    /** Firestore document ids can't contain "/". */
    private fun safeId(id: String) = id.replace("/", "_").take(150).ifBlank { "unknown" }

    suspend fun score(context: Context, placeId: String, hour: Int): FactorResult {
        val band = Scoring.band(hour)
        val stars = try {
            votes(placeId).whereEqualTo("band", band).get().await().documents.mapNotNull { it.getLong("stars")?.toInt() }
        } catch (_: Exception) {
            emptyList() // offline or not set up: treated as no ratings
        }
        if (stars.isEmpty()) return FactorResult(Lang.s(context, R.string.f_community), null, Level.YELLOW, Lang.s(context, R.string.com_none))
        val avg = stars.average()
        val score = avg * 2
        val feel = when {
            score >= 7.0 -> Lang.s(context, R.string.com_safe)
            score >= 4.0 -> Lang.s(context, R.string.com_some)
            else -> Lang.s(context, R.string.com_unsafe)
        }
        return FactorResult(Lang.s(context, R.string.f_community), score, Scoring.levelOf(score), String.format(Locale.US, "%.1f / 5 (%s)", avg, feel))
    }

    /** Save (or change) this phone's rating for the place at this time of day. */
    suspend fun rate(deviceId: String, place: PlaceHit, hour: Int, stars: Int) {
        val band = Scoring.band(hour)
        votes(place.id).document("${deviceId.take(40)}_$band").set(
            mapOf(
                "stars" to stars.coerceIn(1, 5),
                "band" to band,
                "lat" to place.lat,
                "lng" to place.lng,
                "name" to place.name.take(120),
                "at" to Timestamp.now(),
            ),
            SetOptions.merge(),
        ).await()
    }
}
