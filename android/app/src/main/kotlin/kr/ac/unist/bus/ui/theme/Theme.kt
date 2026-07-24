package kr.ac.unist.bus.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF0057B8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E7FF),
    onPrimaryContainer = Color(0xFF001A40),
    secondary = Color(0xFF465D7B),
    secondaryContainer = Color(0xFFD9E7FF),
    tertiary = Color(0xFF006B5F),
    tertiaryContainer = Color(0xFF9DF2E0),
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFF6F7FB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF004493),
    onPrimaryContainer = Color(0xFFD8E7FF),
    secondary = Color(0xFFB9C8E4),
    secondaryContainer = Color(0xFF30445F),
    tertiary = Color(0xFF80D5C4),
    tertiaryContainer = Color(0xFF005047),
)

@Composable
fun UNISTBusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
