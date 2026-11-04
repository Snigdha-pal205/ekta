package com.ekta.sos

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Starts listening again after the phone restarts, so nobody has to reopen the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (canListenInBackground(context)) AlertListenerService.start(context)
    }

    companion object {
        fun canListenInBackground(context: Context): Boolean {
            fun granted(p: String) =
                ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return false
            return Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
}
