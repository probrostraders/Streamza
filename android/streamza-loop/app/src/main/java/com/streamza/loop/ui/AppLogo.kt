package com.streamza.loop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.streamza.loop.R

/** The app's official mark (cloud + assistant + infinity loop) — same source image as the launcher
 *  icon, used inline next to a screen's title the way most apps show their logo in a header. */
@Composable
fun AppLogo(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Image(
        painter = painterResource(R.drawable.ic_streamza_logo),
        contentDescription = null,
        modifier = modifier.size(size).clip(CircleShape),
    )
}

/** Logo + title, used at the top of every tab so the brand mark shows up consistently everywhere,
 *  not just Home. */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppLogo()
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}
