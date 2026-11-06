package com.ekta.sos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

val Ink = Color(0xFF151515)
val SkyTop = Color(0xFF6FB1F2)
val SkyBottom = Color(0xFFA9D3F7)
val Lime = Color(0xFFE4F25A)
val Pink = Color(0xFFE84A8A)
val Mono = FontFamily.Monospace
private val Radius = ALERT_RADIUS_METERS.toInt()

/** Sky gradient, planet + sparkles, and (optionally) the forest along the bottom. */
@Composable
fun IllustratedBackground(forest: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SkyTop, SkyBottom))),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawSky() }
        if (forest) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .align(Alignment.BottomCenter),
            ) { drawForest() }
        }
        content()
    }
}

@Composable
fun SosScreen(
    state: SosState,
    contact: EmergencyContact?,
    onSos: () -> Unit,
    onCancel: () -> Unit,
    onEndCall: () -> Unit,
    onEditContact: () -> Unit,
    onSafeNow: () -> Unit,
    onSettings: () -> Unit,
) {
    // In duress the alert keeps running, but her screen must look completely normal.
    if (state.active && !state.hidden) {
        ActivePage(state, contact, onCancel, onEndCall)
        return
    }
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
            Spacer(Modifier.height(26.dp))
            InfoColumns(contact)
            Spacer(Modifier.height(30.dp))
            SosButton(onClick = onSos, onLongClick = onSafeNow)
            Spacer(Modifier.height(18.dp))
            Text(
                if (contact == null) "Add an emergency contact" else "Emergency contact: ${contact.name}",
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink,
                textDecoration = TextDecoration.Underline, textAlign = TextAlign.Center,
                modifier = Modifier
                    .clickable(onClick = onEditContact)
                    .padding(6.dp),
            )
            Text(
                "Press only in a real emergency.",
                fontFamily = Mono, fontSize = 12.sp, color = Ink,
            )
            Text(
                "SETTINGS",
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Ink,
                letterSpacing = 1.sp, textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable(onClick = onSettings)
                    .padding(8.dp),
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(230.dp)) // room for the forest
        }
    }
}

// ---------- SOS active ----------

@Composable
private fun ActivePage(
    state: SosState,
    contact: EmergencyContact?,
    onCancel: () -> Unit,
    onEndCall: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = ((now - state.startedAt) / 1000).coerceAtLeast(0)

    IllustratedBackground(forest = false) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            WobblyLogo("SOS")
            Text(
                "ACTIVE · ${clock(elapsed)}",
                fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 16.sp,
                color = Ink, letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(20.dp))

            DemoCallCard(elapsed, state.callEnded, state.signal, onEndCall)
            Spacer(Modifier.height(22.dp))

            Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val loc = state.location
                val age = loc?.let { now - it.time } ?: Long.MAX_VALUE
                StatusRow(
                    when {
                        !state.gpsOn -> Step.FAILED
                        loc != null && age <= LiveLocation.FRESH_MS && loc.accuracy <= 30f -> Step.DONE
                        else -> Step.WORKING
                    },
                    "YOUR EXACT LOCATION",
                    when {
                        !state.gpsOn -> "Location (GPS) is OFF · turn it on in quick settings!"
                        loc == null -> "searching GPS… go near a window or outside"
                        age > LiveLocation.FRESH_MS -> "last known, ${age / 60_000} min old · finding you now…"
                        loc.accuracy > 30f -> "±${loc.accuracy.toInt()} m · getting more exact…"
                        else -> "±${loc.accuracy.toInt()} m · live, shared as you move"
                    },
                )
                StatusRow(state.mesh, "BLUETOOTH / WI-FI MESH", state.meshNote)
                StatusRow(state.upload, if (state.online) "INTERNET" else "INTERNET · OFFLINE", state.uploadNote)
                StatusRow(
                    state.contactSms,
                    "SMS → ${contact?.name?.uppercase() ?: "EMERGENCY CONTACT"}",
                    state.contactNote,
                )
                StatusRow(Step.DEMO, "SMS → WOMEN'S COMMITTEE", "DEMO · not actually sent")
            }
            if (state.committeeMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "\u201C${state.committeeMessage}\u201D",
                    fontFamily = Mono, fontSize = 10.sp, color = Ink.copy(alpha = 0.75f),
                    modifier = Modifier.width(320.dp).padding(start = 38.dp),
                )
            }

            Spacer(Modifier.height(30.dp))
            ChunkyButton(width = 260.dp, height = 78.dp, color = Color.White, onClick = onCancel) {
                Text("CANCEL ALERT", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 22.sp, color = Ink, letterSpacing = 2.sp)
                Text("needs your PIN", fontFamily = Mono, fontSize = 11.sp, color = Ink)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** Looks like an ongoing call, but nothing is dialled. */
@Composable
private fun DemoCallCard(elapsed: Long, ended: Boolean, signal: Boolean, onEnd: () -> Unit) {
    Box(Modifier.size(width = 327.dp, height = 116.dp)) {
        Box(
            Modifier
                .offset(7.dp, 7.dp)
                .size(320.dp, 109.dp)
                .background(Pink),
        )
        Row(
            Modifier
                .size(320.dp, 109.dp)
                .background(Ink)
                .border(2.dp, Ink)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "DEMO · NO REAL CALL",
                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                    color = Ink, letterSpacing = 1.sp,
                    modifier = Modifier.background(Lime).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        ended -> "Call to 112 ended"
                        signal -> "Calling 112…"
                        else -> "Waiting for signal…"
                    },
                    fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 22.sp, color = Color.White,
                )
                Text(
                    when {
                        ended -> "Police / emergency services"
                        signal -> "Police / emergency services · ${clock(elapsed)}"
                        else -> "will call 112 the moment there's signal"
                    },
                    fontFamily = Mono, fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f),
                )
            }
            if (!ended) {
                Box(
                    Modifier
                        .size(52.dp)
                        .background(Pink, shape = androidx.compose.foundation.shape.CircleShape)
                        .border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape)
                        .clickable(onClick = onEnd)
                        .semantics { contentDescription = "End demo call" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("END", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun StatusRow(step: Step, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        val (bg, mark) = when (step) {
            Step.DONE -> Lime to "✓"
            Step.FAILED -> Pink to "✕"
            Step.WORKING -> SkyBottom to "…"
            Step.DEMO -> Color.White to "D"
            Step.IDLE -> SkyBottom to ""
        }
        Box(
            Modifier
                .size(26.dp)
                .background(bg)
                .border(2.dp, Ink),
            contentAlignment = Alignment.Center,
        ) {
            Text(mark, fontWeight = FontWeight.Black, fontSize = 14.sp, color = Ink)
        }
        Column(Modifier.padding(start = 12.dp).fillMaxWidth()) {
            Text(title, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Ink, letterSpacing = 1.sp)
            Text(detail, fontFamily = Mono, fontSize = 11.sp, color = Ink)
        }
    }
}

private fun clock(seconds: Long) = "%02d:%02d".format(seconds / 60, seconds % 60)

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
private fun InfoColumns(contact: EmergencyContact?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfoBlock("WHO HEARS YOU", "everyone with Ekta", "within $Radius m · even offline", Modifier.weight(1f))
        Box(Modifier.width(1.5.dp).height(78.dp).background(Ink))
        InfoBlock(
            "ON SOS",
            "112 is called (demo)",
            if (contact == null) "+ SMS to your contact" else "+ live location SMS",
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChunkyButton(
    width: Dp,
    height: Dp,
    enabled: Boolean = true,
    color: Color = Lime,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
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
                .background(if (enabled) color else color.copy(alpha = 0.85f))
                .border(2.dp, Ink)
                .combinedClickable(
                    interactionSource = interaction, indication = null,
                    enabled = enabled, role = Role.Button,
                    onLongClick = onLongClick, onClick = onClick,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { content() }
    }
}

@Composable
private fun SosButton(onClick: () -> Unit, onLongClick: () -> Unit) {
    ChunkyButton(
        width = 230.dp, height = 120.dp, onClick = onClick,
        onLongClick = onLongClick, // hidden: "I'm safe now"
        modifier = Modifier.semantics { contentDescription = "SOS. Alert everyone within $Radius metres" },
    ) {
        Text("SOS", fontSize = 52.sp, fontWeight = FontWeight.Black, color = Ink, letterSpacing = 6.sp)
        Text("press for help", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Ink)
    }
}
