package com.ekta.sos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings → Language: each language written in its own script, so anyone can find theirs. */
@Composable
fun LanguageScreen(current: String, onPick: (String) -> Unit, onBack: () -> Unit) {
    IllustratedBackground(forest = false) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(70.dp))
            Text(stringResource(R.string.language_title), fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 24.sp, color = Ink, letterSpacing = 2.sp)
            Text(stringResource(R.string.language_hint), fontSize = 13.sp, color = Ink)
            Spacer(Modifier.height(6.dp))
            Lang.options.forEach { o ->
                val selected = o.tag == current
                Row(
                    Modifier
                        .width(300.dp)
                        .background(if (selected) Lime else Color.White)
                        .border(2.dp, Ink)
                        .clickable { onPick(o.tag) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(o.native, fontWeight = FontWeight.Black, fontSize = 19.sp, color = Ink)
                        if (o.native != o.english) Text(o.english, fontSize = 12.sp, color = Ink.copy(alpha = 0.7f))
                    }
                    if (selected) Text("✓", fontWeight = FontWeight.Black, fontSize = 22.sp, color = Ink)
                }
            }
            Text(
                stringResource(R.string.back),
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink,
                modifier = Modifier.clickable(onClick = onBack).padding(14.dp),
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}
