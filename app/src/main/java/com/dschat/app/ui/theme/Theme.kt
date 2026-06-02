package com.dschat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Monochrome scheme. primary = ink (near-black/near-white); all "container" roles map to the
// neutral surfaceVariant so banners / reasoning / chips render gray rather than colored.
private val LightColors = lightColorScheme(
    primary = InkLight,
    onPrimary = Color.White,
    primaryContainer = LightSurfaceVariant,
    onPrimaryContainer = LightOnSurface,
    secondary = InkLight,
    onSecondary = Color.White,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightOnSurface,
    tertiary = InkLight,
    onTertiary = Color.White,
    tertiaryContainer = LightSurfaceVariant,
    onTertiaryContainer = LightOnSurface,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF6E4E2),
    onErrorContainer = Color(0xFF410E0B)
)

private val DarkColors = darkColorScheme(
    primary = InkDark,
    onPrimary = Color(0xFF1A1A1C),
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = DarkOnSurface,
    secondary = InkDark,
    onSecondary = Color(0xFF1A1A1C),
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = DarkOnSurface,
    tertiary = InkDark,
    onTertiary = Color(0xFF1A1A1C),
    tertiaryContainer = DarkSurfaceVariant,
    onTertiaryContainer = DarkOnSurface,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF3A2422),
    onErrorContainer = Color(0xFFF2B8B5)
)

@Composable
fun DeepSeekTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
