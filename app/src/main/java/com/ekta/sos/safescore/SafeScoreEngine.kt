package com.ekta.sos.safescore

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Works out the safe score for a place at an hour:
 * the 5 factors (in parallel), then the simple average of the ones available.
 */
object SafeScoreEngine {
    suspend fun calculate(context: Context, place: PlaceHit, hour: Int): SafeScoreResult = coroutineScope {
        val app = context.applicationContext
        val incidents = async(Dispatchers.Default) { IncidentsFactor.score(app, place.lat, place.lng, hour) }
        val people = async(Dispatchers.IO) { PeopleAroundFactor.score(app, place.lat, place.lng, hour) }
        val transport = async(Dispatchers.IO) { TransportFactor.score(app, place.lat, place.lng, hour) }
        val police = async(Dispatchers.IO) { PoliceFactor.score(app, place.lat, place.lng) }
        val community = async(Dispatchers.IO) { CommunityFactor.score(app, place.id, hour) }
        // Order on the card: incidents, people, transport, police, community.
        val factors = listOf(incidents.await(), people.await(), transport.await(), police.await(), community.await())

        val scores = factors.mapNotNull { it.score } // "no ratings yet" is left out
        val safe = (scores.average() * 10).roundToInt() / 10.0
        val (level, label, message) = Scoring.verdict(app, safe)
        SafeScoreResult(place.name, place.lat, place.lng, hour, factors, safe, level, label, message)
    }

    suspend fun suggest(query: String, nearLat: Double?, nearLng: Double?): List<PlaceHit> =
        withContext(Dispatchers.IO) { MapApi.suggest(query, nearLat, nearLng) }
}
