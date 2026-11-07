package com.ekta.sos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Debug panel: the pharmacies, shops and police booths found within 1 km
 * when SOS was pressed. DEMO ONLY: phone numbers are shown as plain text and nobody
 * is ever called or messaged.
 */
@Composable
fun DebugPanelScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { NearbyPlaces.load(context) }
    val lookup by NearbyPlaces.state.collectAsState()
    val scope = rememberCoroutineScope()
    var searchNote by remember { mutableStateOf<String?>(null) }

    IllustratedBackground(forest = false) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(24.dp))
                Text("DEBUG PANEL", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 24.sp, color = Ink, letterSpacing = 2.sp)
                Text(
                    "Pharmacies, shops and police booths with a phone number, within ${NearbyPlaces.RADIUS_LABEL} when SOS was pressed.",
                    fontFamily = Mono, fontSize = 12.sp, color = Ink,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "DEMO · nobody on this list is called or messaged",
                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Ink,
                    modifier = Modifier.background(Lime).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            // ---- Status of the last lookup ----
            item {
                Column(
                    Modifier.fillMaxWidth().background(Color.White).border(2.dp, Ink).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text("STATUS: ${statusLabel(lookup.status)}", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 13.sp, color = Ink)
                    if (lookup.message.isNotBlank()) Text(lookup.message, fontFamily = Mono, fontSize = 12.sp, color = Ink)
                    if (lookup.source.isNotBlank()) Text("source: ${lookup.source}", fontFamily = Mono, fontSize = 11.sp, color = Ink)
                    if (lookup.at > 0) {
                        Text(
                            "at ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lookup.at))}" +
                                " · from ${String.format(Locale.US, "%.5f, %.5f", lookup.lat, lookup.lng)}",
                            fontFamily = Mono, fontSize = 11.sp, color = Ink,
                        )
                        Text(
                            if (lookup.alertId == MANUAL_ID) "started from this panel" else "SOS ${lookup.alertId.take(8)}",
                            fontFamily = Mono, fontSize = 11.sp, color = Ink,
                        )
                    }
                    lookup.errors.forEach { Text("⚠ $it", fontFamily = Mono, fontSize = 11.sp, color = Pink) }
                }
            }

            // ---- Controls (at the top, so they're never below a long list) ----
            item {
                Spacer(Modifier.height(4.dp))
                ChunkyButton(width = 320.dp, height = 56.dp, color = Color.White, onClick = {
                    searchNote = "getting your location…"
                    scope.launch {
                        val loc = try { SosRepository.currentLocation(context) } catch (_: Exception) { null }
                        if (loc == null) {
                            searchNote = "couldn't get your location · is Location (GPS) on?"
                        } else {
                            searchNote = null
                            NearbyPlaces.search(context, MANUAL_ID, loc.latitude, loc.longitude)
                        }
                    }
                }) {
                    Text("SEARCH AGAIN", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 16.sp, color = Ink, letterSpacing = 1.sp)
                    Text("around my location now", fontFamily = Mono, fontSize = 10.sp, color = Ink)
                }
                searchNote?.let { Text(it, fontFamily = Mono, fontSize = 11.sp, color = Ink) }
            }
            // ---- The list: within the radius first, then the nearest ones further away ----
            val near = lookup.places.filter { !it.far }
            val far = lookup.places.filter { it.far }
            if (lookup.status == NearbyPlaces.Status.DONE && near.isEmpty()) {
                item {
                    Text(
                        "No pharmacies, shops or police booths with a phone number within ${NearbyPlaces.RADIUS_LABEL}." +
                            if (lookup.source.startsWith("OpenStreetMap") && far.isEmpty())
                                " Places near you can be added (with their phone) on openstreetmap.org." else "",
                        fontFamily = Mono, fontSize = 13.sp, color = Ink,
                    )
                }
            }
            if (near.isNotEmpty()) item { SectionTitle("WITHIN ${NearbyPlaces.RADIUS_LABEL.uppercase()}") }
            items(near, key = { it.id }) { p -> PlaceRow(p) }
            if (far.isNotEmpty()) item { SectionTitle("NEAREST FURTHER AWAY") }
            items(far, key = { "far-" + it.id }) { p -> PlaceRow(p) }

            item {
                Text(
                    "BACK", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink,
                    modifier = Modifier.clickable(onClick = onBack).padding(10.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

const val MANUAL_ID = "manual"

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 13.sp, color = Ink, letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun PlaceRow(p: NearbyPlaces.Place) {
    Column(
        Modifier.fillMaxWidth().background(Color.White).border(2.dp, Ink).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                p.category.label.uppercase(), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 10.sp, color = Ink,
                modifier = Modifier.background(categoryColor(p.category)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Text(
                if (p.distanceM >= 1000) String.format(Locale.US, "%.1f km", p.distanceM / 1000.0) else "${p.distanceM} m",
                fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 13.sp, color = Ink,
            )
        }
        Text(p.name, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink)
        // Plain text on purpose: tapping it does nothing (no calling in this demo).
        Text("phone: ${p.phone ?: "not listed"}", fontFamily = Mono, fontSize = 12.sp, color = Ink)
        p.address?.let { Text(it, fontFamily = Mono, fontSize = 10.sp, color = Ink.copy(alpha = 0.7f)) }
    }
}

private fun categoryColor(c: NearbyPlaces.Category) = when (c) {
    NearbyPlaces.Category.POLICE -> Color(0xFF9FC3FF)
    NearbyPlaces.Category.PHARMACY -> Color(0xFFB8F0C8)
    NearbyPlaces.Category.SHOP -> Lime
}

private fun statusLabel(s: NearbyPlaces.Status) = when (s) {
    NearbyPlaces.Status.IDLE -> "no search yet (press SOS or SEARCH AGAIN)"
    NearbyPlaces.Status.WAITING_INTERNET -> "WAITING FOR INTERNET"
    NearbyPlaces.Status.SEARCHING -> "SEARCHING… (can take up to a minute)"
    NearbyPlaces.Status.RETRYING -> "SERVERS BUSY · RETRYING"
    NearbyPlaces.Status.DONE -> "DONE"
    NearbyPlaces.Status.ERROR -> "ERROR"
}
