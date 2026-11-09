package com.ekta.sos

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Person
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Full-screen number pad to cancel a running SOS. */
@Composable
fun PinPadScreen(
    message: String?,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
    title: String = stringResource(R.string.pin_title_cancel),
    backLabel: String = stringResource(R.string.back_link),
) {
    var pin by remember { mutableStateOf("") }

    IllustratedBackground(forest = false) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(70.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Bone)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.pin_enter), fontSize = 15.sp, color = Ash)
            Spacer(Modifier.height(26.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(PinStore.PIN_LENGTH) { i ->
                    Box(
                        Modifier
                            .size(22.dp)
                            .background(if (i < pin.length) Bone else Color.Transparent, CircleShape)
                            .border(1.dp, Line, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                message ?: " ",
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Bone,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(if (message != null) Blood else Color.Transparent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(20.dp))

            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { k ->
                            Key(k) {
                                when (k) {
                                    "⌫" -> pin = pin.dropLast(1)
                                    "OK" -> if (pin.length == PinStore.PIN_LENGTH) {
                                        onSubmit(pin)
                                        pin = ""
                                    }
                                    else -> if (pin.length < PinStore.PIN_LENGTH) pin += k
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Text(
                backLabel, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Ash,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack).padding(12.dp),
            )
        }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .background(if (label == "OK") Crimson else Coal2, CircleShape)
            .border(1.dp, if (label == "OK") Ember.copy(alpha = 0.4f) else Line, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "key $label" },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 26.sp, color = Bone)
    }
}

/**
 * Shown after a correct PIN. Deliberately identical for the normal and the duress
 * PIN, so nobody watching her screen can tell the difference.
 */
@Composable
fun AlertCancelledScreen() {
    IllustratedBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(110.dp)
                    .background(Crimson, CircleShape)
                    .border(1.dp, Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("✓", fontSize = 64.sp, fontWeight = FontWeight.Black, color = Bone) }
            Spacer(Modifier.height(26.dp))
            Text(
                stringResource(R.string.alert_cancelled), fontWeight = FontWeight.Bold, fontSize = 26.sp,
                color = Bone,
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(240.dp))
        }
    }
}

/** Choose the two PINs: one to really cancel, one to cancel under duress. */
@Composable
fun PinSetupDialog(onSave: (normal: String, duress: String) -> Unit, onDismiss: () -> Unit) {
    var normal by remember { mutableStateOf("") }
    var normal2 by remember { mutableStateOf("") }
    var duress by remember { mutableStateOf("") }
    val len = PinStore.PIN_LENGTH
    val error = when {
        normal.length == len && normal2.length == len && normal != normal2 -> stringResource(R.string.pin_mismatch)
        normal.length == len && duress.length == len && normal == duress -> stringResource(R.string.pin_duress_same)
        else -> null
    }
    val valid = normal.length == len && normal == normal2 && duress.length == len && normal != duress

    Dialog(onDismissRequest = onDismiss) {
        EktaDialogCard {
            Text(stringResource(R.string.pins_title_soft), fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Bone)
            Text(stringResource(R.string.pins_intro), fontSize = 14.sp, color = Ash)
            PinField(stringResource(R.string.pin_cancel_label, len), normal) { normal = it }
            PinField(stringResource(R.string.pin_cancel_again), normal2) { normal2 = it }
            Text(
                stringResource(R.string.pins_duress_explain), fontSize = 13.sp, color = Bone, lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().background(Blood.copy(alpha = 0.35f), FieldShape).padding(12.dp),
            )
            PinField(stringResource(R.string.pin_duress_label, len), duress) { duress = it }
            if (error != null) Text(error, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Crimson)
            DialogButtons(
                stringResource(R.string.later_soft), onDismiss,
                stringResource(R.string.save_pins_soft), valid,
            ) { onSave(normal, duress) }
        }
    }
}

@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(PinStore.PIN_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        shape = FieldShape,
        colors = ektaFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Settings. "I'm safe now" looks and behaves the same whether or not an alert is running. */
@Composable
fun SettingsScreen(
    onContact: () -> Unit,
    onSafeNow: () -> Unit,
    onLanguage: () -> Unit,
    onBack: (() -> Unit)?,
    onFixPermissions: (() -> Unit)? = null,
    contactName: String? = null,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lang = Lang.currentOption(ctx)
    val missing = remember { AlertPermissions.missing(ctx).size }
    val version = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
    }
    IllustratedBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            if (onBack != null) Text(
                stringResource(R.string.back_arrow), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Bone,
                modifier = Modifier.clickable(onClick = onBack).padding(vertical = 8.dp),
            )
            Text(stringResource(R.string.settings_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Bone)
            Spacer(Modifier.height(4.dp))

            SettingsSection(stringResource(R.string.sec_safety))
            SettingsRow(
                androidx.compose.material.icons.Icons.Filled.Person, stringResource(R.string.contact_title),
                contactName ?: stringResource(R.string.settings_contact_detail), onClick = onContact,
            )
            SettingsRow(
                androidx.compose.material.icons.Icons.Filled.VerifiedUser, stringResource(R.string.set_safe),
                stringResource(R.string.safe_now_detail), onClick = onSafeNow,
            )

            SettingsSection(stringResource(R.string.sec_app))
            // Always says "Language" in English too, so someone who picked the wrong language can find it.
            SettingsRow(
                androidx.compose.material.icons.Icons.Filled.Language,
                stringResource(R.string.set_language) + if (lang.tag != "en") " · Language" else "",
                lang.native, onClick = onLanguage,
            )
            if (onFixPermissions != null) SettingsRow(
                androidx.compose.material.icons.Icons.Filled.NotificationsActive, stringResource(R.string.perm_row),
                if (missing == 0) stringResource(R.string.perm_all_ok) else stringResource(R.string.perm_some, missing),
                warn = missing > 0, onClick = onFixPermissions,
            )

            SettingsSection(stringResource(R.string.sec_about))
            Row(
                Modifier.fillMaxWidth().background(Coal, Round).border(1.dp, Line, Round).padding(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.Lock, contentDescription = null, tint = Crimson,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.about_privacy), fontSize = 14.sp, color = Bone, lineHeight = 20.sp)
                    if (version.isNotBlank()) Text(
                        stringResource(R.string.about_version, version), fontSize = 13.sp, color = Ash,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ash, letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 14.dp, start = 4.dp, bottom = 2.dp),
    )
}

/** A settings row: icon, title, one line under it, arrow. Big enough to tap easily. */
@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String,
    warn: Boolean = false, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).background(Coal, Round).border(1.dp, if (warn) Crimson else Line, Round)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(if (warn) Crimson else Coal2, CircleShape), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = if (warn) Bone else Crimson, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Bone)
            Text(detail, fontSize = 13.sp, color = if (warn) Bone else Ash)
        }
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Ash,
        )
    }
}

@Composable
private fun SettingsItem(title: String, detail: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Coal, Round)
            .border(1.dp, Line, Round)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Bone)
        Text(detail, fontSize = 12.sp, color = Ash)
    }
}
