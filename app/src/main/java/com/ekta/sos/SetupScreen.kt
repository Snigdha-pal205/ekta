package com.ekta.sos

import androidx.compose.ui.res.stringResource
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
    val mesh: Boolean,
    val contact: EmergencyContact?,
    val pins: Boolean,
)

/** First-run screen: one button walks through every permission the app needs. */
@Composable
fun SetupScreen(status: SetupStatus, message: String?, onTurnOn: () -> Unit) {
    fun s(ok: Boolean) = if (ok) Step.DONE else Step.IDLE
    val r = stringResource(R.string.radius_label)

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
            BrandMark(30f)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.setup_intro),
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Bone,
            )
            Spacer(Modifier.height(24.dp))

            Column(Modifier.width(310.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                StatusRow(s(status.location), stringResource(R.string.setup_location), stringResource(R.string.setup_location_d, r))
                StatusRow(s(status.notifications), stringResource(R.string.setup_notifications), stringResource(R.string.setup_notifications_d))
                StatusRow(s(status.alwaysOn), stringResource(R.string.setup_always_on), stringResource(R.string.setup_always_on_d))
                StatusRow(s(status.fullScreen), stringResource(R.string.setup_fullscreen), stringResource(R.string.setup_fullscreen_d))
                StatusRow(s(status.sms), stringResource(R.string.setup_sms), stringResource(R.string.setup_sms_d))
                StatusRow(s(status.mesh), stringResource(R.string.setup_mesh), stringResource(R.string.setup_mesh_d))
                StatusRow(
                    s(status.contact != null), stringResource(R.string.settings_contact),
                    status.contact?.let { "${it.name} · ${it.number}" } ?: stringResource(R.string.setup_contact_d),
                )
                StatusRow(s(status.pins), stringResource(R.string.pins_title), stringResource(R.string.setup_pins_d))
            }

            Spacer(Modifier.height(28.dp))
            ChunkyButton(width = 280.dp, height = 76.dp, onClick = onTurnOn) {
                Text(
                    stringResource(R.string.setup_turn_on), fontFamily = Mono, fontWeight = FontWeight.Black,
                    fontSize = 17.sp, color = Bone, letterSpacing = 1.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                message ?: stringResource(R.string.setup_help),
                fontFamily = Mono, fontSize = 12.sp, color = Bone,
                textAlign = TextAlign.Center, modifier = Modifier.width(290.dp),
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
