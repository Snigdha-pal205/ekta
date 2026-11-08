package com.ekta.sos

import androidx.compose.ui.res.stringResource
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
/** Typewriter font for English; the normal font for Indian scripts (monospace spacing breaks them up). */
val Mono: FontFamily get() = if (Lang.latinScript) FontFamily.Monospace else FontFamily.Default
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
    onDestination: () -> Unit = {},
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
                stringResource(R.string.tagline),
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                color = Ink, letterSpacing = 0.5.sp,
            )
            Spacer(Modifier.height(28.dp))
            SosButton(onClick = onSos, onLongClick = onSafeNow)
            Spacer(Modifier.height(18.dp))
            Text(
                if (contact == null) stringResource(R.string.add_contact) else stringResource(R.string.contact_is, contact.name),
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink,
                textDecoration = TextDecoration.Underline, textAlign = TextAlign.Center,
                modifier = Modifier
                    .clickable(onClick = onEditContact)
                    .padding(6.dp),
            )
            Text(
                stringResource(R.string.press_only),
                fontFamily = Mono, fontSize = 12.sp, color = Ink,
            )
            // ---- dividing line, then "Know your destination" (Safe Score) ----
            Spacer(Modifier.height(22.dp))
            Box(Modifier.width(300.dp).height(3.dp).background(Ink))
            Spacer(Modifier.height(22.dp))
            ChunkyButton(width = 300.dp, height = 74.dp, color = Color.White, onClick = onDestination) {
                Text(stringResource(R.string.know_dest), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 17.sp, color = Ink, letterSpacing = 1.sp)
                Text(stringResource(R.string.know_dest_d), fontFamily = Mono, fontSize = 11.sp, color = Ink)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings),
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Ink,
                letterSpacing = 1.sp, textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable(onClick = onSettings)
                    .padding(8.dp),
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(150.dp)) // room for the forest
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
                stringResource(R.string.sos_active, clock(elapsed)),
                fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 16.sp,
                color = Ink, letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(20.dp))

            DemoCallCard(elapsed, state.callEnded, state.signal, onEndCall)
            Spacer(Modifier.height(14.dp))
            NearbyCallsCard(state.alertId)
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
                    stringResource(R.string.st_location),
                    when {
                        !state.gpsOn -> stringResource(R.string.st_gps_off)
                        loc == null -> stringResource(R.string.st_searching_gps)
                        age > LiveLocation.FRESH_MS -> stringResource(R.string.st_last_known, (age / 60_000).toInt())
                        loc.accuracy > 30f -> stringResource(R.string.st_more_exact, loc.accuracy.toInt())
                        else -> stringResource(R.string.st_live, loc.accuracy.toInt())
                    },
                )
                StatusRow(state.mesh, stringResource(R.string.st_mesh), state.meshNote)
                StatusRow(state.upload, if (state.online) stringResource(R.string.st_internet) else stringResource(R.string.st_internet_off), state.uploadNote)
                StatusRow(
                    state.contactSms,
                    stringResource(R.string.st_sms_to, contact?.name?.uppercase() ?: stringResource(R.string.settings_contact)),
                    state.contactNote,
                )
            }
            if (state.smsPreview.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "\u201C${state.smsPreview}\u201D",
                    fontFamily = Mono, fontSize = 10.sp, color = Ink.copy(alpha = 0.75f),
                    modifier = Modifier.width(320.dp).padding(start = 38.dp),
                )
            }

            Spacer(Modifier.height(30.dp))
            ChunkyButton(width = 260.dp, height = 78.dp, color = Color.White, onClick = onCancel) {
                Text(stringResource(R.string.cancel_alert), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 22.sp, color = Ink, letterSpacing = 2.sp)
                Text(stringResource(R.string.needs_pin), fontFamily = Mono, fontSize = 11.sp, color = Ink)
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
                    stringResource(R.string.demo_no_call),
                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                    color = Ink, letterSpacing = 1.sp,
                    modifier = Modifier.background(Lime).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        ended -> stringResource(R.string.call_ended)
                        signal -> stringResource(R.string.calling_112)
                        else -> stringResource(R.string.waiting_signal)
                    },
                    fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 22.sp, color = Color.White,
                )
                Text(
                    when {
                        ended -> stringResource(R.string.police_services)
                        signal -> stringResource(R.string.police_services) + " · ${clock(elapsed)}"
                        else -> stringResource(R.string.call_when_signal)
                    },
                    fontFamily = Mono, fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f),
                )
            }
            val endDesc = stringResource(R.string.end_demo_call)
            if (!ended) {
                Box(
                    Modifier
                        .size(52.dp)
                        .background(Pink, shape = androidx.compose.foundation.shape.CircleShape)
                        .border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape)
                        .clickable(onClick = onEnd)
                        .semantics { contentDescription = endDesc },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.end), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}

/**
 * Drop-down under the 112 card: the pharmacies, shops and police booths with a phone
 * number found near her. DEMO ONLY: they are shown as "called", but nothing is dialled.
 */
@Composable
private fun NearbyCallsCard(alertId: String) {
    val lookup by NearbyPlaces.state.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    val mine = lookup.alertId == alertId
    val places = if (mine) lookup.places.filter { !it.phone.isNullOrBlank() }.sortedBy { it.distanceM } else emptyList()
    val busy = !mine || lookup.status in setOf(
        NearbyPlaces.Status.IDLE, NearbyPlaces.Status.SEARCHING,
        NearbyPlaces.Status.RETRYING, NearbyPlaces.Status.WAITING_INTERNET,
    )
    val title = when {
        places.isNotEmpty() -> stringResource(R.string.nearby_called, places.size)
        busy && mine && lookup.status == NearbyPlaces.Status.WAITING_INTERNET -> stringResource(R.string.waiting_internet)
        busy -> stringResource(R.string.finding_shops)
        else -> stringResource(R.string.no_numbers)
    }

    val nearbyDesc = stringResource(R.string.nearby_desc)
    Column(Modifier.width(320.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Ink)
                .border(2.dp, Ink)
                .clickable(enabled = places.isNotEmpty()) { open = !open }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { contentDescription = nearbyDesc },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.demo_no_calls),
                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                    color = Ink, letterSpacing = 1.sp,
                    modifier = Modifier.background(Lime).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(title, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 17.sp, color = Color.White)
                Text(
                    stringResource(R.string.nearby_types, NearbyPlaces.RADIUS_LABEL),
                    fontFamily = Mono, fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f),
                )
            }
            if (places.isNotEmpty()) {
                Text(if (open) "▲" else "▼", fontSize = 18.sp, color = Color.White)
            }
        }
        if (open) {
            Text(
                stringResource(R.string.recorded_msg, NearbyAlert.voiceText(NearbyAlert.landmark(places))),
                fontFamily = Mono, fontSize = 11.sp, color = Ink,
                modifier = Modifier.fillMaxWidth().background(Lime).border(2.dp, Ink).padding(10.dp),
            )
            places.forEach { p ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .border(2.dp, Ink)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            categoryLabel(p.category), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 9.sp, color = Ink,
                            modifier = Modifier.background(categoryColor(p.category)).padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                        Text(
                            if (p.distanceM >= 1000) String.format(java.util.Locale.US, "%.1f km", p.distanceM / 1000.0) else "${p.distanceM} m",
                            fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Ink,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.called_demo), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 9.sp, color = Ink)
                    }
                    Text(p.name, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink)
                    // Plain text on purpose: tapping it does nothing (no real calls in this demo).
                    Text("☎ ${p.phone}", fontFamily = Mono, fontSize = 11.sp, color = Ink)
                    // Where it is (e.g. "Prithviraj Chauhan Marg, Makanpur Colony"), so she can tell which side of the road.
                    p.address?.takeIf { it.isNotBlank() }?.let { addr ->
                        Text(
                            "📍 " + addr.split(",").map { it.trim() }
                                .filter { part -> part.isNotEmpty() && !part.any(Char::isDigit) || part.any(Char::isLetter) && part.length > 6 }
                                .take(2).joinToString(", "),
                            fontSize = 10.sp, color = Ink.copy(alpha = 0.7f), maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun categoryLabel(c: NearbyPlaces.Category) = stringResource(
    when (c) {
        NearbyPlaces.Category.POLICE -> R.string.cat_police
        NearbyPlaces.Category.PHARMACY -> R.string.cat_pharmacy
        NearbyPlaces.Category.SHOP -> R.string.cat_shop
    },
)

private fun categoryColor(c: NearbyPlaces.Category) = when (c) {
    NearbyPlaces.Category.POLICE -> Color(0xFF9FC3FF)
    NearbyPlaces.Category.PHARMACY -> Color(0xFFB8F0C8)
    NearbyPlaces.Category.SHOP -> Lime
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
    val sosDesc = stringResource(R.string.sos_desc, Radius)
    ChunkyButton(
        width = 230.dp, height = 120.dp, onClick = onClick,
        onLongClick = onLongClick, // hidden: "I'm safe now"
        modifier = Modifier.semantics { contentDescription = sosDesc },
    ) {
        Text("SOS", fontSize = 52.sp, fontWeight = FontWeight.Black, color = Ink, letterSpacing = 6.sp)
        Text(stringResource(R.string.press_for_help), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Ink)
    }
}
