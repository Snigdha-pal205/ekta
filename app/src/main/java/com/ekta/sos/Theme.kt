package com.ekta.sos

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/*
 * Ekta look: Shakti. Inspired by Durga and Kali: black of the night sky, kumkum red,
 * and the rescue grip (linked hands) as the mark. Calm and clean, so it reads
 * instantly under stress, but strong.
 */

// ---- Colours: black and red, nothing else ----
val Night = Color(0xFF000000)    // background: pitch black
val Coal = Color(0xFF0E0E0E)     // cards
val Coal2 = Color(0xFF181818)    // raised: inputs, keys
val Line = Color(0xFF262626)     // hairline borders
val Crimson = Color(0xFFE3141C)  // the red: SOS and main actions only
val Blood = Color(0xFF8E0B11)    // pressed / deep red
val Ember = Crimson
val Gold = Crimson               // accent = red (no third colour)
val Bone = Color(0xFFF4F4F4)     // text
val Ash = Color(0xFF9A9A9A)      // secondary text
val Leaf = Bone                  // "done" marks stay neutral

// Old names, kept so every screen picks up the new look.
val Ink = Bone
val Lime = Crimson
val Pink = Crimson
val SkyTop = Night
val SkyBottom = Coal2

/** Poppins (Indian Type Foundry): the text face for the whole app. Latin + Devanagari;
 *  other Indian scripts fall back to the phone's own font for those letters. */
val Body = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_bold, FontWeight.ExtraBold),
    Font(R.font.poppins_bold, FontWeight.Black),
)

/** Old name used across the screens. */
val Mono: FontFamily get() = Body

/** Wrap each screen's content so every Text uses Poppins by default. */
@Composable
fun EktaTheme(content: @Composable () -> Unit) {
    androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontFamily = Body), content)
}

/** Strong display face for the EKTA wordmark (Latin + Devanagari). */
val Display = FontFamily(Font(R.font.rozha_one, FontWeight.Normal))

val Round = RoundedCornerShape(16.dp)

/**
 * The background of every page: pitch black with a mesh of dark-red creeper vines
 * (leaves, tendrils, buds) growing in from the edges. With a [heart], the veins
 * flash on every beat and blood is pumped along them. [vines] off = plain black.
 */
@Composable
fun EktaBackground(
    @Suppress("UNUSED_PARAMETER") glowY: Float = 0f,
    @Suppress("UNUSED_PARAMETER") intensity: Float = 1f,
    vines: Boolean = true,
    heart: State<Float>? = null,
    clearCenter: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Night)) {
        if (vines) {
            VineMesh(Modifier.fillMaxSize(), heart, clearCenter)
            // nothing grows into the status bar or the navigation bar: the saree border frames the art
            Box(
                Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .windowInsetsTopHeight(WindowInsets.statusBars).background(Night),
            )
            Box(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .windowInsetsBottomHeight(WindowInsets.navigationBars).background(Night),
            )
        }
        content()
    }
}

// ---- The heart: one beat every 1.3 s (lub-dub) ----
const val BEAT_MS = 1300f
private const val CLOCK_BEATS = 40

/**
 * A clock in ms running over many beats. Returned as State so it is only read where
 * things are drawn (graphicsLayer / Canvas): animating it never rebuilds the screen.
 */
@Composable
fun heartClock(): State<Float> {
    val t = rememberInfiniteTransition(label = "heart")
    return t.animateFloat(
        0f, BEAT_MS * CLOCK_BEATS,
        infiniteRepeatable(tween((BEAT_MS * CLOCK_BEATS).toInt(), easing = androidx.compose.animation.core.LinearEasing)),
        label = "clock",
    )
}

/** Lub-dub: how strongly the heart is beating right now (0..1), smoothed. */
fun heartBeat(clock: Float): Float {
    val t = clock % BEAT_MS
    val k = floatArrayOf(0f, 110f, 240f, 340f, 620f, BEAT_MS)
    val v = floatArrayOf(0f, 1f, 0.2f, 0.75f, 0f, 0f)
    for (i in 0 until k.size - 1) if (t <= k[i + 1]) {
        val f = (t - k[i]) / (k[i + 1] - k[i])
        val e = f * f * (3 - 2 * f) // smoothstep: no sharp corners in the motion
        return v[i] + (v[i + 1] - v[i]) * e
    }
    return 0f
}

private class Vine(
    val path: Path, val leaves: List<Path>, val buds: List<Offset>, val length: Float,
    val phase: Float, val speed: Float, val measure: androidx.compose.ui.graphics.PathMeasure,
)

private fun buildVines(w: Float, h: Float, dp: Float, clearCenter: Boolean): List<Vine> {
    val rnd = kotlin.random.Random(11)
    // where each vine starts (x, y, heading in radians), from the edges inward
    val starts = listOf(
        Triple(0f, h * 0.10f, 0.35f), Triple(0f, h * 0.38f, -0.15f), Triple(0f, h * 0.66f, 0.25f), Triple(0f, h * 0.92f, -0.45f),
        Triple(w, h * 0.20f, 2.75f), Triple(w, h * 0.50f, 3.30f), Triple(w, h * 0.78f, 2.95f), Triple(w, h * 0.98f, 3.6f),
        Triple(w * 0.25f, 0f, 1.25f), Triple(w * 0.80f, h, -1.75f),
    )
    return starts.mapIndexed { vi, (sx, sy, sa) ->
        val pts = ArrayList<Offset>()
        var x = sx; var y = sy; var ang = sa
        val step = 9f * dp
        val n = 46 + rnd.nextInt(26)
        for (i in 0 until n) {
            pts += Offset(x, y)
            ang += (rnd.nextFloat() - 0.5f) * 0.42f + sin(i * 0.23f + vi) * 0.09f
            // keep the centre clear (SOS, halo, eyes, hint): turn back out when getting close
            val ex = (x + cos(ang) * step - w / 2) / (w * 0.43f)
            val ey = (y + sin(ang) * step - h * 0.5f) / (h * 0.36f)
            val nx = x + cos(ang) * step; val ny = y + sin(ang) * step
            val inHeader = clearCenter && nx < w * 0.55f && ny > h * 0.06f && ny < h * 0.17f // logo + name
            if (inHeader) ang = if (ny < h * 0.115f) -1.4f else 1.4f
            if (clearCenter && !inHeader && ex * ex + ey * ey < 1f) {
                val out = kotlin.math.atan2(y - h * 0.5f, x - w / 2)
                ang = out + (rnd.nextFloat() - 0.5f) * 1.2f
            }
            x += cos(ang) * step; y += sin(ang) * step
            if (x < -20 * dp || x > w + 20 * dp || y < -20 * dp || y > h + 20 * dp) break
        }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size - 1) {
                val m = Offset((pts[i].x + pts[i + 1].x) / 2, (pts[i].y + pts[i + 1].y) / 2)
                quadraticTo(pts[i].x, pts[i].y, m.x, m.y)
            }
        }
        val leaves = ArrayList<Path>()
        for (i in 3 until pts.size - 1 step 4) {
            val p0 = pts[i]; val p1 = pts[i + 1]
            val a = kotlin.math.atan2(p1.y - p0.y, p1.x - p0.x) + (if ((i / 4) % 2 == 0) 0.95f else -0.95f)
            val len = (7f + rnd.nextFloat() * 5f) * dp
            val tip = Offset(p0.x + cos(a) * len, p0.y + sin(a) * len)
            val nx = -sin(a) * len * 0.35f; val ny = cos(a) * len * 0.35f
            val mid = Offset((p0.x + tip.x) / 2, (p0.y + tip.y) / 2)
            leaves += Path().apply {
                moveTo(p0.x, p0.y)
                quadraticTo(mid.x + nx, mid.y + ny, tip.x, tip.y)
                quadraticTo(mid.x - nx, mid.y - ny, p0.x, p0.y)
                close()
            }
        }
        // a curling tendril part-way along
        if (pts.size > 20) {
            val c = pts[pts.size / 2]
            leaves += Path().apply {
                moveTo(c.x, c.y)
                var r = 9f * dp; var t = 0f
                while (r > 1.5f * dp) { t += 0.5f; r *= 0.9f; lineTo(c.x + cos(t) * r - 9f * dp, c.y + sin(t) * r) }
            }
        }
        val buds = listOf(pts.last())
        val pm = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, false) }
        Vine(path, leaves, buds, pm.length, rnd.nextFloat(), 0.6f + rnd.nextFloat() * 0.6f, pm)
    }
}

@Composable
private fun VineMesh(modifier: Modifier, heart: State<Float>?, clearCenter: Boolean) {
    val clock = heart ?: heartClock()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val dens = androidx.compose.ui.platform.LocalDensity.current.density
        val vines = remember(w, h, clearCenter) { buildVines(w, h, dens, clearCenter) }
        val stem = Color(0xFF3A0A0D)
        // 1) the vines: drawn once, never redrawn
        Canvas(Modifier.fillMaxSize()) {
            vines.forEach { v ->
                drawPath(v.path, stem, style = Stroke(1.3f * density, cap = StrokeCap.Round))
                v.leaves.forEach { l -> drawPath(l, Color(0xFF2B0709)); drawPath(l, stem, style = Stroke(0.9f * density)) }
                v.buds.forEach { b ->
                    for (k in 0 until 5) {
                        val a = k * 72f * (Math.PI / 180).toFloat()
                        drawCircle(Color(0xFF5C0C12), radius = 2.3f * density, center = Offset(b.x + cos(a) * 3f * density, b.y + sin(a) * 3f * density))
                    }
                    drawCircle(Color(0xFF8E0B11), radius = 1.6f * density, center = b)
                }
            }
        }
        // 2) the vines breathe: a soft glow drawn once, its opacity rising and falling over ~8 s
        val breath = rememberInfiniteTransition(label = "veins").animateFloat(
            0f, 1f, infiniteRepeatable(tween(4000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath",
        )
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 0.15f + 0.45f * breath.value }) {
            vines.forEach { v ->
                drawPath(v.path, Crimson.copy(alpha = 0.10f), style = Stroke(8f * density, cap = StrokeCap.Round))
                drawPath(v.path, Crimson.copy(alpha = 0.22f), style = Stroke(3.5f * density, cap = StrokeCap.Round))
                v.buds.forEach { b -> drawCircle(Crimson.copy(alpha = 0.6f), radius = 2.2f * density, center = b) }
            }
        }
        // 3) slow embers: one soft drop per vine gliding at a steady pace (no bursts)
        val seg = remember { Path() }
        Canvas(Modifier.fillMaxSize()) {
            val travel = clock.value / (BEAT_MS * CLOCK_BEATS) // 0..1 over the whole clock (~52 s)
            vines.forEach { v ->
                val head = (((travel * 2f * v.speed) + v.phase) % 1f) * (v.length * 1.2f)
                val tail = v.length * 0.22f
                seg.reset()
                val a0 = (head - tail).coerceIn(0f, v.length); val a1 = head.coerceIn(0f, v.length)
                if (a1 > a0 && v.measure.getSegment(a0, a1, seg, true)) {
                    drawPath(seg, Crimson.copy(alpha = 0.14f), style = Stroke(6f * density, cap = StrokeCap.Round))
                    drawPath(seg, Crimson.copy(alpha = 0.45f), style = Stroke(1.5f * density, cap = StrokeCap.Round))
                    seg.reset()
                    val b0 = (head - tail * 0.25f).coerceIn(0f, v.length)
                    if (a1 > b0 && v.measure.getSegment(b0, a1, seg, true)) {
                        drawPath(seg, Color(0xFFFF4A40).copy(alpha = 0.85f), style = Stroke(2f * density, cap = StrokeCap.Round))
                    }
                }
            }
        }
    }
}

/** No glow any more: pages stay pitch black. */
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEktaGlow(glowY: Float = 0.3f, intensity: Float = 1.4f) {}

/** Old name: every page now gets the new background. */
@Composable
fun IllustratedBackground(@Suppress("UNUSED_PARAMETER") forest: Boolean = true, content: @Composable () -> Unit) =
    EktaBackground(content = content)

/**
 * Ekta's mark, "the rescue grip": three women's arms in a triangle, each hand locked around
 * the next woman's wrist (the strongest hold, used to pull someone to safety). Unity, trust,
 * power. A white bangle on each wrist.
 * Drawn in a 100 x 100 box; the app icon (drawable/ic_sos.xml) uses the same shapes.
 * (Kept the old function name so every caller picks up the new mark.)
 */
@Composable
fun Trishul(size: Dp, color: Color = Crimson, modifier: Modifier = Modifier) = EktaMark(size, color, modifier)

@Composable
fun EktaMark(size: Dp, color: Color = Crimson, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 100f
        fun o(x: Float, y: Float) = Offset(x * k, y * k)
        // One arm, drawn three times turned 120°: forearm from outside to the next corner,
        // where its hand locks around the next woman's wrist.
        val v0 = o(50f, 24f); val v1 = o(72.5f, 63f)
        val dx = 0.5f; val dy = 0.866f            // direction of this arm
        val px = -dy; val py = dx                  // across the arm
        fun along(p: Offset, t: Float, s: Float = 0f) = Offset(p.x + (dx * t + px * s) * k, p.y + (dy * t + py * s) * k)
        val rot = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
        for (i in 0 until 3) rotate(i * 120f, pivot = o(50f, 50f)) {
            drawLine(color, along(v0, -20f), along(v1, -7f), strokeWidth = 13f * k, cap = StrokeCap.Round) // forearm
        }
        for (i in 0 until 3) rotate(i * 120f, pivot = o(50f, 50f)) {
            drawLine(Bone, along(v1, -11f, -7.5f), along(v1, -11f, 7.5f), strokeWidth = 2.6f * k, cap = StrokeCap.Round) // bangle
            // hand: a palm wrapped round the next wrist, three finger gaps, a thumb
            val hc = along(v1, 1.5f)
            rotate(rot, pivot = hc) {
                val palm = androidx.compose.ui.geometry.RoundRect(
                    hc.x - 8.5f * k, hc.y - 9f * k, hc.x + 8.5f * k, hc.y + 9f * k, androidx.compose.ui.geometry.CornerRadius(5.5f * k),
                )
                val pp = Path().apply { addRoundRect(palm) }
                drawPath(pp, Bone)
                drawPath(pp, Night, style = Stroke(1.3f * k))
                for (g in listOf(-3f, 3f)) drawLine(Night, Offset(hc.x + 2f * k, hc.y + g * k), Offset(hc.x + 8f * k, hc.y + g * k), strokeWidth = 1.3f * k)
                drawCircle(Bone, radius = 3.6f * k, center = Offset(hc.x - 3.5f * k, hc.y - 9.5f * k))   // thumb
                drawCircle(Night, radius = 3.6f * k, center = Offset(hc.x - 3.5f * k, hc.y - 9.5f * k), style = Stroke(1.3f * k))
            }
        }
    }
}

/** EKTA wordmark: the circle mark, EKTA, and एकता in red beneath. */
@Composable
fun BrandMark(size: Float = 30f) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        EktaMark((size * 2.2f).dp)
        Spacer(Modifier.width((size * 0.3f).dp))
        Column {
            Text(
                "EKTA", fontFamily = Display, fontSize = size.sp, color = Bone, letterSpacing = (size * 0.14f).sp,
                style = androidx.compose.ui.text.TextStyle(
                    lineHeight = (size * 1.0f).sp,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                ),
            )
            Text(
                "एकता", fontFamily = Display, fontSize = (size * 0.58f).sp, color = Crimson, letterSpacing = (size * 0.04f).sp,
                style = androidx.compose.ui.text.TextStyle(
                    lineHeight = (size * 0.75f).sp,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                ),
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/** Old name for the big logo on some pages. */
@Composable
fun WobblyLogo(text: String) {
    Text(text, fontFamily = Display, fontSize = 52.sp, color = Bone, letterSpacing = 6.sp)
}

/** A filled, rounded button (red by default). Keeps the old name and signature. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChunkyButton(
    width: Dp,
    height: Dp,
    enabled: Boolean = true,
    color: Color = Crimson,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    Column(
        modifier
            .scale(s)
            .size(width, height)
            .background(if (enabled) color else color.copy(alpha = 0.5f), Round)
            .border(1.dp, if (color == Crimson) Crimson else Line, Round)
            .combinedClickable(
                interactionSource = interaction, indication = null,
                enabled = enabled, role = Role.Button,
                onLongClick = onLongClick, onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

/** One status line (✓ done, … working, ✕ failed) used on the SOS and setup pages. */
@Composable
fun StatusRow(step: Step, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        val (bg, mark, fg) = when (step) {
            Step.DONE -> Triple(Bone, "✓", Night)
            Step.FAILED -> Triple(Crimson, "!", Bone)
            Step.WORKING -> Triple(Coal2, "…", Ash)
            Step.DEMO -> Triple(Coal2, "D", Ash)
            Step.IDLE -> Triple(Coal2, "", Ash)
        }
        Box(
            Modifier.size(26.dp).background(bg, CircleShape).border(1.dp, Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(mark, fontWeight = FontWeight.Black, fontSize = 13.sp, color = fg) }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Bone)
            Text(detail, fontSize = 12.sp, color = Ash)
        }
    }
}

/** Slow breathing for halos and "live" dots. */
@Composable
fun breathing(periodMs: Int = 1600): Float = breathingState(periodMs).value

/** Same, as State: read it inside graphicsLayer / Canvas so the screen isn't rebuilt every frame. */
@Composable
fun breathingState(periodMs: Int = 1600): State<Float> {
    val t = rememberInfiniteTransition(label = "breath")
    return t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
}

/**
 * Durga's prabhamandala (halo) around the SOS button, in fine red line work:
 * a lotus of 24 petals, a ring of rudraksha beads, and ten spear-points for her
 * ten arms (dashabhuja) that turn very slowly. [discRadius] is the SOS disc inside.
 */
@Composable
fun Prabhamandala(sizeDp: Dp, discRadius: Dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "halo")
    val spin = t.animateFloat(0f, 360f, infiniteRepeatable(tween(60_000, easing = androidx.compose.animation.core.LinearEasing)), label = "spin")
    val flick = t.animateFloat(0.7f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "flick")
    Box(modifier.size(sizeDp)) {
        // still parts: disc border, lotus, beads, outer ring (drawn once)
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val d = discRadius.toPx()
            val px = 1.dp.toPx()
            fun pt(r: Float, deg: Float): Offset {
                val a = Math.toRadians(deg.toDouble() - 90)
                return Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
            }
            drawCircle(Crimson, radius = d + 6 * px, center = c, style = Stroke(1.5f * px))
            val p1 = d + 14 * px; val p2 = d + 36 * px; val n = 24; val half = 360f / n / 2
            for (i in 0 until n) {
                val a = i * 360f / n
                val path = Path().apply {
                    val s0 = pt(p1, a - half); moveTo(s0.x, s0.y)
                    val c1 = pt(p1 + (p2 - p1) * 0.75f, a - half * 0.9f); val tip = pt(p2, a)
                    quadraticTo(c1.x, c1.y, tip.x, tip.y)
                    val c2 = pt(p1 + (p2 - p1) * 0.75f, a + half * 0.9f); val e = pt(p1, a + half)
                    quadraticTo(c2.x, c2.y, e.x, e.y)
                }
                drawPath(path, Crimson.copy(alpha = 0.85f), style = Stroke(1.2f * px, join = StrokeJoin.Round))
                drawLine(Crimson.copy(alpha = 0.35f), pt(p1 + 4 * px, a), pt(p2 - 6 * px, a), strokeWidth = px)
            }
            drawCircle(Crimson.copy(alpha = 0.6f), radius = p1, center = c, style = Stroke(px))
            val beadR = p2 + 8 * px
            for (i in 0 until 72) drawCircle(Crimson.copy(alpha = 0.55f), radius = 1.3f * px, center = pt(beadR, i * 5f))
            drawCircle(Crimson.copy(alpha = 0.45f), radius = beadR + 8 * px, center = c, style = Stroke(px))
        }
        // flames: drawn once, turn with the spears, flicker through opacity only
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = spin.value; alpha = flick.value }) {
            val c = center; val px = 1.dp.toPx()
            val ring = discRadius.toPx() + 52 * px
            fun pt(r: Float, deg: Float): Offset {
                val a = Math.toRadians(deg.toDouble() - 90)
                return Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
            }
            for (i in 0 until 20) {
                val a = 18f + (i / 2) * 36f + (if (i % 2 == 0) -7f else 7f)
                val l = pt(ring + px, a - 2.4f); val r = pt(ring + px, a + 2.4f)
                val tip = pt(ring + (14 + (i % 3) * 2) * px, a + (if (i % 2 == 0) -2.5f else 2.5f))
                val cl = pt(ring + 9 * px, a - 3.6f); val cr = pt(ring + 7 * px, a + 3.2f)
                val flame = Path().apply { moveTo(l.x, l.y); quadraticTo(cl.x, cl.y, tip.x, tip.y); quadraticTo(cr.x, cr.y, r.x, r.y); close() }
                drawPath(flame, Crimson.copy(alpha = 0.8f))
            }
        }
        // ten spear-points: drawn once, the layer turns
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = spin.value }) {
            val c = center; val px = 1.dp.toPx()
            val ring = discRadius.toPx() + 52 * px
            fun pt(r: Float, deg: Float): Offset {
                val a = Math.toRadians(deg.toDouble() - 90)
                return Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
            }
            for (i in 0 until 10) {
                val a = i * 36f
                val base = pt(ring, a); val tip = pt(ring + 22 * px, a)
                val l = pt(ring + 7 * px, a - 3.2f); val r = pt(ring + 7 * px, a + 3.2f)
                val spear = Path().apply { moveTo(base.x, base.y); lineTo(l.x, l.y); lineTo(tip.x, tip.y); lineTo(r.x, r.y); close() }
                drawPath(spear, Crimson)
                drawCircle(Crimson, radius = 2f * px, center = pt(ring + 28 * px, a))
            }
        }
    }
}

/**
 * The laal paar: a woven red zari border like the edge of a Durga Puja saree:
 * a solid edge band, interlocking temple spires (with finials and cut-outs) and
 * inverted spires, a fine line and a fringe of beads, with a slow zari shimmer.
 */
@Composable
fun SareeBorder(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "zari")
    val shine = t.animateFloat(-0.2f, 1.2f, infiniteRepeatable(tween(5200, easing = androidx.compose.animation.core.LinearEasing)), label = "shine")
    Box(modifier.height(30.dp).background(Night)) {
        Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }) {
            // the woven pattern (drawn once)
            Canvas(Modifier.fillMaxSize()) {
                val px = 1.dp.toPx(); val w = size.width
                drawRect(Crimson, topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(w, 3.2f * px))
                drawLine(Crimson.copy(alpha = 0.75f), Offset(0f, 5.2f * px), Offset(w, 5.2f * px), strokeWidth = px)
                val step = 14f * px
                var x = step / 2
                while (x < w + step) {
                    val spire = Path().apply { moveTo(x - 6f * px, 22f * px); lineTo(x, 9f * px); lineTo(x + 6f * px, 22f * px); close() }
                    drawPath(spire, Crimson)
                    val cut = Path().apply { moveTo(x - 2.6f * px, 21f * px); lineTo(x, 15f * px); lineTo(x + 2.6f * px, 21f * px); close() }
                    drawPath(cut, Color.Black, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
                    drawCircle(Crimson, radius = 1.1f * px, center = Offset(x, 19.6f * px))
                    drawCircle(Crimson, radius = 1.3f * px, center = Offset(x, 7.3f * px))
                    val ix = x + step / 2
                    val inv = Path().apply { moveTo(ix - 3.4f * px, 7f * px); lineTo(ix + 3.4f * px, 7f * px); lineTo(ix, 15.5f * px); close() }
                    drawPath(inv, Crimson.copy(alpha = 0.6f))
                    x += step
                }
                drawLine(Crimson, Offset(0f, 24f * px), Offset(w, 24f * px), strokeWidth = 1.2f * px)
                var bx = 3.5f * px
                while (bx < w) { drawCircle(Crimson.copy(alpha = 0.85f), radius = 1.15f * px, center = Offset(bx, 27.6f * px)); bx += 7f * px }
            }
            // zari shimmer: a soft band of light that only lights the threads
            Canvas(Modifier.fillMaxSize()) {
                val bandW = 90.dp.toPx()
                val x = shine.value * size.width - bandW / 2
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color(0x99FF6A5A), Color.Transparent), startX = x, endX = x + bandW),
                    blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop,
                )
            }
        }
    }
}

/**
 * Trinetra: the fierce, kohl-winged eyes of Ma Durga (as painted on Durga Puja idols)
 * under sharp angry brows, with Kali's blazing third eye between them. She is watching.
 */
@Composable
fun Trinetra(width: Dp, modifier: Modifier = Modifier) {
    val glow = breathingState(1500)
    Box(modifier.size(width, width * 0.3f)) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val px = 1.dp.toPx()
        fun eye(mirror: Boolean) {
            fun X(f: Float) = if (mirror) w - f * w else f * w
            fun P(fx: Float, fy: Float) = Offset(X(fx), fy * h)
            val inner = P(0.40f, 0.66f)
            val outer = P(0.165f, 0.60f)
            val shape = Path().apply {
                moveTo(inner.x, inner.y)
                val u = P(0.27f, 0.30f); quadraticTo(u.x, u.y, outer.x, outer.y)
                val l = P(0.29f, 0.88f); quadraticTo(l.x, l.y, inner.x, inner.y)
                close()
            }
            // iris + pupil, kept inside the eye
            clipPath(shape) {
                val ic = P(0.29f, 0.60f)
                drawCircle(Crimson, radius = h * 0.17f, center = ic)
                drawCircle(Night, radius = h * 0.075f, center = ic)
                drawCircle(Bone, radius = h * 0.028f, center = Offset(ic.x + h * 0.05f, ic.y - h * 0.05f))
            }
            drawPath(shape, Bone, style = Stroke(1.6f * px, join = StrokeJoin.Round))
            // kohl: heavy upper lid + winged flick (kajal)
            val lid = Path().apply {
                moveTo(inner.x, inner.y)
                val u = P(0.27f, 0.27f); quadraticTo(u.x, u.y, outer.x, outer.y)
                val tip = P(0.075f, 0.40f); lineTo(tip.x, tip.y)
                val back = P(0.20f, 0.52f); lineTo(back.x, back.y)
                val u2 = P(0.27f, 0.36f); quadraticTo(u2.x, u2.y, inner.x, inner.y)
                close()
            }
            drawPath(lid, Bone)
            // angry brow: low at the centre, rising sharply outward
            val brow = Path().apply {
                val a = P(0.43f, 0.30f); moveTo(a.x, a.y)
                val c = P(0.28f, 0.02f); val b = P(0.10f, 0.16f); quadraticTo(c.x, c.y, b.x, b.y)
                val c2 = P(0.28f, 0.10f); quadraticTo(c2.x, c2.y, a.x, a.y)
                close()
            }
            drawPath(brow, Bone)
        }
        eye(false); eye(true)
    }
    // third eye: a vertical flame-eye whose glow breathes (opacity only)
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 0.7f + 0.3f * glow.value }) {
        val w = size.width
        val h = size.height
        val px = 1.dp.toPx()
        val cx = w / 2
        val third = Path().apply {
            moveTo(cx, h * 0.02f)
            quadraticTo(cx + w * 0.035f, h * 0.24f, cx, h * 0.52f)
            quadraticTo(cx - w * 0.035f, h * 0.24f, cx, h * 0.02f)
            close()
        }
        drawPath(third, Crimson)
        drawLine(Night, Offset(cx, h * 0.14f), Offset(cx, h * 0.40f), strokeWidth = 1.6f * px, cap = StrokeCap.Round)
        drawCircle(Crimson, radius = 2.6f * px, center = Offset(cx, h * 0.64f)) // bindi
    }
}
}

// ---- Shared pieces for pop-ups and forms (one look everywhere) ----

/** A rounded dark card for pop-ups. */
@Composable
fun EktaDialogCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Coal, RoundedCornerShape(22.dp)).border(1.dp, Line, RoundedCornerShape(22.dp))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Text-field colours: quiet border, red when typing. */
@Composable
fun ektaFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Crimson, unfocusedBorderColor = Line,
    focusedLabelColor = Bone, unfocusedLabelColor = Ash,
    focusedContainerColor = Coal2, unfocusedContainerColor = Coal2,
    focusedTextColor = Bone, unfocusedTextColor = Bone, cursorColor = Crimson,
)

val FieldShape = RoundedCornerShape(14.dp)

/** Pop-up buttons: a quiet text button and a red pill. */
@Composable
fun DialogButtons(cancel: String, onCancel: () -> Unit, ok: String, okEnabled: Boolean, onOk: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text(
            cancel, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Ash,
            modifier = Modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onCancel).padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            ok, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (okEnabled) Bone else Bone.copy(alpha = 0.4f),
            modifier = Modifier.clip(RoundedCornerShape(24.dp))
                .background(if (okEnabled) Crimson else Coal2, RoundedCornerShape(24.dp))
                .clickable(enabled = okEnabled, onClick = onOk).padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

/** "← Back" link at the top of sub-pages. */
@Composable
fun BackLink(onClick: () -> Unit) {
    Text(
        stringResource(R.string.back_link), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Bone,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 2.dp),
    )
}
