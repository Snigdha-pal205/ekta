package com.ekta.sos

import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Contacts
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
        EktaDialogCard {
            Text(stringResource(R.string.contact_title), fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Bone)
            Text(stringResource(R.string.contact_intro), fontSize = 14.sp, color = Ash, lineHeight = 20.sp)
            Field(stringResource(R.string.contact_my_name), myName) { myName = it }
            Row(
                Modifier.fillMaxWidth().clip(FieldShape).background(Coal2, FieldShape).border(1.dp, Crimson, FieldShape)
                    .clickable(onClick = onPickFromContacts).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.Contacts, contentDescription = null, tint = Crimson,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.contact_pick_soft), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Bone)
            }
            Text(stringResource(R.string.contact_or_type), fontSize = 13.sp, color = Ash)
            Field(stringResource(R.string.contact_name), name) { name = it }
            Field(stringResource(R.string.contact_phone), number, KeyboardType.Phone) { number = it }
            DialogButtons(
                stringResource(R.string.cancel_soft), onDismiss,
                stringResource(R.string.save_soft), valid,
            ) { onSave(myName.trim(), name.trim(), number.trim()) }
        }
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        shape = FieldShape,
        colors = ektaFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}
