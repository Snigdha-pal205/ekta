package com.ekta.sos

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Push alerts from the server (FCM). The server sends *data* messages
 * (type/id/lat/lng/status/…) and the app shows them exactly like any other alert:
 * same "safeher_alerts" channel, same looping alarm, same rules.
 *
 * Note: sending pushes needs a server (e.g. a Cloud Function on the Blaze plan).
 * Until one exists, nearby alerts still arrive through the live Firestore listener.
 */
class EktaMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data.isEmpty()) return
        AlertListenerService.deliverPush(this, message.data)
    }

    override fun onNewToken(token: String) {
        // Kept so a future server can target this phone.
        getSharedPreferences("ekta", Context.MODE_PRIVATE).edit().putString("fcm_token", token).apply()
    }
}
