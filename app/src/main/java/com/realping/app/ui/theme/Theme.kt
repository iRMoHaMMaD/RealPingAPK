// app/src/main/java/com/realping/app/ui/theme/Theme.kt
package com.realping.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2C8F4A),
    secondary = Color(0xFFFFA000),
    tertiary = Color(0xFFFF6D00)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2C8F4A),
    secondary = Color(0xFFFFA000),
    tertiary = Color(0xFFFF6D00)
)

@Composable
fun RealPingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
