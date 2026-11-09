package com.ekta.sos

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/** First screen: log in or sign up (email or Google). Nothing else opens until she is logged in. */
@Composable
fun LoginScreen(onLanguage: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var signUp by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPw by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var info by rememberSaveable { mutableStateOf<String?>(null) }

    fun attempt(block: suspend () -> Boolean) {
        if (busy) return
        focus.clearFocus()
        error = null; info = null; busy = true
        scope.launch {
            try {
                if (block()) onDone()
            } catch (e: Account.Failure) {
                error = ctx.getString(e.msg)
            } finally {
                busy = false
            }
        }
    }

    fun submit() {
        val e = email.trim()
        when {
            e.isEmpty() || password.isEmpty() || (signUp && name.isBlank()) -> error = ctx.getString(R.string.login_err_fields)
            !android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches() -> error = ctx.getString(R.string.login_err_email)
            signUp && password.length < 6 -> error = ctx.getString(R.string.login_err_short)
            else -> attempt {
                if (signUp) Account.signUp(ctx, name, e, password) else Account.logIn(ctx, e, password)
                true
            }
        }
    }

    val clock = heartClock()
    EktaBackground(heart = clock) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Everything fits on one screen (no scrolling). While typing, the logo steps aside for the keyboard.
            val typing = WindowInsets.isImeVisible
            if (typing) Spacer(Modifier.weight(1f))
            else UnityHero(Modifier.weight(1f).fillMaxWidth(), clock, onLanguage)

            Column(
                Modifier.fillMaxWidth().background(Coal, Round).border(1.dp, Line, Round).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Log in | Sign up
                Row(Modifier.fillMaxWidth().background(Night, Round).border(1.dp, Line, Round).padding(4.dp)) {
                    listOf(false to R.string.login_tab_in, true to R.string.login_tab_up).forEach { (up, label) ->
                        val on = signUp == up
                        val bg by animateColorAsState(if (on) Crimson else Night, label = "tab")
                        Box(
                            Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp)).background(bg)
                                .clickable { signUp = up; error = null; info = null },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(label), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) Bone else Ash)
                        }
                    }
                }

                AnimatedVisibility(signUp) {
                    Field(name, { name = it }, stringResource(R.string.login_name), Icons.Outlined.Person, KeyboardType.Text)
                }
                Field(email, { email = it.trim() }, stringResource(R.string.login_email), Icons.Outlined.Email, KeyboardType.Email)
                Field(
                    password, { password = it }, stringResource(R.string.login_password), Icons.Outlined.Lock, KeyboardType.Password,
                    hidden = !showPw, last = true, onGo = ::submit,
                    trailing = {
                        Text(
                            stringResource(if (showPw) R.string.login_hide else R.string.login_show),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ash,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showPw = !showPw }.padding(8.dp),
                        )
                    },
                )

                if (!signUp) Text(
                    stringResource(R.string.login_forgot), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Crimson,
                    modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(8.dp)).clickable {
                        val e = email.trim()
                        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
                            error = ctx.getString(R.string.login_need_email); info = null
                        } else attempt {
                            Account.resetPassword(ctx, e)
                            info = ctx.getString(R.string.login_reset_sent, e)
                            false
                        }
                    }.padding(4.dp),
                )

                error?.let { Message(it, Crimson) }
                info?.let { Message(it, Bone) }

                ChunkyButton(width = 400.dp, height = 52.dp, enabled = !busy, onClick = ::submit, modifier = Modifier.fillMaxWidth()) {
                    if (busy) CircularProgressIndicator(color = Bone, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                    else Text(
                        stringResource(if (signUp) R.string.login_button_up else R.string.login_tab_in),
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Bone, letterSpacing = 0.5.sp,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(Line))
                    Text(stringResource(R.string.login_or), fontSize = 13.sp, color = Ash, modifier = Modifier.padding(horizontal = 12.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(Line))
                }

                ChunkyButton(
                    width = 400.dp, height = 52.dp, enabled = !busy, color = Coal2, modifier = Modifier.fillMaxWidth(),
                    onClick = { attempt { Account.google(ctx as Activity) } },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(26.dp).background(Bone, CircleShape), contentAlignment = Alignment.Center) {
                            Text("G", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Night)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.login_google), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Bone)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, label: String, icon: ImageVector, type: KeyboardType,
    hidden: Boolean = false, last: Boolean = false, onGo: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = Ash) },
        trailingIcon = trailing,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = if (last) ImeAction.Go else ImeAction.Next),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        shape = FieldShape, colors = ektaFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Message(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, fontSize = 14.sp, color = color, lineHeight = 19.sp, modifier = Modifier.fillMaxWidth())
}

/**
 * The hero: she is the heart in the middle, and around her a ring of other women (lights)
 * slowly turning. With every heartbeat a drop of red light runs out along each thread and
 * lights up a sister: one woman's call reaching all the others. That is Ekta.
 * Everything animates in the draw phase only, so it stays smooth.
 */
@Composable
private fun UnityHero(modifier: Modifier, clock: androidx.compose.runtime.State<Float>, onLanguage: () -> Unit) {
    val ctx = LocalContext.current
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, tween(1800, easing = FastOutSlowInEasing)) }

    BoxWithConstraints(modifier) {
        val textH = 74.dp
        val art = (maxHeight - textH - 8.dp).coerceIn(0.dp, 250.dp)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (art >= 110.dp) Box(Modifier.size(art), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) { drawUnity(clock.value, intro.value) }
                // The mark beats with the heart (layer only: no recomposition).
                val seal = art * 0.36f
                Box(
                    Modifier.size(seal).graphicsLayer {
                        val b = heartBeat(clock.value)
                        val s = (0.6f + 0.4f * intro.value) * (1f + 0.045f * b)
                        scaleX = s; scaleY = s; alpha = intro.value.coerceAtMost(1f)
                    }.drawBehind {
                        val r = size.minDimension / 2
                        drawCircle(Night, radius = r)
                        drawCircle(Crimson, radius = r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                    },
                    contentAlignment = Alignment.Center,
                ) { EktaMark(seal * 0.62f) }
            }
            Text(
                "EKTA", fontFamily = Display, fontSize = 38.sp, color = Bone,
                modifier = Modifier.graphicsLayer { alpha = ((intro.value - 0.25f) / 0.6f).coerceIn(0f, 1f) },
                letterSpacing = 10.sp,
            )
            Text(
                stringResource(R.string.login_tagline), fontSize = 14.sp, color = Ash, textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = ((intro.value - 0.5f) / 0.5f).coerceIn(0f, 1f) },
            )
        }
        // Language chip, top right, so she can read everything in her own language first.
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 10.dp)
                .clip(RoundedCornerShape(20.dp)).background(Coal, RoundedCornerShape(20.dp))
                .border(1.dp, Line, RoundedCornerShape(20.dp)).clickable(onClick = onLanguage)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Language, contentDescription = null, tint = Crimson, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(Lang.currentOption(ctx).native, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Bone)
        }
    }
}

private const val SISTERS_OUT = 9
private const val SISTERS_IN = 6
private const val CLOCK_PERIOD = BEAT_MS * 40 // heartClock() wraps after 40 beats

private fun DrawScope.drawUnity(clock: Float, intro: Float) {
    val c = center
    val R = size.minDimension / 2
    val turn = (clock / CLOCK_PERIOD) * 2f * PI.toFloat()
    val beat = heartBeat(clock)
    val sinceBeat = (clock % BEAT_MS) / BEAT_MS // 0..1 through each beat

    // A black pool so the red never fights the vines behind it.
    drawCircle(
        Brush.radialGradient(0f to Night, 0.7f to Night.copy(alpha = 0.95f), 1f to Night.copy(alpha = 0f), center = c, radius = R * 1.35f),
        radius = R * 1.35f, center = c,
    )
    // Her heart: a soft red glow that swells with each beat.
    drawCircle(
        Brush.radialGradient(
            0f to Crimson.copy(alpha = 0.55f * intro * (0.55f + 0.45f * beat)), 1f to Crimson.copy(alpha = 0f),
            center = c, radius = R * 0.62f,
        ),
        radius = R * 0.62f, center = c,
    )

    fun ring(n: Int, radius: Float, dir: Float, offset: Float): List<Offset> = List(n) { i ->
        val a = offset + dir * turn + i * 2f * PI.toFloat() / n
        Offset(c.x + radius * cos(a), c.y + radius * sin(a))
    }
    val outer = ring(SISTERS_OUT, R * 0.86f, 1f, 0f)
    val inner = ring(SISTERS_IN, R * 0.6f, -1f, 0.4f)
    val reach = intro.coerceIn(0f, 1f)

    // The web: each sister holds the hands of the ones beside her, and the inner ring holds the outer.
    val thread = Crimson.copy(alpha = 0.22f * reach)
    for (pts in listOf(outer, inner)) for (i in pts.indices) {
        val a = pts[i]; val b = pts[(i + 1) % pts.size]
        drawLine(thread, a, a + (b - a) * reach, strokeWidth = 1.dp.toPx())
    }
    inner.forEach { p ->
        val near = outer.minByOrNull { (it - p).getDistanceSquared() }!!
        drawLine(thread, p, p + (near - p) * reach, strokeWidth = 1.dp.toPx())
    }

    // Spokes from her to every sister, and a drop of light running out after each beat.
    val seal = R * 0.36f
    val all = inner + outer
    all.forEachIndexed { idx, p ->
        val dir = (p - c) / (p - c).getDistance()
        val start = c + dir * seal
        drawLine(Crimson.copy(alpha = 0.12f * reach), start, start + (p - start) * reach, strokeWidth = 1.dp.toPx())
        val isOuter = idx >= inner.size
        // The drop leaves just after the "lub", outer ring a moment later.
        val t = ((sinceBeat - 0.06f - if (isOuter) 0.1f else 0f) / 0.55f)
        if (t in 0f..1f && intro > 0.95f) {
            val e = 1f - (1f - t) * (1f - t)
            val pos = start + (p - start) * e
            val fade = sin(PI.toFloat() * t)
            drawCircle(Crimson.copy(alpha = 0.35f * fade), radius = 5.dp.toPx(), center = pos)
            drawCircle(Bone.copy(alpha = 0.9f * fade), radius = 1.6.dp.toPx(), center = pos)
        }
    }

    // The sisters: they light up as the drop reaches them, then settle back to a calm glow.
    all.forEachIndexed { idx, p ->
        val isOuter = idx >= inner.size
        val arrive = 0.06f + (if (isOuter) 0.1f else 0f) + 0.55f
        val lit = ((1f - abs(sinceBeat - arrive) / 0.18f)).coerceIn(0f, 1f)
        val appear = ((intro - 0.3f - idx * 0.03f) / 0.3f).coerceIn(0f, 1f)
        val r = (if (isOuter) 3.2f else 2.6f).dp.toPx()
        drawCircle(Crimson.copy(alpha = (0.18f + 0.45f * lit) * appear), radius = r * (2.6f + 1.4f * lit), center = p)
        drawCircle(androidx.compose.ui.graphics.lerp(Crimson, Bone, 0.35f + 0.65f * lit).copy(alpha = appear), radius = r, center = p)
    }
}
