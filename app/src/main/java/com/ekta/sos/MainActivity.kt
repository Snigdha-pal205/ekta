package com.ekta.sos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Bumped on every resume so the UI re-reads permission state (e.g. after Settings). */
    private var resumeTick by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("ekta", MODE_PRIVATE)

        setContent {
            val scope = rememberCoroutineScope()
            var setupDone by remember { mutableStateOf(prefs.getBoolean("setup_done", false)) }
            var setupMessage by remember { mutableStateOf<String?>(null) }
            var status by remember { mutableStateOf("Press only in a real emergency.") }
            var sending by remember { mutableStateOf(false) }
            val tick = resumeTick // read so permission checks below recompose on resume

            fun finishSetup() {
                prefs.edit().putBoolean("setup_done", true).apply()
                setupMessage = null
                setupDone = true
            }

            // Step 4: don't let battery saver kill the listener.
            val batteryLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { finishSetup() }

            fun askBattery() {
                if (ignoresBatteryOptimizations()) return finishSetup()
                try {
                    batteryLauncher.launch(batteryIntent())
                } catch (_: Exception) {
                    finishSetup()
                }
            }

            // Step 3: "Allow all the time" so alerts survive restarts.
            val backgroundLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { askBattery() }

            fun askBackground() {
                if (Build.VERSION.SDK_INT < 29 || BootReceiver.canListenInBackground(this)) {
                    askBattery()
                } else {
                    backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }

            // Steps 1+2: location + notifications in one go.
            val foregroundLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                if (hasLocation()) {
                    AlertListenerService.start(this)
                    askBackground()
                } else {
                    setupMessage = "Ekta needs your location to know who is within 200 m. " +
                        "Tap the button and choose \"While using the app\"."
                }
            }

            val settingsLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { if (hasLocation()) askBackground() }

            fun startSetup() {
                when {
                    hasLocation() -> askBackground()
                    // Denied before and Android won't show the popup again → open Settings.
                    setupMessage != null &&
                        !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ->
                        settingsLauncher.launch(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                        )
                    else -> foregroundLauncher.launch(foregroundPermissions())
                }
            }

            fun sendSos() {
                if (sending) return
                sending = true
                status = "Sending your SOS…"
                scope.launch {
                    status = try {
                        SosRepository.sendSos(this@MainActivity)
                        "SOS sent! Everyone within 200 m has been alerted."
                    } catch (e: Exception) {
                        "Couldn't send: ${e.message ?: "unknown error"}"
                    }
                    delay(5_000) // short cooldown so a panicked double-tap doesn't spam
                    sending = false
                }
            }

            val ready = setupDone && hasLocation()
            LaunchedEffect(ready, tick) {
                if (ready) AlertListenerService.start(this@MainActivity)
            }

            if (!ready) {
                SetupScreen(
                    locationOk = hasLocation(),
                    notificationsOk = hasNotifications(),
                    alwaysOnOk = BootReceiver.canListenInBackground(this) && ignoresBatteryOptimizations(),
                    message = setupMessage,
                    onTurnOn = ::startSetup,
                )
            } else {
                SosScreen(
                    status = status,
                    sending = sending,
                    listening = true,
                    onSos = ::sendSos,
                )
            }
        }
    }

    private fun hasLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasNotifications() =
        Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun ignoresBatteryOptimizations() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife") // a safety app must keep listening
    private fun batteryIntent() =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))

    private fun foregroundPermissions() = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}
