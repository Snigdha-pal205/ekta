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
 * Settings → WOMEN COMMITTEE: the numbers the women's committee SMS would go to.
 * DEMO: shown as plain text; nobody is ever called or messaged.
 */
@Composable
fun WomenCommitteeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { WomenHelp.load(context) }
    val lookup by WomenHelp.state.collectAsState()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }

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
                Text("WOMEN COMMITTEE", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 22.sp, color = Ink, letterSpacing = 2.sp)
                Text(
                    "Who the women's committee SMS would go to on SOS: women's helplines and nearby women's offices.",
                    fontFamily = Mono, fontSize = 12.sp, color = Ink,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "DEMO · nobody on this list is called or messaged",
                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Ink,
                    modifier = Modifier.background(Lime).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            item {
                Column(
                    Modifier.fillMaxWidth().background(Color.White).border(2.dp, Ink).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text("STATUS: ${statusText(lookup.status)}", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 13.sp, color = Ink)
                    if (lookup.message.isNotBlank()) Text(lookup.message, fontFamily = Mono, fontSize = 12.sp, color = Ink)
                    if (lookup.at > 0) {
                        Text(
                            "at ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lookup.at))}" +
                                " · from ${String.format(Locale.US, "%.5f, %.5f", lookup.lat, lookup.lng)}",
                            fontFamily = Mono, fontSize = 11.sp, color = Ink,
                        )
                    }
                    Text("${lookup.total} numbers in total", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Ink)
                }
            }
            item {
                ChunkyButton(width = 320.dp, height = 56.dp, color = Color.White, onClick = {
                    note = "getting your location…"
                    scope.launch {
                        val loc = try { SosRepository.currentLocation(context) } catch (_: Exception) { null }
                        if (loc == null) {
                            note = "couldn't get your location · is Location (GPS) on?"
                        } else {
                            note = null
                            WomenHelp.search(context, MANUAL_ID, loc.latitude, loc.longitude)
                        }
                    }
                }) {
                    Text("SEARCH AGAIN", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 16.sp, color = Ink, letterSpacing = 1.sp)
                    Text("around my location now", fontFamily = Mono, fontSize = 10.sp, color = Ink)
                }
                note?.let { Text(it, fontFamily = Mono, fontSize = 11.sp, color = Ink) }
            }

            item { Title("WOMEN'S HELPLINES") }
            items(WomenHelp.HELPLINES, key = { "h" + it.phone }) { ContactRow(it) }

            item { Title("NEARBY WOMEN'S OFFICES (within ${WomenHelp.OFFICE_RADIUS_M / 1000} km)") }
            if (lookup.offices.isEmpty()) {
                item {
                    Text(
                        if (lookup.status == NearbyPlaces.Status.DONE) "None with a phone number listed near you." else "—",
                        fontFamily = Mono, fontSize = 12.sp, color = Ink,
                    )
                }
            }
            items(lookup.offices, key = { "o" + it.name + it.phone }) { ContactRow(it) }


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

@Composable
private fun Title(text: String) {
    Text(text, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 13.sp, color = Ink, letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ContactRow(c: WomenHelp.Contact) {
    Column(
        Modifier.fillMaxWidth().background(Color.White).border(2.dp, Ink).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                c.kind.uppercase(), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 10.sp, color = Ink,
                modifier = Modifier.background(Color(0xFFF7C6DA)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            c.distanceM?.let { d ->
                Text(
                    if (d >= 1000) String.format(Locale.US, "%.1f km", d / 1000.0) else "$d m",
                    fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 13.sp, color = Ink,
                )
            }
        }
        Text(c.name, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink)
        // Plain text on purpose: tapping does nothing (no calling in this demo).
        Text("phone: ${c.phone}", fontFamily = Mono, fontSize = 12.sp, color = Ink)
        c.address?.let { Text(it, fontFamily = Mono, fontSize = 10.sp, color = Ink.copy(alpha = 0.7f)) }
    }
}

private fun statusText(s: NearbyPlaces.Status) = when (s) {
    NearbyPlaces.Status.IDLE -> "no search yet (press SOS or SEARCH AGAIN)"
    NearbyPlaces.Status.WAITING_INTERNET -> "WAITING FOR INTERNET"
    NearbyPlaces.Status.SEARCHING -> "SEARCHING…"
    NearbyPlaces.Status.RETRYING -> "RETRYING"
    NearbyPlaces.Status.DONE -> "DONE"
    NearbyPlaces.Status.ERROR -> "ERROR"
}
