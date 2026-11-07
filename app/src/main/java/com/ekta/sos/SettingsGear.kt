package com.ekta.sos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Settings button (gear) in the top-left corner of every Ekta page, so Settings can be
 * opened at any time — even while an SOS is running, without cancelling it.
 */
@Composable
fun SettingsGear(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(12.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .background(Color.White, CircleShape)
                .border(2.dp, Ink, CircleShape)
                .clickable(onClick = onClick)
                .semantics { contentDescription = "Open settings" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = Ink, modifier = Modifier.size(26.dp))
        }
    }
}
