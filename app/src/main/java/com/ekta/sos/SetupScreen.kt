package com.ekta.sos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable

data class SetupStatus(
    val location: Boolean,
    val notifications: Boolean,
    val alwaysOn: Boolean,
    val fullScreen: Boolean,
    val sms: Boolean,
    val contact: EmergencyContact?,
)

/** First-run screen: one button walks through every permission the app needs. */
@Composable
fun SetupScreen(status: SetupStatus, message: String?, onTurnOn: () -> Unit) {
    fun s(ok: Boolean) = if (ok) Step.DONE else Step.IDLE
    val r = ALERT_RADIUS_METERS.toInt()

    IllustratedBackground(forest = false) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(70.dp))
            WobblyLogo("EKTA")
            Spacer(Modifier.height(10.dp))
            Text(
                "One-time setup. Takes 30 seconds.",
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Ink,
            )
            Spacer(Modifier.height(24.dp))

            Column(Modifier.width(310.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                StatusRow(s(status.location), "LOCATION", "so we know who is within $r m")
                StatusRow(s(status.notifications), "NOTIFICATIONS", "so you hear when someone needs help")
                StatusRow(s(status.alwaysOn), "ALWAYS ON", "keeps working in the background and after a restart")
                StatusRow(s(status.fullScreen), "FULL-SCREEN ALERTS", "a nearby SOS fills your screen and buzzes")
                StatusRow(s(status.sms), "SMS", "to text your live location on SOS")
                StatusRow(
                    s(status.contact != null), "EMERGENCY CONTACT",
                    status.contact?.let { "${it.name} · ${it.number}" } ?: "who gets your live location",
                )
            }

            Spacer(Modifier.height(28.dp))
            ChunkyButton(width = 280.dp, height = 76.dp, onClick = onTurnOn) {
                Text(
                    "TURN ON SOS ALERTS", fontFamily = Mono, fontWeight = FontWeight.Black,
                    fontSize = 17.sp, color = Ink, letterSpacing = 1.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                message ?: "Tap Allow on each popup. On settings pages, switch Ekta on, then press back. You'll never be asked again.",
                fontFamily = Mono, fontSize = 12.sp, color = Ink,
                textAlign = TextAlign.Center, modifier = Modifier.width(290.dp),
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
