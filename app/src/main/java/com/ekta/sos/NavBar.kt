package com.ekta.sos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.WhereToVote
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.WhereToVote
import androidx.compose.material.icons.outlined.ContactPhone
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The four places in Ekta, in the bottom bar. */
enum class EktaTab(val icon: ImageVector, val iconOff: ImageVector, val label: Int) {
    SOS(Icons.Filled.MonitorHeart, Icons.Outlined.MonitorHeart, R.string.tab_sos),            // her heart, beating
    SAFE(Icons.Filled.WhereToVote, Icons.Outlined.WhereToVote, R.string.tab_safe),             // a place, checked
    CONTACT(Icons.Filled.ContactPhone, Icons.Outlined.ContactPhone, R.string.tab_contact),     // her emergency contact
    SETTINGS(Icons.Filled.Tune, Icons.Outlined.Tune, R.string.tab_settings),
}

/** Bottom navigation: black bar, the selected tab in red with a small red mark above it. */
@Composable
fun EktaNavBar(selected: EktaTab, onSelect: (EktaTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Night)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            EktaTab.entries.forEach { t ->
                val on = t == selected
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable(
                        interactionSource = MutableInteractionSource(), indication = null, role = Role.Tab,
                    ) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // selected: filled red icon on a soft red pill; others: outlined grey
                    Box(
                        Modifier.width(60.dp).height(32.dp)
                            .background(if (on) Crimson.copy(alpha = 0.18f) else Night, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (t == EktaTab.SOS) HeartPulse(if (on) Crimson else Ash, filled = on)
                        else Icon(if (on) t.icon else t.iconOff, contentDescription = null, tint = if (on) Crimson else Ash, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(t.label), fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Bone else Ash, maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** SOS tab icon: a heart with a heartbeat line across it (outlined, or filled when selected). */
@Composable
private fun HeartPulse(color: androidx.compose.ui.graphics.Color, filled: Boolean) {
    androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
        val k = size.minDimension / 24f
        val heart = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
            "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09" +
                "C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z",
        ).toPath()
        scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            if (filled) drawPath(heart, color)
            else drawPath(heart, color, style = androidx.compose.ui.graphics.drawscope.Stroke(1.8f))
            val pulse = androidx.compose.ui.graphics.Path().apply {
                moveTo(3.5f, 11.5f); lineTo(8f, 11.5f); lineTo(10f, 8f); lineTo(13f, 15f); lineTo(15f, 10.5f); lineTo(16.5f, 11.5f); lineTo(20.5f, 11.5f)
            }
            drawPath(
                pulse, if (filled) Night else color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    1.7f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
        }
    }
}

/** Contact tab: who gets her SMS, what they'll receive, and what happens on SOS. */
@Composable
fun ContactTab(contact: EmergencyContact?, onChange: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val myName = SosSession.myName(ctx)
    EktaBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.contact_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Bone)
            Text(stringResource(R.string.contact_intro), fontSize = 15.sp, color = Ash, lineHeight = 21.sp)

            // ---- the contact ----
            Column(
                Modifier.fillMaxWidth().background(Coal, Round).border(1.dp, if (contact == null) Crimson else Line, Round).padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).background(Crimson, CircleShape), contentAlignment = Alignment.Center) {
                        Text(
                            (contact?.name?.trim()?.firstOrNull()?.uppercase() ?: "+"),
                            fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Bone,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(contact?.name ?: stringResource(R.string.add_contact), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Bone)
                        if (contact != null) Text(contact.number, fontSize = 15.sp, color = Ash)
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (contact != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(Icons.Filled.Call, stringResource(R.string.contact_call), Modifier.weight(1f)) {
                            try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + contact.number))) } catch (_: Exception) {}
                        }
                        PillButton(Icons.AutoMirrored.Filled.Message, stringResource(R.string.contact_message), Modifier.weight(1f)) {
                            val text = ctx.getString(R.string.contact_tell)
                            try {
                                ctx.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:" + contact.number))
                                        .putExtra("sms_body", text),
                                )
                            } catch (_: Exception) {}
                        }
                        PillButton(Icons.Filled.Edit, stringResource(R.string.change), Modifier.weight(1f), onClick = onChange)
                    }
                } else {
                    ChunkyButton(width = 360.dp, height = 52.dp, onClick = onChange, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.add), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Bone)
                    }
                }
            }

            // ---- her name in the SMS ----
            Row(
                Modifier.fillMaxWidth().background(Coal, Round).border(1.dp, Line, Round).clickable(onClick = onChange)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Badge, contentDescription = null, tint = Crimson, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.contact_your_name), fontSize = 13.sp, color = Ash)
                    Text(myName.ifBlank { "—" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Bone)
                }
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.change), tint = Ash, modifier = Modifier.size(20.dp))
            }

            // ---- what they will receive ----
            Text(stringResource(R.string.contact_receive), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Bone, modifier = Modifier.padding(top = 6.dp))
            val preview = if (myName.isBlank()) stringResource(R.string.sms_sos_anon, "https://maps.google.com/?q=…", stringResource(R.string.sms_acc, 8))
            else stringResource(R.string.sms_sos_named, myName, "https://maps.google.com/?q=…", stringResource(R.string.sms_acc, 8))
            Row {
                Spacer(Modifier.width(28.dp))
                Text(
                    preview, fontSize = 14.sp, color = Bone, lineHeight = 20.sp,
                    modifier = Modifier.weight(1f)
                        .background(Coal2, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                        .border(1.dp, Line, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                        .padding(14.dp),
                )
            }

            // ---- what happens on SOS ----
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp).background(Coal, Round).border(1.dp, Line, Round).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(stringResource(R.string.contact_steps), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Bone)
                listOf(R.string.contact_step1, R.string.contact_step2, R.string.contact_step3, R.string.contact_step4).forEachIndexed { i, id ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(26.dp).background(Crimson, CircleShape), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Bone)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(id, stringResource(R.string.radius_label)), fontSize = 14.sp, color = Bone, lineHeight = 20.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(64.dp).background(Coal2, RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Crimson, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Bone)
    }
}
