package com.ekta.sos

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

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
            val sos by SosSession.state.collectAsState()
            var setupDone by remember { mutableStateOf(prefs.getBoolean("setup_done", false)) }
            var setupMessage by remember { mutableStateOf<String?>(null) }
            var contact by remember { mutableStateOf(SosSession.contact(this)) }
            var showContactDialog by remember { mutableStateOf(false) }
            var picked by remember { mutableStateOf<EmergencyContact?>(null) }
            val tick = resumeTick // read so the checks below refresh on resume

            fun finishSetup() {
                prefs.edit().putBoolean("setup_done", true).apply()
                setupMessage = null
                setupDone = true
                if (contact == null) showContactDialog = true
            }

            // ---- Contact picker (no READ_CONTACTS needed: the picker grants this one row) ----
            val contactPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result -> result.data?.data?.let { picked = readPickedContact(it) } }

            // ---- Setup chain: each step hands over to the next ----
            val overlayLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { finishSetup() }

            fun askOverlay() {
                if (Settings.canDrawOverlays(this)) return finishSetup()
                try {
                    overlayLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    )
                } catch (_: Exception) {
                    finishSetup()
                }
            }

            val fullScreenLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { askOverlay() }

            fun askFullScreen() {
                if (canUseFullScreen()) return askOverlay()
                try {
                    fullScreenLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")),
                    )
                } catch (_: Exception) {
                    askOverlay()
                }
            }

            val batteryLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { askFullScreen() }

            fun askBattery() {
                if (ignoresBatteryOptimizations()) return askFullScreen()
                try {
                    batteryLauncher.launch(batteryIntent())
                } catch (_: Exception) {
                    askFullScreen()
                }
            }

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

            val foregroundLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                if (hasLocation()) {
                    AlertListenerService.start(this)
                    askBackground()
                } else {
                    setupMessage = "Ekta needs your location to know who is within " +
                        "${ALERT_RADIUS_METERS.toInt()} m. Tap the button and choose \"While using the app\"."
                }
            }

            val settingsLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { if (hasLocation()) askBackground() }

            fun startSetup() {
                when {
                    hasLocation() && (hasSms() || setupMessage != null) -> askBackground()
                    hasLocation() -> foregroundLauncher.launch(foregroundPermissions())
                    setupMessage != null &&
                        !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ->
                        settingsLauncher.launch(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                        )
                    else -> foregroundLauncher.launch(foregroundPermissions())
                }
            }

            val ready = setupDone && hasLocation()
            LaunchedEffect(ready, tick) {
                if (ready) AlertListenerService.start(this@MainActivity)
            }

            if (!ready) {
                SetupScreen(
                    status = SetupStatus(
                        location = hasLocation(),
                        notifications = hasNotifications(),
                        alwaysOn = BootReceiver.canListenInBackground(this) && ignoresBatteryOptimizations(),
                        fullScreen = canUseFullScreen() && Settings.canDrawOverlays(this),
                        sms = hasSms(),
                        contact = contact,
                    ),
                    message = setupMessage,
                    onTurnOn = ::startSetup,
                )
            } else {
                SosScreen(
                    state = sos,
                    contact = contact,
                    onSos = { SosSession.start(this) },
                    onSafe = { SosSession.stop(this) },
                    onEndCall = SosSession::endDemoCall,
                    onEditContact = { picked = null; showContactDialog = true },
                )
            }

            if (showContactDialog) {
                ContactDialog(
                    initial = contact,
                    picked = picked,
                    onPickFromContacts = {
                        try {
                            contactPicker.launch(
                                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                            )
                        } catch (_: Exception) {
                        }
                    },
                    onSave = { name, number ->
                        SosSession.saveContact(this, name, number)
                        contact = SosSession.contact(this)
                        showContactDialog = false
                    },
                    onDismiss = { showContactDialog = false },
                )
            }
        }
    }

    private fun readPickedContact(uri: Uri): EmergencyContact? = try {
        contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) EmergencyContact(c.getString(0) ?: "", c.getString(1) ?: "") else null
        }
    } catch (_: Exception) {
        null
    }

    private fun hasLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasSms() = granted(Manifest.permission.SEND_SMS)

    private fun hasNotifications() =
        Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun canUseFullScreen() =
        Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

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
        add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}
