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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
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
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            BackLink(onBack)
            Text(stringResource(R.string.set_language) + if (current != "en") " · Language" else "", fontWeight = FontWeight.Bold, fontSize = 28.sp, color = Bone)
            Text(stringResource(R.string.language_hint), fontSize = 15.sp, color = Ash)
            Spacer(Modifier.height(6.dp))
            Lang.options.forEach { o ->
                val selected = o.tag == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Round)
                        .background(if (selected) Blood else Coal, Round)
                        .border(1.dp, if (selected) Crimson else Line, Round)
                        .clickable { onPick(o.tag) }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(o.native, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = Bone)
                        if (o.native != o.english) Text(o.english, fontSize = 13.sp, color = if (selected) Bone.copy(alpha = 0.8f) else Ash)
                    }
                    if (selected) Text("✓", fontWeight = FontWeight.Black, fontSize = 22.sp, color = Bone)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
