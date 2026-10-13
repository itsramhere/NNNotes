package com.ramst.notein.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3E5F90),
    secondary = Color(0xFF566579),
    tertiary = Color(0xFF75566D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C8FF),
    secondary = Color(0xFFBEC8D9),
    tertiary = Color(0xFFE4BAD4),
)

@Composable
fun NoteinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
