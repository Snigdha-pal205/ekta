package com.ekta.sos

import kotlinx.coroutines.launch
import android.content.Context
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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

/** 2 = added Bluetooth mesh (offline SOS). 3 = added cancel / duress PINs. */
private const val SETUP_VERSION = 4

/** Intent extra: open straight into Settings (used by the gear on the alert page). */
const val EXTRA_OPEN_SETTINGS = "open_settings"

class MainActivity : ComponentActivity() {

    /** Bumped on every resume so the UI re-reads permission state (e.g. after Settings). */
    private var resumeTick by mutableIntStateOf(0)

    /** Bumped when another page (e.g. an alert) asks to open Settings. */
    private var openSettingsTick by mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) openSettingsTick++
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            // Ekta is always black: white status/navigation bar icons, so the time and battery stay visible
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val prefs = getSharedPreferences("ekta", MODE_PRIVATE)

        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) openSettingsTick++
        setContent { com.ekta.sos.EktaTheme {
            val sos by SosSession.state.collectAsState()
            // Bump SETUP_VERSION whenever setup gains a step, so existing users see it once.
            var setupDone by remember {
                mutableStateOf(prefs.getBoolean("setup_done", false) && prefs.getInt("setup_version", 1) >= SETUP_VERSION)
            }
            var setupMessage by remember { mutableStateOf<String?>(null) }
            var contact by remember { mutableStateOf(SosSession.contact(this)) }
            var showContactDialog by remember { mutableStateOf(false) }
            var picked by remember { mutableStateOf<EmergencyContact?>(null) }
            var hasPins by remember { mutableStateOf(PinStore.hasPins(this)) }
            var showPinSetup by remember { mutableStateOf(false) }
            var showPinPad by remember { mutableStateOf(false) }
            var safeNowPad by remember { mutableStateOf(false) } // hidden "I'm safe now" pad
            // Settings / language stay open when the page is redrawn in a new language.
            var showSettings by rememberSaveable { mutableStateOf(false) }
            var showLanguage by rememberSaveable { mutableStateOf(false) }
            var tab by rememberSaveable { mutableStateOf(EktaTab.SOS) }
            var signedIn by remember { mutableStateOf(Account.isSignedIn(this)) }
            var account by remember { mutableStateOf(Account.who(this)) }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            fun logOut() {
                scope.launch {
                    AlertListenerService.stop(this@MainActivity)
                    Account.logOut(this@MainActivity)
                    showSettings = false; tab = EktaTab.SOS
                    account = null; signedIn = false
                }
            }
            var pinMessage by remember { mutableStateOf<String?>(null) }
            var showCancelled by remember { mutableStateOf(false) }
            var laterKeys by remember { mutableStateOf(setOf<String>()) } // pop-ups she put off this time
            val tick = resumeTick // read so the checks below refresh on resume

            fun finishSetup() {
                prefs.edit().putBoolean("setup_done", true).putInt("setup_version", SETUP_VERSION).apply()
                setupMessage = null
                setupDone = true
                if (contact == null) showContactDialog = true
                else if (!hasPins) showPinSetup = true
            }

            val gpsLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult(),
            ) { }

            // ---- Contact picker (no READ_CONTACTS needed: the picker grants this one row) ----
            val contactPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result -> result.data?.data?.let { picked = readPickedContact(it) } }

            // ---- Setup chain: each step hands over to the next ----
            val bluetoothLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { finishSetup() }

            fun askBluetooth() {
                if (bluetoothOn() || !MeshManager.hasPermissions(this)) return finishSetup()
                try {
                    @SuppressLint("MissingPermission")
                    val i = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    bluetoothLauncher.launch(i)
                } catch (_: Exception) {
                    finishSetup()
                }
            }

            val autostartLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { askBluetooth() }

            // Xiaomi only: "Autostart", so Ekta keeps listening after a restart / update.
            fun askAutostart() {
                if (XiaomiPopups.autostartAllowed(this)) return askBluetooth()
                Toast.makeText(
                    this, getString(R.string.toast_autostart),
                    Toast.LENGTH_LONG,
                ).show()
                for (i in XiaomiPopups.autostartIntents(this)) {
                    try {
                        autostartLauncher.launch(i)
                        return
                    } catch (_: Exception) {
                    }
                }
                askBluetooth()
            }

            val xiaomiLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { askAutostart() }

            // Xiaomi only: "Display pop-up windows while running in the background" +
            // "Show on lock screen", or alerts arrive as a notification, not the full page.
            fun askXiaomi() {
                if (XiaomiPopups.allowed(this)) return askAutostart()
                Toast.makeText(
                    this,
                    getString(R.string.toast_xiaomi_popups),
                    Toast.LENGTH_LONG,
                ).show()
                for (i in XiaomiPopups.settingsIntents(this)) {
                    try {
                        xiaomiLauncher.launch(i)
                        return
                    } catch (_: Exception) {
                    }
                }
                askAutostart()
            }

            val overlayLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { askXiaomi() }

            fun askOverlay() {
                if (Settings.canDrawOverlays(this)) return askXiaomi()
                try {
                    overlayLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    )
                } catch (_: Exception) {
                    askXiaomi()
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
                    setupMessage = getString(R.string.setup_need_location, getString(R.string.radius_label))
                }
            }

            val settingsLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { if (hasLocation()) askBackground() }

            fun startSetup() {
                when {
                    hasLocation() && (allForegroundGranted() || setupMessage != null) -> askBackground()
                    hasLocation() -> foregroundLauncher.launch(foregroundPermissions())
                    setupMessage != null &&
                        !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ->
                        settingsLauncher.launch(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                        )
                    else -> foregroundLauncher.launch(foregroundPermissions())
                }
            }

            val ready = signedIn && setupDone && hasLocation()
            LaunchedEffect(ready, tick) {
                if (ready) AlertListenerService.start(this@MainActivity)
            }

            if (!signedIn) {
                // Login comes first: nothing else opens until she is logged in.
                if (showLanguage) {
                    androidx.activity.compose.BackHandler { showLanguage = false }
                    LanguageScreen(
                        current = Lang.current(this),
                        onPick = { tag -> showLanguage = false; if (tag != Lang.current(this)) Lang.set(this, tag) },
                        onBack = { showLanguage = false },
                    )
                } else LoginScreen(
                    onLanguage = { showLanguage = true },
                    onDone = { account = Account.who(this); signedIn = true },
                )
            } else if (!ready) {
                SetupScreen(
                    status = SetupStatus(
                        location = hasLocation(),
                        notifications = hasNotifications(),
                        alwaysOn = BootReceiver.canListenInBackground(this) && ignoresBatteryOptimizations() &&
                            XiaomiPopups.autostartAllowed(this),
                        fullScreen = canUseFullScreen() && Settings.canDrawOverlays(this) &&
                            XiaomiPopups.allowed(this),
                        sms = hasSms(),
                        mesh = MeshManager.hasPermissions(this) && bluetoothOn(),
                        contact = contact,
                        pins = hasPins,
                    ),
                    message = setupMessage,
                    onTurnOn = ::startSetup,
                )
            } else if (showCancelled) {
                AlertCancelledScreen()
                // Same screen and timing for the normal and the duress PIN, then home.
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(3_000)
                    showCancelled = false
                }
            } else if (safeNowPad) {
                // Same pad and same result screen whether or not an alert is running,
                // so nobody watching can tell.
                androidx.activity.compose.BackHandler { safeNowPad = false; pinMessage = null }
                PinPadScreen(
                    title = stringResource(R.string.set_safe),
                    backLabel = stringResource(R.string.back_link),
                    message = pinMessage,
                    onSubmit = { pin ->
                        when (SosSession.cancelWithPin(this, pin)) {
                            PinStore.Result.WRONG -> pinMessage = getString(R.string.pin_wrong)
                            else -> {
                                pinMessage = null
                                safeNowPad = false
                                showSettings = false
                                showCancelled = true
                            }
                        }
                    },
                    onBack = { safeNowPad = false; pinMessage = null },
                )
            } else if (showLanguage) {
                androidx.activity.compose.BackHandler { showLanguage = false }
                LanguageScreen(
                    current = Lang.current(this),
                    onPick = { tag -> showLanguage = false; if (tag != Lang.current(this)) Lang.set(this, tag) },
                    onBack = { showLanguage = false },
                )
            } else if (showSettings) {
                androidx.activity.compose.BackHandler { showSettings = false }
                SettingsScreen(
                    onContact = { picked = null; showContactDialog = true },
                    onSafeNow = { pinMessage = null; safeNowPad = true },
                    onLanguage = { showLanguage = true },
                    onBack = { showSettings = false },
                    contactName = contact?.name,
                    account = account,
                    onLogout = if (sos.active) null else ::logOut,
                )
            } else if (showPinPad && sos.active && !sos.hidden) {
                androidx.activity.compose.BackHandler { showPinPad = false; pinMessage = null }
                PinPadScreen(
                    message = pinMessage,
                    onSubmit = { pin ->
                        when (SosSession.cancelWithPin(this, pin)) {
                            PinStore.Result.WRONG -> pinMessage = getString(R.string.pin_wrong)
                            else -> {
                                pinMessage = null
                                showPinPad = false
                                showCancelled = true
                            }
                        }
                    },
                    onBack = { showPinPad = false; pinMessage = null },
                )
            } else if (tab != EktaTab.SOS && !(sos.active && !sos.hidden)) {
                // Other tabs, with the bar below. BACK goes to the SOS tab.
                androidx.activity.compose.BackHandler { tab = EktaTab.SOS }
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        when (tab) {
                            EktaTab.SAFE -> com.ekta.sos.safescore.SafeScoreTab()
                            EktaTab.CONTACT -> ContactTab(contact) { picked = null; showContactDialog = true }
                            else -> SettingsScreen(
                                onContact = { picked = null; showContactDialog = true },
                                onSafeNow = { pinMessage = null; safeNowPad = true },
                                onLanguage = { showLanguage = true },
                                onBack = null,
                                onFixPermissions = { laterKeys = emptySet(); resumeTick++ },
                                contactName = contact?.name,
                                account = account,
                                onLogout = ::logOut,
                            )
                        }
                    }
                    EktaNavBar(tab) { tab = it }
                }
            } else {
                SosScreen(
                    bottomBar = { EktaNavBar(tab) { tab = it } },
                    state = sos,
                    contact = contact,
                    onSos = {
                        SosSession.start(this)
                        // GPS off → Android's one-tap "Turn on location" dialog right away.
                        if (!LiveLocation.isGpsOn(this)) askTurnOnGps(gpsLauncher)
                    },
                    onCancel = {
                        if (PinStore.hasPins(this)) {
                            pinMessage = null
                            showPinPad = true
                        } else {
                            showPinSetup = true // no PINs yet: set them, then cancel
                        }
                    },
                    onEndCall = SosSession::endDemoCall,
                    onEditContact = { picked = null; showContactDialog = true },
                    onSafeNow = { pinMessage = null; safeNowPad = true },
                    onSettings = { showSettings = true },
                    onDestination = { startActivity(Intent(this, com.ekta.sos.safescore.SafeScoreActivity::class.java)) },
                )
            }

            // Pop-up for any permission the full-page alert still needs (re-checked on
            // every return to the app, one at a time, until all are on).
            val missingPerms = remember(tick, laterKeys) {
                AlertPermissions.missing(this).filter { it.key !in laterKeys }
            }
            val nextPerm = missingPerms.firstOrNull()
            if (ready && nextPerm != null && !showContactDialog && !showPinSetup && !sos.active && !showCancelled &&
                !showSettings && !safeNowPad && !showPinPad
            ) {
                // How many were missing when this round started, so it reads "step 2 of 4".
                val total = remember(laterKeys) { mutableIntStateOf(0) }
                if (missingPerms.size > total.intValue) total.intValue = missingPerms.size
                PermissionPopup(
                    item = nextPerm,
                    done = total.intValue - missingPerms.size,
                    total = total.intValue,
                    onOpenSettings = {
                        val opened = nextPerm.intents(this).any { i ->
                            try {
                                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                true
                            } catch (_: Exception) {
                                false
                            }
                        }
                        // A reminder that stays visible on top of the settings page.
                        if (opened) android.widget.Toast.makeText(this, nextPerm.hint, android.widget.Toast.LENGTH_LONG).show()
                        if (!opened) laterKeys = laterKeys + nextPerm.key
                    },
                    onLater = { laterKeys = laterKeys + nextPerm.key },
                )
            }

            // Gear in the corner of every page: Settings without cancelling the SOS.
            // (The SOS keeps running; BACK from Settings returns to the SOS page.)
            LaunchedEffect(openSettingsTick) {
                if (openSettingsTick > 0) { safeNowPad = false; showPinPad = false; showSettings = true }
            }
            val onMainPage = ready && !showCancelled && !safeNowPad && !showSettings &&
                !(showPinPad && sos.active && !sos.hidden)
            if (onMainPage && sos.active && !sos.hidden) SettingsGear { showSettings = true }

            if (showContactDialog) {
                ContactDialog(
                    initial = contact,
                    initialMyName = SosSession.myName(this),
                    picked = picked,
                    onPickFromContacts = {
                        try {
                            contactPicker.launch(
                                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                            )
                        } catch (_: Exception) {
                        }
                    },
                    onSave = { myName, name, number ->
                        SosSession.saveContact(this, myName, name, number)
                        contact = SosSession.contact(this)
                        showContactDialog = false
                        if (!hasPins) showPinSetup = true
                    },
                    onDismiss = { showContactDialog = false },
                )
            }

            if (showPinSetup) {
                PinSetupDialog(
                    onSave = { normal, duress ->
                        PinStore.save(this, normal, duress)
                        hasPins = true
                        showPinSetup = false
                    },
                    onDismiss = { showPinSetup = false },
                )
            }
        }}
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

    /** Google's "Turn on location for better accuracy" dialog (falls back to settings). */
    private fun askTurnOnGps(launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>) {
        val req = com.google.android.gms.location.LocationSettingsRequest.Builder()
            .addLocationRequest(
                com.google.android.gms.location.LocationRequest.Builder(
                    com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 3_000,
                ).build(),
            )
            .setAlwaysShow(true)
            .build()
        com.google.android.gms.location.LocationServices.getSettingsClient(this)
            .checkLocationSettings(req)
            .addOnFailureListener { e ->
                val r = e as? com.google.android.gms.common.api.ResolvableApiException
                try {
                    if (r != null) launcher.launch(androidx.activity.result.IntentSenderRequest.Builder(r.resolution).build())
                    else startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
                }
            }
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
        if (Build.VERSION.SDK_INT >= 31) {
            // "Nearby devices": Bluetooth mesh for SOS with no network
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= 37) add(MeshManager.ACCESS_LOCAL_NETWORK)
    }.toTypedArray()

    private fun allForegroundGranted() = foregroundPermissions().all(::granted)

    private fun bluetoothOn() = try {
        getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (_: Exception) {
        false
    }
}
