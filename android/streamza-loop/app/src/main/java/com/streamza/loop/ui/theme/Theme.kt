package com.streamza.loop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// Same brand red across web + app (see site/styles.css :root's --accent).
val StreamzaRed = Color(0xFFFF3B30)
val StreamzaRedPress = Color(0xFFD70015)

// Dark palette — matches the website's always-dark look.
val StreamzaBg = Color(0xFF000000)
val StreamzaSurface = Color(0xFF1C1C1E)
val StreamzaSurface2 = Color(0xFF2C2C2E)
val StreamzaMuted = Color(0xFFAEAEB2)
val StreamzaMuted2 = Color(0xFF8E8E93)

// Light palette — YouTube's own light mode: white surfaces, near-black text, light-gray cards.
val StreamzaLightBg = Color(0xFFFFFFFF)
val StreamzaLightSurface = Color(0xFFFFFFFF)
val StreamzaLightSurface2 = Color(0xFFF2F2F2)
val StreamzaLightMuted = Color(0xFF606060)
val StreamzaLightMuted2 = Color(0xFF909090)
val StreamzaLightOutline = Color(0xFFD9D9D9)

private val DarkColorScheme = darkColorScheme(
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

private val LightColorScheme = lightColorScheme(
    primary = StreamzaRed,
    onPrimary = Color.White,
    secondary = StreamzaRedPress,
    background = StreamzaLightBg,
    onBackground = Color.Black,
    surface = StreamzaLightSurface,
    onSurface = Color.Black,
    surfaceVariant = StreamzaLightSurface2,
    onSurfaceVariant = StreamzaLightMuted,
    outline = StreamzaLightOutline,
    error = StreamzaRed,
)

/** "system" follows the device setting (like YouTube's default), "light"/"dark" pin it — see the
 *  Appearance section in SettingsScreen and AppPreferences.themeMode for where this is chosen. */
@Composable
fun StreamzaLoopTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val useDark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (useDark) DarkColorScheme else LightColorScheme) {
        // MaterialTheme only carries color *tokens* — nothing actually paints colorScheme.background
        // without this Surface. Without it, the window shows Android's default background instead
        // (caught by actually running the app on an emulator: the screen came out mid-gray, not black).
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
