package com.ekta.sos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** First-run screen: one button walks through every permission the app needs. */
@Composable
fun SetupScreen(
    locationOk: Boolean,
    notificationsOk: Boolean,
    alwaysOnOk: Boolean,
    message: String?,
    onTurnOn: () -> Unit,
) {
    IllustratedBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            WobblyLogo("EKTA")
            Spacer(Modifier.height(12.dp))
            Text(
                "One-time setup. Takes 10 seconds.",
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Ink,
            )
            Spacer(Modifier.height(26.dp))

            Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Step(locationOk, "LOCATION", "so we know who is within 200 m")
                Step(notificationsOk, "NOTIFICATIONS", "so you hear when someone needs help")
                Step(alwaysOnOk, "ALWAYS ON", "keeps working in the background and after a restart")
            }

            Spacer(Modifier.height(30.dp))
            ChunkyButton(width = 280.dp, height = 76.dp, onClick = onTurnOn) {
                Text(
                    "TURN ON SOS ALERTS", fontFamily = Mono, fontWeight = FontWeight.Black,
                    fontSize = 17.sp, color = Ink, letterSpacing = 1.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                message ?: "Tap Allow on each popup. On the location page pick \"Allow all the time\", then press back. You'll never be asked again.",
                fontFamily = Mono, fontSize = 12.sp, color = Ink,
                textAlign = TextAlign.Center, modifier = Modifier.width(290.dp),
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(240.dp)) // room for the forest
        }
    }
}

@Composable
private fun Step(done: Boolean, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(26.dp)
                .background(if (done) Lime else SkyBottom)
                .border(2.dp, Ink),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Text("✓", fontWeight = FontWeight.Black, fontSize = 15.sp, color = Ink)
        }
        Column(Modifier.padding(start = 12.dp).fillMaxWidth()) {
            Text(title, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Ink, letterSpacing = 1.sp)
            Text(detail, fontFamily = Mono, fontSize = 11.sp, color = Ink)
        }
    }
}
