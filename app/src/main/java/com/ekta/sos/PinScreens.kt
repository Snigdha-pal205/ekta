package com.ekta.sos

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
    title: String = "CANCEL ALERT",
    backLabel: String = "BACK TO SOS",
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
            Text(
                title, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 24.sp,
                color = Ink, letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text("Enter your PIN", fontFamily = Mono, fontSize = 14.sp, color = Ink)
            Spacer(Modifier.height(26.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(PinStore.PIN_LENGTH) { i ->
                    Box(
                        Modifier
                            .size(22.dp)
                            .background(if (i < pin.length) Ink else Color.Transparent, CircleShape)
                            .border(2.dp, Ink, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                message ?: " ",
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(if (message != null) Lime else Color.Transparent)
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
                backLabel,
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink,
                modifier = Modifier.clickable(onClick = onBack).padding(10.dp),
            )
        }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(78.dp)
            .background(if (label == "OK") Lime else Color.White)
            .border(2.dp, Ink)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "key $label" },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 26.sp, color = Ink)
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
                    .background(Lime, CircleShape)
                    .border(3.dp, Ink, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("✓", fontSize = 64.sp, fontWeight = FontWeight.Black, color = Ink) }
            Spacer(Modifier.height(26.dp))
            Text(
                "Alert cancelled", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 26.sp,
                color = Ink,
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
        normal.length == len && normal2.length == len && normal != normal2 -> "The two cancel PINs don't match"
        normal.length == len && duress.length == len && normal == duress -> "The duress PIN must be different"
        else -> null
    }
    val valid = normal.length == len && normal == normal2 && duress.length == len && normal != duress

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(SkyBottom)
                .border(2.dp, Ink)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("CANCEL PINS", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 17.sp, color = Ink, letterSpacing = 1.sp)
            Text(
                "An SOS can only be stopped with a PIN.",
                fontFamily = Mono, fontSize = 12.sp, color = Ink,
            )
            PinField("Cancel PIN ($len digits)", normal) { normal = it }
            PinField("Cancel PIN again", normal2) { normal2 = it }
            Text(
                "DURESS PIN: if someone forces you to cancel, enter this one instead. " +
                    "Your screen will say \"Alert cancelled\", but help keeps coming and nobody will call your phone.",
                fontFamily = Mono, fontSize = 11.sp, color = Ink,
                modifier = Modifier.background(Color.White).padding(8.dp),
            )
            PinField("Duress PIN ($len digits)", duress) { duress = it }
            if (error != null) {
                Text(error, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Pink)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "LATER", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp),
                )
                Text(
                    "SAVE PINS", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 14.sp,
                    color = if (valid) Ink else Ink.copy(alpha = 0.35f),
                    modifier = Modifier
                        .background(if (valid) Lime else Color.Transparent)
                        .border(2.dp, if (valid) Ink else Ink.copy(alpha = 0.35f))
                        .clickable(enabled = valid) { onSave(normal, duress) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(PinStore.PIN_LENGTH)) },
        label = { Text(label, fontFamily = Mono) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Ink, unfocusedBorderColor = Ink,
            focusedLabelColor = Ink, unfocusedLabelColor = Ink,
            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
            focusedTextColor = Ink, unfocusedTextColor = Ink, cursorColor = Ink,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Settings. "I'm safe now" looks and behaves the same whether or not an alert is running. */
@Composable
fun SettingsScreen(onContact: () -> Unit, onSafeNow: () -> Unit, onBack: () -> Unit) {
    IllustratedBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(80.dp))
            Text("SETTINGS", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 24.sp, color = Ink, letterSpacing = 2.sp)
            Spacer(Modifier.height(30.dp))
            SettingsItem("EMERGENCY CONTACT", "change who gets your live location", onContact)
            Spacer(Modifier.height(14.dp))
            SettingsItem("I'M SAFE NOW", "end an alert with your PIN", onSafeNow)
            Spacer(Modifier.height(30.dp))
            Text(
                "BACK",
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink,
                modifier = Modifier.clickable(onClick = onBack).padding(10.dp),
            )
        }
    }
}

@Composable
private fun SettingsItem(title: String, detail: String, onClick: () -> Unit) {
    Column(
        Modifier
            .width(300.dp)
            .background(Color.White)
            .border(2.dp, Ink)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(title, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 15.sp, color = Ink, letterSpacing = 1.sp)
        Text(detail, fontFamily = Mono, fontSize = 11.sp, color = Ink)
    }
}
