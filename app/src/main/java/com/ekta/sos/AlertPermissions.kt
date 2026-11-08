package com.ekta.sos

import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings an alert needs: exact location, filling the screen and ringing. Any that are off are shown
 * one by one in a pop-up with a button straight to the right settings page.
 */
object AlertPermissions {
    /**
     * One setting to turn on, written for anyone: a plain title, one line on why,
     * numbered steps (what to tap, word for word), and a short reminder shown as a
     * toast on top of the settings page.
     */
    data class Missing(
        val key: String,
        val title: String,
        val why: String,
        val steps: List<String>,
        val hint: String,
        val intents: (Context) -> List<Intent>,
    )

    private fun appDetails(c: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))


    fun missing(c: Context): List<Missing> {
        val list = mutableListOf<Missing>()
        fun t(id: Int) = Lang.s(c, id)
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) list += Missing(
            "notifications", t(R.string.perm_notif_title),
            t(R.string.perm_notif_why),
            listOf(t(R.string.perm_tap_turn_on), t(R.string.perm_notif_step), t(R.string.perm_back)),
            t(R.string.perm_notif_hint),
        ) { ctx ->
            listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                appDetails(ctx),
            )
        }
        if (!LiveLocation.isGpsOn(c)) list += Missing(
            "gps", t(R.string.perm_gps_title),
            t(R.string.perm_gps_why),
            listOf(t(R.string.perm_tap_turn_on), t(R.string.perm_gps_step), t(R.string.perm_back)),
            t(R.string.perm_gps_hint),
        ) { ctx -> listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), appDetails(ctx)) }
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) list += Missing(
            "fullscreen", t(R.string.perm_fs_title),
            t(R.string.perm_fs_why),
            listOf(t(R.string.perm_tap_turn_on), t(R.string.perm_fs_step), t(R.string.perm_back)),
            t(R.string.perm_fs_hint),
        ) { ctx ->
            listOf(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")),
                appDetails(ctx),
            )
        }
        if (!Settings.canDrawOverlays(c)) list += Missing(
            "overlay", t(R.string.perm_ov_title),
            t(R.string.perm_ov_why),
            listOf(t(R.string.perm_tap_turn_on), t(R.string.perm_ov_find), t(R.string.perm_ov_step), t(R.string.perm_back)),
            t(R.string.perm_ov_hint),
        ) { ctx ->
            listOf(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")),
                appDetails(ctx),
            )
        }
        if (!XiaomiPopups.allowed(c)) list += Missing(
            "xiaomi_popups", t(R.string.perm_xp_title),
            t(R.string.perm_xp_why),
            listOf(
                t(R.string.perm_tap_turn_on),
                t(R.string.perm_xp_step1),
                t(R.string.perm_xp_step2),
                t(R.string.perm_back),
            ),
            t(R.string.perm_xp_hint),
            XiaomiPopups::settingsIntents,
        )
        if (!XiaomiPopups.autostartAllowed(c)) list += Missing(
            "xiaomi_autostart", t(R.string.perm_xa_title),
            t(R.string.perm_xa_why),
            listOf(t(R.string.perm_tap_turn_on), t(R.string.perm_xa_step), t(R.string.perm_back)),
            t(R.string.perm_xa_hint),
            XiaomiPopups::autostartIntents,
        )
        return list
    }
}

@Composable
fun PermissionPopup(
    item: AlertPermissions.Missing,
    done: Int,
    total: Int,
    onOpenSettings: () -> Unit,
    onLater: () -> Unit,
) {
    // A card at the BOTTOM of the screen, not a blocking dialog: the SOS button above
    // must always work with one tap, even while this is showing.
    Box(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .border(2.dp, Ink)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Progress: "Step 2 of 4" with dots
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.perm_step, done + 1, total),
                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Ink, letterSpacing = 1.sp,
                )
                Spacer(Modifier.weight(1f))
                repeat(total) { i ->
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(if (i <= done) Lime else Color.White, CircleShape)
                            .border(1.5.dp, Ink, CircleShape),
                    )
                }
            }
            Text(item.title, fontWeight = FontWeight.Black, fontSize = 18.sp, color = Ink)
            Text(item.why, fontSize = 13.sp, color = Ink)
            Column(
                Modifier.fillMaxWidth().background(SkyBottom.copy(alpha = 0.45f)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item.steps.forEachIndexed { i, step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(22.dp).background(Ink, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text("${i + 1}", fontWeight = FontWeight.Black, fontSize = 12.sp, color = Color.White) }
                        Text(step, fontSize = 13.sp, color = Ink, modifier = Modifier.padding(start = 10.dp, top = 1.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.not_now),
                    fontSize = 13.sp, color = Ink.copy(alpha = 0.7f), textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable(onClick = onLater).padding(horizontal = 6.dp, vertical = 12.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(50.dp)
                        .background(Lime)
                        .border(2.dp, Ink)
                        .clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.turn_on), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 18.sp, color = Ink, letterSpacing = 2.sp) }
            }
        }
    }
}
