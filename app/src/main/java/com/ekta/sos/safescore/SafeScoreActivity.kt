package com.ekta.sos.safescore

import com.ekta.sos.R
import androidx.compose.ui.res.stringResource
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ekta.sos.ChunkyButton
import com.ekta.sos.IllustratedBackground
import com.ekta.sos.Ink
import com.ekta.sos.Lime
import com.ekta.sos.Mono
import com.ekta.sos.SosRepository
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/** "Know your destination": search a place, pick an hour, see how safe it is. */
class SafeScoreActivity : ComponentActivity() {
    private var nearLat: Double? = null
    private var nearLng: Double? = null

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.ekta.sos.Lang.wrap(base))

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Rough position only to rank search suggestions near her; never uploaded.
        try {
            LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { it?.let { l -> nearLat = l.latitude; nearLng = l.longitude } }
        } catch (_: Exception) {
        }
        setContent { SafeScorePage(onBack = { finish() }, near = { nearLat to nearLng }, share = ::shareJourney) }
    }

    /** Share journey: the system share sheet with a ready message (she chooses who gets it). */
    private fun shareJourney(r: SafeScoreResult) {
        val link = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", r.lat, r.lng)
        val text = getString(R.string.share_text, r.placeName, Scoring.hourLabel(r.hour), fmt(r.safeScore), r.label, link)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), getString(R.string.share_journey_title)))
    }
}

// ---------- colours ----------
private val Green = Color(0xFF2E9E5B)
private val Orange = Color(0xFFF08A24)
private val Red = Color(0xFFE0413A)
private val Yellow = Color(0xFFF2C230)
private fun Level.color() = when (this) { Level.GREEN -> Green; Level.ORANGE -> Orange; Level.RED -> Red; Level.YELLOW -> Yellow }
private fun Level.emoji() = when (this) { Level.GREEN -> "🟢"; Level.ORANGE -> "🟠"; Level.RED -> "🔴"; Level.YELLOW -> "🟡" }
private fun fmt(d: Double) = String.format(Locale.US, "%.1f", d)

@Composable
private fun SafeScorePage(onBack: () -> Unit, near: () -> Pair<Double?, Double?>, share: (SafeScoreResult) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val nowHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }

    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<PlaceHit>>(emptyList()) }
    var suggestError by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<PlaceHit?>(null) }
    var hour by remember { mutableStateOf(nowHour) }
    var result by remember { mutableStateOf<SafeScoreResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var map by remember { mutableStateOf<Bitmap?>(null) }
    var myStars by remember { mutableStateOf(0) }
    var rateNote by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    // Suggestions as she types (waits for a short pause, so we don't search every letter).
    LaunchedEffect(query) {
        if (picked != null && query == picked?.name) return@LaunchedEffect
        suggestError = null
        if (query.trim().length < 3) { suggestions = emptyList(); return@LaunchedEffect }
        delay(500)
        try {
            val (la, lo) = near()
            suggestions = SafeScoreEngine.suggest(query, la, lo)
            if (suggestions.isEmpty()) suggestError = context.getString(R.string.place_not_found)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // she typed more: this search was replaced, not failed
        } catch (e: SafeScoreError) {
            suggestions = emptyList(); suggestError = e.text(context)
        } catch (e: Exception) {
            suggestions = emptyList(); suggestError = context.getString(R.string.search_failed)
        }
    }

    // Work out the score whenever the place, hour or a rating changes.
    LaunchedEffect(picked, hour, reload) {
        val p = picked ?: return@LaunchedEffect
        loading = true; error = null
        try {
            result = SafeScoreEngine.calculate(context, p, hour)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // a newer place/hour replaced this one
        } catch (e: SafeScoreError) {
            result = null; error = e.text(context)
        } catch (e: Exception) {
            result = null; error = context.getString(R.string.score_failed)
        }
        loading = false
    }
    LaunchedEffect(picked) {
        map = null
        val p = picked ?: return@LaunchedEffect
        map = withContext(Dispatchers.IO) { MapApi.mapImage(p.lat, p.lng) }
    }

    IllustratedBackground(forest = false) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.back_arrow), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink,
                    modifier = Modifier.clickable(onClick = onBack).padding(vertical = 8.dp),
                )
                Text(stringResource(R.string.ss_title1), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 26.sp, color = Ink, letterSpacing = 2.sp)
                Text(stringResource(R.string.ss_title2), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 26.sp, color = Ink, letterSpacing = 2.sp)
                Text(
                    stringResource(R.string.ss_intro),
                    fontFamily = Mono, fontSize = 12.sp, color = Ink,
                )
            }

            // ---- Search ----
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; if (picked != null && it != picked?.name) { picked = null; result = null } },
                    label = { Text(stringResource(R.string.ss_where), fontFamily = Mono) },
                    placeholder = { Text(stringResource(R.string.ss_example), fontFamily = Mono, fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Ink, unfocusedBorderColor = Ink, focusedLabelColor = Ink, unfocusedLabelColor = Ink,
                        focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                        focusedTextColor = Ink, unfocusedTextColor = Ink, cursorColor = Ink,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                suggestError?.let { Note(it) }
            }
            if (picked == null) {
                items(suggestions, key = { "s" + it.id }) { s ->
                    Column(
                        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).border(2.dp, Ink, RoundedCornerShape(10.dp))
                            .clickable {
                                picked = s; query = s.name; suggestions = emptyList(); myStars = 0; rateNote = null; focus.clearFocus()
                            }.padding(12.dp),
                    ) {
                        Text("📍 ${s.name}", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Ink)
                        if (s.address.isNotBlank() && s.address != s.name) Text(s.address, fontFamily = Mono, fontSize = 11.sp, color = Ink.copy(alpha = 0.7f))
                    }
                }
            }

            picked?.let { p ->
                // ---- Map with the pin ----
                item { MapCard(map) }

                // ---- When ----
                item {
                    Text(stringResource(R.string.ss_when), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 13.sp, color = Ink, letterSpacing = 1.sp)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items((0..23).map { (nowHour + it) % 24 }) { h ->
                            val sel = h == hour
                            Text(
                                if (h == nowHour) stringResource(R.string.ss_now, Scoring.hourLabel(h)) else Scoring.hourLabel(h),
                                fontFamily = Mono, fontWeight = if (sel) FontWeight.Black else FontWeight.Bold, fontSize = 12.sp, color = Ink,
                                modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                    .background(if (sel) Lime else Color.White).border(2.dp, Ink, RoundedCornerShape(20.dp))
                                    .clickable { hour = h; myStars = 0; rateNote = null }.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                // ---- Result ----
                item {
                    when {
                        loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = Ink, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.ss_checking), fontFamily = Mono, fontSize = 13.sp, color = Ink)
                            }
                        }
                        error != null -> Column(
                            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).border(2.dp, Red, RoundedCornerShape(12.dp)).padding(14.dp),
                        ) {
                            Text("⚠ ${error}", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink)
                            Text(stringResource(R.string.try_again), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 13.sp, color = Ink,
                                modifier = Modifier.padding(top = 8.dp).clickable { reload++ }.padding(vertical = 6.dp))
                        }
                        result != null -> ResultCard(result!!, onShare = { share(result!!) })
                    }
                }

                // ---- Rate ----
                if (result != null && !loading) item {
                    Column(
                        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).border(2.dp, Ink, RoundedCornerShape(12.dp)).padding(14.dp),
                    ) {
                        Text(stringResource(R.string.ss_rate_q, Scoring.hourLabel(hour)), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink)
                        Row(Modifier.padding(top = 6.dp)) {
                            (1..5).forEach { n ->
                                Text(
                                    if (n <= myStars) "★" else "☆", fontSize = 34.sp, color = if (n <= myStars) Orange else Ink,
                                    modifier = Modifier.clickable {
                                        myStars = n; rateNote = context.getString(R.string.saving)
                                        scope.launch {
                                            rateNote = try {
                                                CommunityFactor.rate(SosRepository.deviceId(context), p, hour, n)
                                                reload++
                                                context.getString(R.string.ss_rate_thanks)
                                            } catch (_: Exception) {
                                                context.getString(R.string.ss_rate_failed)
                                            }
                                        }
                                    }.padding(end = 6.dp),
                                )
                            }
                        }
                        rateNote?.let { Text(it, fontFamily = Mono, fontSize = 11.sp, color = Ink) }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, fontFamily = Mono, fontSize = 12.sp, color = Ink,
        modifier = Modifier.padding(top = 6.dp).background(Color.White.copy(alpha = 0.8f)).padding(8.dp))
}

/** Street map of the place with a pin in the middle. */
@Composable
private fun MapCard(map: Bitmap?) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(600f / 340f).clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFDDE8F2)).border(2.dp, Ink, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (map != null) {
            Image(map.asImageBitmap(), contentDescription = stringResource(R.string.map_desc), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(stringResource(R.string.map_loading), fontFamily = Mono, fontSize = 12.sp, color = Ink)
        }
        // The pin (tip at the centre of the map).
        Canvas(Modifier.size(34.dp, 44.dp).offset(y = (-22).dp)) { // tip of the pin on the exact spot
            val w = size.width; val h = size.height
            val path = Path().apply {
                moveTo(w / 2, h)
                cubicTo(w * 0.15f, h * 0.62f, 0f, h * 0.45f, 0f, w / 2)
                cubicTo(0f, w * 0.2f, w * 0.2f, 0f, w / 2, 0f)
                cubicTo(w * 0.8f, 0f, w, w * 0.2f, w, w / 2)
                cubicTo(w, h * 0.45f, w * 0.85f, h * 0.62f, w / 2, h)
                close()
            }
            drawPath(path, Color(0xFFE84A8A))
            drawPath(path, Ink, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
            drawCircle(Color.White, radius = w * 0.17f, center = Offset(w / 2, w / 2))
        }
    }
}

/** The result card, in the order: header, 5 factors, safe score, message, Share journey. */
@Composable
private fun ResultCard(r: SafeScoreResult, onShare: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).border(2.dp, Ink, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("📍 ${r.placeName} · ${Scoring.hourLabel(r.hour)}", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 15.sp, color = Ink)
        r.factors.forEach { f ->
            Row(verticalAlignment = Alignment.Top) {
                Text(f.level.emoji(), fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${f.name}: ${f.text}", fontFamily = Mono, fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f),
                )
                // The number too, so the colour is never the only signal.
                Text(f.score?.let { fmt(it) } ?: "–", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Ink.copy(alpha = 0.7f))
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.copy(alpha = 0.15f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(74.dp).background(r.level.color(), CircleShape).border(3.dp, Ink, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(fmt(r.safeScore), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 24.sp, color = Ink)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(R.string.ss_score, fmt(r.safeScore)), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 16.sp, color = Ink)
                Text("${r.level.emoji()} ${r.label} · ${r.message}", fontFamily = Mono, fontSize = 13.sp, color = Ink)
            }
        }
        ChunkyButton(width = 300.dp, height = 56.dp, onClick = onShare) {
            Text(stringResource(R.string.share_journey), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 16.sp, color = Ink, letterSpacing = 1.sp)
        }
        Text(
            stringResource(R.string.ss_sample_note),
            fontFamily = Mono, fontSize = 10.sp, color = Ink.copy(alpha = 0.6f), textAlign = TextAlign.Start,
        )
    }
}
