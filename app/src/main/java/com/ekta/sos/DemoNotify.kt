package com.ekta.sos

import android.util.Log

/**
 * DEMO ONLY. The women's committee and the police test number are never actually
 * contacted. This records exactly what would have been sent, so it can be shown in a
 * demo (`adb logcat -s EktaDemo`) without messaging anyone real.
 */
object DemoNotify {
    private const val TAG = "EktaDemo"
    private val log = mutableListOf<String>()

    fun committee(text: String) = record("women's committee", text)
    fun police(text: String) = record("police test number", text)

    fun recent(): List<String> = synchronized(log) { log.toList() }

    private fun record(to: String, text: String) {
        val line = "DEMO (not sent) → $to: $text"
        synchronized(log) {
            log += line
            if (log.size > 50) log.removeAt(0)
        }
        Log.i(TAG, line)
    }
}
