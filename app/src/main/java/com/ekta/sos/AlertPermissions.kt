package com.ekta.sos

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings an alert needs: exact location, filling the screen and ringing. Any that are off are shown
 * one by one in a pop-up with a button straight to the right settings page.
 */
object AlertPermissions {
    data class Missing(
        val key: String,
        val title: String,
        val why: String,
        val turnOn: String,
        val intents: (Context) -> List<Intent>,
    )

    private fun appDetails(c: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))

    fun missing(c: Context): List<Missing> {
        val list = mutableListOf<Missing>()
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) list += Missing(
            "notifications", "ALLOW NOTIFICATIONS",
            "Without notifications you will never hear when someone nearby needs help.",
            "Turn on \"Allow notifications\".",
        ) { ctx ->
            listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                appDetails(ctx),
            )
        }
        if (!LiveLocation.isGpsOn(c)) list += Missing(
            "gps", "LOCATION (GPS) IS OFF",
            "Without it nobody can find you: your SOS would go out with no exact location.",
            "Turn on \"Use location\" (and \"Google Location Accuracy\" if you see it).",
        ) { ctx -> listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), appDetails(ctx)) }
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) list += Missing(
            "fullscreen", "FULL-SCREEN ALERTS",
            "So a nearby SOS fills your screen, even when the phone is locked.",
            "Turn on \"Allow full screen notifications\" for Ekta.",
        ) { ctx ->
            listOf(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")),
                appDetails(ctx),
            )
        }
        if (!Settings.canDrawOverlays(c)) list += Missing(
            "overlay", "DISPLAY OVER OTHER APPS",
            "So the alert page opens on top of whatever you are using.",
            "Find Ekta and turn on \"Display over other apps\".",
        ) { ctx ->
            listOf(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")),
                appDetails(ctx),
            )
        }
        if (!XiaomiPopups.allowed(c)) list += Missing(
            "xiaomi_popups", "XIAOMI: POP-UP WINDOWS",
            "Xiaomi blocks the alert page unless these are on. Without them you only get a small notification.",
            "Turn on \"Display pop-up windows while running in the background\" and \"Show on Lock screen\".",
            XiaomiPopups::settingsIntents,
        )
        if (!XiaomiPopups.autostartAllowed(c)) list += Missing(
            "xiaomi_autostart", "XIAOMI: AUTOSTART",
            "So Ekta keeps listening after the phone restarts or the app updates.",
            "Find Ekta and turn Autostart on.",
            XiaomiPopups::autostartIntents,
        )
        return list
    }
}

@Composable
fun PermissionPopup(
    item: AlertPermissions.Missing,
    remaining: Int,
    onOpenSettings: () -> Unit,
    onLater: () -> Unit,
) {
    // A card at the TOP of the screen, not a blocking dialog: the SOS button below
    // must always work with one tap, even while this is showing.
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 66.dp, bottom = 8.dp), // below the settings gear
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(SkyBottom)
                .border(2.dp, Ink)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "PERMISSION NEEDED" + if (remaining > 1) " (1 of $remaining)" else "",
                fontFamily = Mono, fontSize = 11.sp, color = Ink, letterSpacing = 1.sp,
            )
            Text(item.title, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 16.sp, color = Ink, letterSpacing = 1.sp)
            Text(item.why, fontFamily = Mono, fontSize = 11.sp, color = Ink)
            Text(
                item.turnOn,
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Ink,
                modifier = Modifier.fillMaxWidth().background(Color.White).padding(8.dp),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "LATER", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink,
                    modifier = Modifier.clickable(onClick = onLater).padding(12.dp),
                )
                Text(
                    "OPEN SETTINGS", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 14.sp, color = Ink,
                    modifier = Modifier
                        .background(Lime)
                        .border(2.dp, Ink)
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
