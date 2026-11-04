package com.ekta.sos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF151515)
val SkyTop = Color(0xFF6FB1F2)
val SkyBottom = Color(0xFFA9D3F7)
val Lime = Color(0xFFE4F25A)
val Pink = Color(0xFFE84A8A)
val Mono = FontFamily.Monospace

/** Sky gradient, planet + sparkles, and the forest along the bottom. */
@Composable
fun IllustratedBackground(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SkyTop, SkyBottom))),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawSky() }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .align(Alignment.BottomCenter),
        ) { drawForest() }
        content()
    }
}

@Composable
fun SosScreen(status: String, sending: Boolean, listening: Boolean, onSos: () -> Unit) {
    IllustratedBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            WobblyLogo("EKTA")
            Spacer(Modifier.height(14.dp))
            Text(
                "Nobody walks home alone.",
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                color = Ink, letterSpacing = 0.5.sp,
            )
            Spacer(Modifier.height(28.dp))
            InfoColumns(listening)
            Spacer(Modifier.height(36.dp))
            SosButton(enabled = !sending, label = if (sending) "…" else "SOS", onClick = onSos)
            Spacer(Modifier.height(18.dp))
            Text(
                status,
                fontFamily = Mono, fontSize = 13.sp, color = Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(280.dp),
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(240.dp)) // room for the forest
        }
    }
}

/** Hand-drawn feeling logo: each letter tilted and nudged a little differently. */
@Composable
fun WobblyLogo(text: String) {
    val tilts = listOf(-12f, 8f, -5f, 14f, -9f, 6f)
    val lifts = listOf(6, -8, 4, -4, 8, -6)
    Row(verticalAlignment = Alignment.CenterVertically) {
        text.forEachIndexed { i, c ->
            Text(
                c.toString(),
                fontSize = 62.sp, fontWeight = FontWeight.Black, color = Ink,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier
                    .offset(y = lifts[i % lifts.size].dp)
                    .rotate(tilts[i % tilts.size])
                    .padding(horizontal = 1.dp),
            )
        }
    }
}

@Composable
private fun InfoColumns(listening: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfoBlock("WHO HEARS YOU", "everyone with Ekta", "within 200 metres", Modifier.weight(1f))
        Box(Modifier.width(1.5.dp).height(78.dp).background(Ink))
        InfoBlock(
            "YOUR STATUS",
            if (listening) "on duty" else "not listening",
            if (listening) "you'll hear nearby SOS" else "allow location access",
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun InfoBlock(title: String, line1: String, line2: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            color = Ink, letterSpacing = 1.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(line1, fontFamily = Mono, fontSize = 12.sp, color = Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(line2, fontFamily = Mono, fontSize = 12.sp, color = Ink, textAlign = TextAlign.Center)
    }
}

/** Chunky lime button with a hard black offset shadow; it "sinks" into the shadow on press. */
@Composable
fun ChunkyButton(
    width: Dp,
    height: Dp,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shadow = 7.dp
    val shift = if (pressed) shadow else 0.dp

    Box(Modifier.size(width = width + shadow, height = height + shadow)) {
        Box(
            Modifier
                .offset(shadow, shadow)
                .size(width, height)
                .background(Ink),
        )
        Column(
            modifier
                .offset(shift, shift)
                .size(width, height)
                .background(if (enabled) Lime else Lime.copy(alpha = 0.85f))
                .border(2.dp, Ink)
                .clickable(
                    interactionSource = interaction, indication = null,
                    enabled = enabled, role = Role.Button, onClick = onClick,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { content() }
    }
}

@Composable
private fun SosButton(enabled: Boolean, label: String, onClick: () -> Unit) {
    ChunkyButton(
        width = 230.dp, height = 120.dp, enabled = enabled, onClick = onClick,
        modifier = Modifier.semantics { contentDescription = "SOS. Alert everyone within 200 metres" },
    ) {
        Text(label, fontSize = 52.sp, fontWeight = FontWeight.Black, color = Ink, letterSpacing = 6.sp)
        Text("press for help", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Ink)
    }
}
