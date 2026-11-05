package com.ekta.sos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Pick or type the emergency contact who gets the live-location SMS. */
@Composable
fun ContactDialog(
    initial: EmergencyContact?,
    initialMyName: String,
    picked: EmergencyContact?,
    onPickFromContacts: () -> Unit,
    onSave: (myName: String, name: String, number: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var myName by remember { mutableStateOf(initialMyName) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var number by remember { mutableStateOf(initial?.number ?: "") }
    LaunchedEffect(picked) {
        picked?.let { name = it.name; number = it.number }
    }
    val valid = number.count { it.isDigit() } >= 3

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(SkyBottom)
                .border(2.dp, Ink)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("EMERGENCY CONTACT", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 17.sp, color = Ink, letterSpacing = 1.sp)
            Text(
                "When you press SOS, this person gets an SMS with your live location.",
                fontFamily = Mono, fontSize = 12.sp, color = Ink,
            )
            Field("Your name (so they know it's you)", myName) { myName = it }
            Text(
                "PICK FROM CONTACTS",
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink,
                modifier = Modifier
                    .background(Lime)
                    .border(2.dp, Ink)
                    .clickable(onClick = onPickFromContacts)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Text("or type it in:", fontFamily = Mono, fontSize = 12.sp, color = Ink)
            Field("Name", name) { name = it }
            Field("Phone number", number, KeyboardType.Phone) { number = it }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CANCEL", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp),
                )
                Text(
                    "SAVE", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 14.sp,
                    color = if (valid) Ink else Ink.copy(alpha = 0.35f),
                    modifier = Modifier
                        .background(if (valid) Lime else Color.Transparent)
                        .border(2.dp, if (valid) Ink else Ink.copy(alpha = 0.35f))
                        .clickable(enabled = valid) { onSave(myName.trim(), name.trim(), number.trim()) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontFamily = Mono) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Ink, unfocusedBorderColor = Ink,
            focusedLabelColor = Ink, unfocusedLabelColor = Ink,
            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
            focusedTextColor = Ink, unfocusedTextColor = Ink, cursorColor = Ink,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
