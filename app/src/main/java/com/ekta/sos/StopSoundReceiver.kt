package com.ekta.sos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The "Stop sound" button on an alert notification. */
class StopSoundReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmPlayer.stop(intent.getStringExtra(EXTRA_ID))
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
