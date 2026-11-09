package com.ekta.sos

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay


/** Seconds between tapping SOS and the alert going out (time to cancel a mistake). */
private const val COUNTDOWN_S = 10

@Composable
fun SosScreen(
    state: SosState,
    contact: EmergencyContact?,
    onSos: () -> Unit,
    onCancel: () -> Unit,
    onEndCall: () -> Unit,
    onEditContact: () -> Unit,
    onSafeNow: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onSettings: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onDestination: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    // In duress the alert keeps running, but her screen must look completely normal.
    if (state.active && !state.hidden) {
        ActivePage(state, contact, onCancel, onEndCall)
        return
    }
    var counting by rememberSaveable { mutableStateOf(false) }
    if (counting) {
        Countdown(
            onSend = { counting = false; onSos() },
            onCancel = { counting = false },
        )
        return
    }
    // One clock for the heart and the blood in the veins.
    val clock = heartClock()
    EktaBackground(heart = clock, clearCenter = true) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SareeBorder(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).padding(horizontal = 24.dp)) {
            // Header row, same height and top as the ⚙ (drawn by MainActivity, top-right).
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(22f)
            }

            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Trinetra(250.dp) }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SosButton(
                    onClick = { counting = true }, onLongClick = onSafeNow,
                    clock = clock,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.sos_hint, stringResource(R.string.radius_label), COUNTDOWN_S),
                fontSize = 14.sp, lineHeight = 20.sp, color = Ash, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.weight(1f))

            // Only when it matters: without a contact, no one gets her SMS.
            if (contact == null) {
                MissingContact(onEditContact)
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
        SareeBorder(Modifier.fillMaxWidth().rotate(180f))
        bottomBar()
        }
    }
}

/** The SOS button: her heart. Beats lub-dub with a burst of power; long-press (hidden): "I'm safe now". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SosButton(onClick: () -> Unit, onLongClick: () -> Unit, clock: androidx.compose.runtime.State<Float>, modifier: Modifier = Modifier) {
    val sosDesc = stringResource(R.string.sos_desc, stringResource(R.string.radius_label))
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = animateFloatAsState(if (pressed) 0.95f else 1f, label = "sos")
    Box(modifier.size(330.dp), contentAlignment = Alignment.Center) {
        // deep red glow: drawn once; it swells and brightens with each beat
        Canvas(
            Modifier.size(330.dp).graphicsLayer {
                val b = heartBeat(clock.value)
                alpha = 0.55f + 0.45f * b
                scaleX = 1f + 0.08f * b; scaleY = scaleX
            },
        ) {
            val r = size.width * 0.62f
            drawCircle(Brush.radialGradient(listOf(Crimson.copy(alpha = 0.5f), Blood.copy(alpha = 0.2f), Color.Transparent), center = center, radius = r), radius = r)
        }
        Prabhamandala(330.dp, discRadius = 92.dp)
        // the heart
        Box(
            Modifier
                .graphicsLayer {
                    val b = heartBeat(clock.value)
                    scaleX = press.value * (1f + 0.06f * b); scaleY = scaleX
                }
                .size(184.dp)
                .background(if (pressed) Blood else Crimson, CircleShape)
                .combinedClickable(
                    interactionSource = interaction, indication = null, role = Role.Button,
                    onLongClick = onLongClick, onClick = onClick,
                )
                .semantics { contentDescription = sosDesc },
            contentAlignment = Alignment.Center,
        ) {
            Text("SOS", fontFamily = Display, fontSize = 64.sp, color = Bone, letterSpacing = 4.sp)
        }
    }
}

@Composable
private fun MissingContact(onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Coal, Round).border(1.dp, Crimson, Round).clickable(onClick = onAdd)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Person, contentDescription = null, tint = Crimson, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.add_contact), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Bone, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.add), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Crimson)
    }
}

/** 10 seconds, then the SOS goes out by itself. One button only: Cancel (no PIN needed, nothing has been sent yet). */
@Composable
private fun Countdown(onSend: () -> Unit, onCancel: () -> Unit) {
    var left by remember { mutableIntStateOf(COUNTDOWN_S) }
    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1_000)
            left--
        }
        onSend()
    }
    val progress by animateFloatAsState(left / COUNTDOWN_S.toFloat(), label = "count")
    EktaBackground(clearCenter = true) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        SareeBorder(Modifier.fillMaxWidth())
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.countdown_title), fontSize = 18.sp, color = Ash, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(24.dp))
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val st = 10.dp.toPx()
                    drawCircle(Coal2, radius = size.width / 2 - st, style = Stroke(st))
                    drawArc(
                        Crimson, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                        topLeft = Offset(st, st), size = androidx.compose.ui.geometry.Size(size.width - 2 * st, size.height - 2 * st),
                        style = Stroke(st, cap = StrokeCap.Round),
                    )
                }
                Text("$left", fontSize = 96.sp, fontWeight = FontWeight.Black, color = Bone)
            }
            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.countdown_hint), fontSize = 13.sp, color = Ash, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            ChunkyButton(width = 360.dp, height = 64.dp, onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel), fontSize = 19.sp, fontWeight = FontWeight.Black, color = Bone, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(20.dp))
        }
        SareeBorder(Modifier.fillMaxWidth().rotate(180f))
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
    val b = breathing(1400)

    EktaBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            SareeBorder(Modifier.fillMaxWidth())
            // Scrolling content
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            ) {
                Spacer(Modifier.height(40.dp)) // room for the ⚙
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(Crimson.copy(alpha = 0.5f + 0.5f * (1 - b)), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.sos_active, clock(elapsed)), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Gold, letterSpacing = 1.sp)
                }
                Text(stringResource(R.string.help_on_way), fontSize = 28.sp, fontWeight = FontWeight.Black, color = Bone, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(18.dp))

                DemoCallCard(elapsed, state.callEnded, state.signal, onEndCall)
                Spacer(Modifier.height(10.dp))
                NearbyCallsCard(state.alertId)
                Spacer(Modifier.height(10.dp))

                Column(
                    Modifier.fillMaxWidth().background(Coal, Round).border(1.dp, Line, Round).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
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
                        stringResource(R.string.st_sms_to, contact?.name ?: stringResource(R.string.settings_contact)),
                        state.contactNote,
                    )
                    if (state.smsPreview.isNotBlank()) {
                        Text(
                            "“${state.smsPreview}”",
                            fontSize = 11.sp, color = Ash.copy(alpha = 0.8f),
                            modifier = Modifier.padding(start = 38.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            // Always on screen: cancelling never scrolls away.
            Column(
                Modifier.fillMaxWidth().background(Night).padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ChunkyButton(width = 360.dp, height = 60.dp, color = Coal, onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel_alert), fontSize = 17.sp, fontWeight = FontWeight.Black, color = Bone, letterSpacing = 1.sp)
                    Text(stringResource(R.string.needs_pin), fontSize = 11.sp, color = Ash)
                }
            }
        }
    }
}

/** Looks like an ongoing call, but nothing is dialled. */
@Composable
private fun DemoCallCard(elapsed: Long, ended: Boolean, signal: Boolean, onEnd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Coal, Round).border(1.dp, Line, Round)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            DemoTag(stringResource(R.string.demo_no_call))
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    ended -> stringResource(R.string.call_ended)
                    signal -> stringResource(R.string.calling_112)
                    else -> stringResource(R.string.waiting_signal)
                },
                fontSize = 21.sp, fontWeight = FontWeight.Black, color = Bone,
            )
            Text(
                when {
                    ended -> stringResource(R.string.police_services)
                    signal -> stringResource(R.string.police_services) + " · ${clock(elapsed)}"
                    else -> stringResource(R.string.call_when_signal)
                },
                fontSize = 12.sp, color = Ash,
            )
        }
        val endDesc = stringResource(R.string.end_demo_call)
        if (!ended) {
            Box(
                Modifier.size(52.dp).background(Crimson, CircleShape).clickable(onClick = onEnd)
                    .semantics { contentDescription = endDesc },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.end), fontWeight = FontWeight.Black, fontSize = 12.sp, color = Bone) }
        }
    }
}

@Composable
private fun DemoTag(text: String) {
    Text(
        text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Gold, letterSpacing = 1.sp,
        modifier = Modifier.border(1.dp, Gold.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
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
    Column(Modifier.fillMaxWidth().background(Coal, Round).border(1.dp, Line, Round)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = places.isNotEmpty()) { open = !open }
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .semantics { contentDescription = nearbyDesc },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                DemoTag(stringResource(R.string.demo_no_calls))
                Spacer(Modifier.height(6.dp))
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Black, color = Bone)
                Text(stringResource(R.string.nearby_types, NearbyPlaces.RADIUS_LABEL), fontSize = 12.sp, color = Ash)
            }
            if (places.isNotEmpty()) Text(if (open) "▲" else "▼", fontSize = 16.sp, color = Ash)
        }
        if (open) {
            Text(
                stringResource(R.string.recorded_msg, NearbyAlert.voiceText(NearbyAlert.landmark(places))),
                fontSize = 12.sp, color = Bone,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(Coal2, RoundedCornerShape(12.dp)).padding(12.dp),
            )
            Spacer(Modifier.height(6.dp))
            places.forEach { p ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            categoryLabel(p.category), fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Night,
                            modifier = Modifier.background(categoryColor(p.category), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                        Text(
                            if (p.distanceM >= 1000) String.format(java.util.Locale.US, "%.1f km", p.distanceM / 1000.0) else "${p.distanceM} m",
                            fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Ash,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.called_demo), fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Ash)
                    }
                    Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Bone)
                    // Plain text on purpose: tapping it does nothing (no real calls in this demo).
                    Text("☎ ${p.phone}", fontSize = 12.sp, color = Ash)
                    // Where it is (e.g. "Prithviraj Chauhan Marg, Makanpur Colony"), so she can tell which side of the road.
                    p.address?.takeIf { it.isNotBlank() }?.let { addr ->
                        Text(
                            "📍 " + addr.split(",").map { it.trim() }
                                .filter { part -> part.isNotEmpty() && !part.any(Char::isDigit) || part.any(Char::isLetter) && part.length > 6 }
                                .take(2).joinToString(", "),
                            fontSize = 11.sp, color = Ash.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Line))
            }
            Spacer(Modifier.height(6.dp))
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
    NearbyPlaces.Category.POLICE -> Bone
    NearbyPlaces.Category.PHARMACY -> Bone
    NearbyPlaces.Category.SHOP -> Bone
}

private fun clock(seconds: Long) = "%02d:%02d".format(seconds / 60, seconds % 60)
