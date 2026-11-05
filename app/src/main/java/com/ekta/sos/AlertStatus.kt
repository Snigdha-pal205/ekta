package com.ekta.sos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * What this phone knows about other people's alerts: the key hashes that came with
 * each SOS (to check cancel/duress messages are genuine), and the latest status.
 */
object AlertStatus {
    const val ACTIVE = "active"
    const val CANCELLED = "cancelled"
    const val DURESS = "duress"

    data class Info(
        val status: String = ACTIVE,
        val lat: Double? = null,
        val lng: Double? = null,
        val updatedAt: Long = 0L,
    )

    private val hashes = HashMap<String, Pair<String, String>>() // id -> (cancel hash, duress hash)
    private val alerted = HashSet<String>()                       // ids this phone showed the alert for

    private val _infos = MutableStateFlow<Map<String, Info>>(emptyMap())
    val infos: StateFlow<Map<String, Info>> = _infos

    @Synchronized
    fun rememberHashes(id: String, cancelHash: String, duressHash: String) {
        if (cancelHash.isNotBlank() && duressHash.isNotBlank()) hashes.putIfAbsent(id, cancelHash to duressHash)
    }

    @Synchronized
    fun markAlerted(id: String) {
        alerted += id
    }

    @Synchronized
    fun wasAlerted(id: String) = id in alerted

    /** True if [key] really unlocks [status] for alert [id] (hash must match the SOS). */
    @Synchronized
    fun verify(id: String, status: String, key: String): Boolean {
        val (hc, hd) = hashes[id] ?: return false
        val h = PinStore.sha256Hex(key)
        return (status == CANCELLED && h == hc) || (status == DURESS && h == hd)
    }

    fun statusOf(id: String) = _infos.value[id]?.status ?: ACTIVE

    /** Record a (verified) status / location change. Returns true if the status itself changed. */
    fun set(id: String, status: String, lat: Double?, lng: Double?, at: Long): Boolean {
        var changed = false
        _infos.update { m ->
            val old = m[id] ?: Info()
            // Once cancelled or duress, an alert never goes back to plain "active".
            val newStatus = if (old.status != ACTIVE && status == ACTIVE) old.status else status
            changed = newStatus != old.status
            if (at < old.updatedAt && !changed) return@update m
            m + (id to Info(newStatus, lat ?: old.lat, lng ?: old.lng, maxOf(at, old.updatedAt)))
        }
        return changed
    }
}
