package com.streamza.loop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Matches the website's palette (site/styles.css :root) exactly — same brand across web + app.
val StreamzaRed = Color(0xFFFF3B30)
val StreamzaRedPress = Color(0xFFD70015)
val StreamzaBg = Color(0xFF000000)
val StreamzaSurface = Color(0xFF1C1C1E)
val StreamzaSurface2 = Color(0xFF2C2C2E)
val StreamzaMuted = Color(0xFFAEAEB2)
val StreamzaMuted2 = Color(0xFF8E8E93)

private val LoopColorScheme = darkColorScheme(
    primary = StreamzaRed,
    onPrimary = Color.White,
    secondary = StreamzaRedPress,
    background = StreamzaBg,
    onBackground = Color.White,
    surface = StreamzaSurface,
    onSurface = Color.White,
    surfaceVariant = StreamzaSurface2,
    onSurfaceVariant = StreamzaMuted,
    outline = StreamzaMuted2,
    error = StreamzaRed,
)

@Composable
fun StreamzaLoopTheme(content: @Composable () -> Unit) {
    // Streamza's identity is deliberately always-dark (pure black + system red), same on web and app —
    // not following system light/dark, matching the website's own choice not to offer a light theme.
    MaterialTheme(
        colorScheme = LoopColorScheme,
        content = content,
    )
}
