package com.ekta.sos.safescore

import com.ekta.sos.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import com.ekta.sos.Crimson
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
import com.ekta.sos.Bone
import com.ekta.sos.Coal
import com.ekta.sos.Coal2
import com.ekta.sos.Line
import com.ekta.sos.Ash
import com.ekta.sos.Gold
import com.ekta.sos.Crimson
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
        enableEdgeToEdge(
            // Ekta is always black: white status/navigation bar icons, so the time and battery stay visible
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Rough position only to rank search suggestions near her; never uploaded.
        try {
            LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { it?.let { l -> nearLat = l.latitude; nearLng = l.longitude } }
        } catch (_: Exception) {
        }
        setContent { com.ekta.sos.EktaTheme { SafeScorePage(onBack = { finish() }, near = { nearLat to nearLng }, share = ::shareJourney) }}
    }

    private fun shareJourney(r: SafeScoreResult) = startActivity(shareJourneyIntent(this, r))
}

// ---------- colours ----------
private val Green = Color(0xFF2E9E5B)
private val Orange = Color(0xFFF08A24)
private val Red = Color(0xFFE0413A)
private val Yellow = Color(0xFFF2C230)
private fun Level.color() = when (this) { Level.GREEN -> Green; Level.ORANGE -> Orange; Level.RED -> Red; Level.YELLOW -> Yellow }
private fun Level.emoji() = when (this) { Level.GREEN -> "🟢"; Level.ORANGE -> "🟠"; Level.RED -> "🔴"; Level.YELLOW -> "🟡" }
private fun fmt(d: Double) = String.format(Locale.US, "%.1f", d)

/** Share journey: the system share sheet with a ready message (she chooses who gets it). */
private fun shareJourneyIntent(c: android.content.Context, r: SafeScoreResult): Intent {
    val link = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", r.lat, r.lng)
    val text = c.getString(R.string.share_text, r.placeName, Scoring.hourLabel(r.hour), fmt(r.safeScore), r.label, link)
    return Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), c.getString(R.string.share_journey_title))
}

/** Safe Score as a tab in the bottom bar (no back link; the bar is the way out). */
@SuppressLint("MissingPermission")
@Composable
fun SafeScoreTab() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var near by remember { mutableStateOf<Pair<Double?, Double?>>(null to null) }
    LaunchedEffect(Unit) {
        // Rough position only to rank search suggestions near her; never uploaded.
        try {
            LocationServices.getFusedLocationProviderClient(ctx).lastLocation
                .addOnSuccessListener { it?.let { l -> near = l.latitude to l.longitude } }
        } catch (_: Exception) {
        }
    }
    SafeScorePage(onBack = null, near = { near }, share = { r -> ctx.startActivity(shareJourneyIntent(ctx, r)) })
}

@Composable
private fun SafeScorePage(onBack: (() -> Unit)?, near: () -> Pair<Double?, Double?>, share: (SafeScoreResult) -> Unit) {
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
            Modifier.fillMaxSize().statusBarsPadding().then(if (onBack != null) Modifier.navigationBarsPadding() else Modifier).imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(12.dp))
                if (onBack != null) Text(
                    stringResource(R.string.back_arrow), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Bone,
                    modifier = Modifier.clickable(onClick = onBack).padding(vertical = 8.dp),
                ) else Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ss_heading), fontWeight = FontWeight.Bold, fontSize = 28.sp, color = Bone, lineHeight = 34.sp)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.ss_intro), fontSize = 15.sp, color = Ash, lineHeight = 21.sp)
            }

            // ---- Search ----
            item {
                val shape = RoundedCornerShape(16.dp)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; if (picked != null && it != picked?.name) { picked = null; result = null } },
                    placeholder = { Text(stringResource(R.string.ss_where), fontSize = 16.sp, color = Ash) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Ash) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; picked = null; result = null; suggestions = emptyList() }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ss_clear), tint = Ash)
                            }
                        }
                    },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
                    shape = shape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Crimson, unfocusedBorderColor = Line,
                        focusedContainerColor = Coal, unfocusedContainerColor = Coal,
                        focusedTextColor = Bone, unfocusedTextColor = Bone, cursorColor = Crimson,
                    ),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = context.getString(R.string.ss_where) },
                )
                suggestError?.let { Note(it) }
            }

            // ---- Before a place is chosen: useful things, not an empty page ----
            if (picked == null && suggestions.isEmpty()) {
                item {
                    var waiting by remember { mutableStateOf(false) }
                    ActionCard(
                        icon = Icons.Filled.MyLocation,
                        title = stringResource(R.string.ss_here),
                        subtitle = if (waiting) stringResource(R.string.ss_here_wait) else stringResource(R.string.ss_here_d),
                        filled = true,
                    ) {
                        val (la, lo) = near()
                        if (la != null && lo != null) {
                            val name = context.getString(R.string.ss_here_name)
                            picked = PlaceHit(String.format(Locale.US, "here:%.4f,%.4f", la, lo), name, "", la, lo)
                            query = name; hour = nowHour; myStars = 0; rateNote = null; waiting = false; focus.clearFocus()
                        } else waiting = true
                    }
                }
                val recent = RecentPlaces.load(context)
                if (recent.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.ss_recent)) }
                    items(recent, key = { "r" + it.id }) { r ->
                        ActionCard(icon = Icons.Filled.History, title = r.name, subtitle = r.address.takeIf { it.isNotBlank() && it != r.name }) {
                            picked = r; query = r.name; hour = nowHour; myStars = 0; rateNote = null; focus.clearFocus()
                        }
                    }
                }
                item { HowItWorks() }
            }
            if (picked == null) {
                items(suggestions, key = { "s" + it.id }) { s ->
                    Column(
                        Modifier.fillMaxWidth().background(Coal, RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp))
                            .clickable {
                                picked = s; query = s.name; suggestions = emptyList(); myStars = 0; rateNote = null; focus.clearFocus()
                                RecentPlaces.add(context, s)
                            }.padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(s.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Bone)
                        if (s.address.isNotBlank() && s.address != s.name) Text(s.address, fontSize = 13.sp, color = Ash)
                    }
                }
            }

            picked?.let { p ->
                // ---- Map with the pin ----
                item { MapCard(map) }

                // ---- When ----
                item {
                    Text(stringResource(R.string.ss_when_soft), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Bone)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items((0..23).map { (nowHour + it) % 24 }) { h ->
                            val sel = h == hour
                            Text(
                                if (h == nowHour) stringResource(R.string.ss_now, Scoring.hourLabel(h)) else Scoring.hourLabel(h),
                                fontFamily = Mono, fontWeight = if (sel) FontWeight.Black else FontWeight.Bold, fontSize = 12.sp, color = Bone,
                                modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                    .background(if (sel) Crimson else Coal2, RoundedCornerShape(20.dp)).border(1.dp, Line, RoundedCornerShape(20.dp))
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
                                CircularProgressIndicator(color = Bone, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.ss_checking), fontFamily = Mono, fontSize = 13.sp, color = Bone)
                            }
                        }
                        error != null -> Column(
                            Modifier.fillMaxWidth().background(Coal2, RoundedCornerShape(12.dp)).border(2.dp, Red, RoundedCornerShape(12.dp)).padding(14.dp),
                        ) {
                            Text("⚠ ${error}", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Bone)
                            Text(stringResource(R.string.try_again), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 13.sp, color = Bone,
                                modifier = Modifier.padding(top = 8.dp).clickable { reload++ }.padding(vertical = 6.dp))
                        }
                        result != null -> ResultCard(result!!, onShare = { share(result!!) })
                    }
                }

                // ---- Rate ----
                if (result != null && !loading) item {
                    Column(
                        Modifier.fillMaxWidth().background(Coal2, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(14.dp),
                    ) {
                        Text(stringResource(R.string.ss_rate_q, Scoring.hourLabel(hour)), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Bone)
                        Row(Modifier.padding(top = 6.dp)) {
                            (1..5).forEach { n ->
                                Text(
                                    if (n <= myStars) "★" else "☆", fontSize = 34.sp, color = if (n <= myStars) Orange else Bone,
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
                        rateNote?.let { Text(it, fontFamily = Mono, fontSize = 11.sp, color = Bone) }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/** A big, easy row button: icon, title, optional line under it. */
@Composable
private fun ActionCard(icon: ImageVector, title: String, subtitle: String?, filled: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape)
            .background(if (filled) Crimson else Coal, shape).border(1.dp, if (filled) Crimson else Line, shape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(if (filled) Bone.copy(alpha = 0.18f) else Coal2, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = if (filled) Bone else Crimson, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Bone, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, fontSize = 13.sp, color = if (filled) Bone.copy(alpha = 0.85f) else Ash, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = if (filled) Bone else Ash)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Ash, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

/** "How the score works": the five factors and what the colours mean. */
@Composable
private fun HowItWorks() {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).background(Coal, shape).border(1.dp, Line, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.ss_how), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Bone)
        listOf(
            Triple(Icons.Filled.ReportProblem, R.string.f_incidents, R.string.ss_how_inc),
            Triple(Icons.Filled.Groups, R.string.f_people, R.string.ss_how_ppl),
            Triple(Icons.Filled.DirectionsBus, R.string.f_transport, R.string.ss_how_tr),
            Triple(Icons.Filled.LocalPolice, R.string.f_police, R.string.ss_how_pol),
            Triple(Icons.Filled.Star, R.string.f_community, R.string.ss_how_com),
        ).forEach { (icon, name, desc) ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(36.dp).background(Coal2, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = Crimson, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(name), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Bone)
                    Text(stringResource(desc), fontSize = 13.sp, color = Ash, lineHeight = 18.sp)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Text(stringResource(R.string.ss_legend), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Bone)
        listOf(
            Triple(Green, R.string.v_safe, "8 – 10"),
            Triple(Yellow, R.string.v_fair, "7 – 7.9"),
            Triple(Orange, R.string.v_careful, "5 – 6.9"),
            Triple(Red, R.string.v_unsafe, "0 – 4.9"),
        ).forEach { (c, label, range) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(c, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(label), fontSize = 14.sp, color = Bone, modifier = Modifier.weight(1f))
                Text(range, fontSize = 14.sp, color = Ash)
            }
        }
    }
}

/** The last few places she checked (on this phone only). */
private object RecentPlaces {
    private const val KEY = "ss_recent"
    fun load(c: android.content.Context): List<PlaceHit> = try {
        val arr = org.json.JSONArray(c.getSharedPreferences("ekta", 0).getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PlaceHit(o.getString("id"), o.getString("name"), o.optString("address"), o.getDouble("lat"), o.getDouble("lng"))
        }
    } catch (_: Exception) { emptyList() }

    fun add(c: android.content.Context, p: PlaceHit) {
        val list = (listOf(p) + load(c).filter { it.id != p.id }).take(4)
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("address", it.address).put("lat", it.lat).put("lng", it.lng)) }
        c.getSharedPreferences("ekta", 0).edit().putString(KEY, arr.toString()).apply()
    }
}

@Composable
private fun Note(text: String) {
    Text(text, fontFamily = Mono, fontSize = 12.sp, color = Bone,
        modifier = Modifier.padding(top = 6.dp).background(Coal2.copy(alpha = 0.8f)).padding(8.dp))
}

/** Street map of the place with a pin in the middle. */
@Composable
private fun MapCard(map: Bitmap?) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(600f / 340f).clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFDDE8F2)).border(1.dp, Line, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (map != null) {
            Image(map.asImageBitmap(), contentDescription = stringResource(R.string.map_desc), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(stringResource(R.string.map_loading), fontFamily = Mono, fontSize = 12.sp, color = Bone)
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
            drawPath(path, Crimson)
            drawPath(path, Bone, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
            drawCircle(Bone, radius = w * 0.17f, center = Offset(w / 2, w / 2))
        }
    }
}

/** The result card, in the order: header, 5 factors, safe score, message, Share journey. */
@Composable
private fun ResultCard(r: SafeScoreResult, onShare: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Coal2, RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("📍 ${r.placeName} · ${Scoring.hourLabel(r.hour)}", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 15.sp, color = Bone)
        r.factors.forEach { f ->
            Row(verticalAlignment = Alignment.Top) {
                Text(f.level.emoji(), fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${f.name}: ${f.text}", fontFamily = Mono, fontSize = 13.sp, color = Bone, modifier = Modifier.weight(1f),
                )
                // The number too, so the colour is never the only signal.
                Text(f.score?.let { fmt(it) } ?: "–", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Bone.copy(alpha = 0.7f))
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Coal.copy(alpha = 0.15f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(74.dp).background(r.level.color(), CircleShape).border(1.dp, Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(fmt(r.safeScore), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 24.sp, color = Bone)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(R.string.ss_score, fmt(r.safeScore)), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 16.sp, color = Bone)
                Text("${r.level.emoji()} ${r.label} · ${r.message}", fontFamily = Mono, fontSize = 13.sp, color = Bone)
                if (r.nightCapped) {
                    Text("🌙 " + stringResource(R.string.ss_night_cap), fontFamily = Mono, fontSize = 11.sp, color = Bone.copy(alpha = 0.75f))
                }
            }
        }
        ChunkyButton(width = 300.dp, height = 56.dp, onClick = onShare) {
            Text(stringResource(R.string.share_journey), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 16.sp, color = Bone, letterSpacing = 1.sp)
        }
        Text(
            stringResource(R.string.ss_sample_note),
            fontFamily = Mono, fontSize = 10.sp, color = Bone.copy(alpha = 0.6f), textAlign = TextAlign.Start,
        )
    }
}
