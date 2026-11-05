package com.ekta.sos

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The two PINs that end an SOS. Only salted hashes are stored, never the PINs.
 *  - normal PIN → really cancel
 *  - duress PIN → *looks* cancelled on her phone, but the alert keeps running silently
 */
object PinStore {
    enum class Result { NORMAL, DURESS, WRONG }

    const val PIN_LENGTH = 4

    private fun prefs(c: Context) = c.getSharedPreferences("ekta", Context.MODE_PRIVATE)

    fun hasPins(c: Context) =
        prefs(c).getString("pin_normal", null) != null && prefs(c).getString("pin_duress", null) != null

    fun save(c: Context, normal: String, duress: String) {
        val salt = randomHex(16)
        prefs(c).edit()
            .putString("pin_salt", salt)
            .putString("pin_normal", sha256Hex(salt + normal))
            .putString("pin_duress", sha256Hex(salt + duress))
            .apply()
    }

    fun check(c: Context, pin: String): Result {
        val p = prefs(c)
        val salt = p.getString("pin_salt", "") ?: ""
        val h = sha256Hex(salt + pin)
        return when (h) {
            p.getString("pin_normal", null) -> Result.NORMAL
            p.getString("pin_duress", null) -> Result.DURESS
            else -> Result.WRONG
        }
    }

    fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
