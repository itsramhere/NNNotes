package com.ramst.notein.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF28594C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEAE0),
    onPrimaryContainer = Color(0xFF193F34),
    secondary = Color(0xFF607166),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4ECE3),
    onSecondaryContainer = Color(0xFF244638),
    tertiary = Color(0xFF9E6248),
    tertiaryContainer = Color(0xFFF5E3D7),
    onTertiaryContainer = Color(0xFF643C2A),
    background = Color(0xFFF8F7F3),
    onBackground = Color(0xFF252D28),
    surface = Color(0xFFFCFBF8),
    onSurface = Color(0xFF252D28),
    surfaceVariant = Color(0xFFECEEE6),
    onSurfaceVariant = Color(0xFF697168),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F3EC),
    surfaceContainer = Color(0xFFECEEE6),
    surfaceContainerHigh = Color(0xFFE5E8DF),
    surfaceContainerHighest = Color(0xFFDFE3D9),
    outline = Color(0xFF8A9388),
    outlineVariant = Color(0xFFE1E4DA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA4D1B9),
    onPrimary = Color(0xFF143C2F),
    primaryContainer = Color(0xFF2E5142),
    onPrimaryContainer = Color(0xFFD6EDDF),
    secondary = Color(0xFFB4C9B8),
    secondaryContainer = Color(0xFF30483B),
    onSecondaryContainer = Color(0xFFD9EADC),
    tertiary = Color(0xFFE4B295),
    tertiaryContainer = Color(0xFF573E30),
    onTertiaryContainer = Color(0xFFFFDCC6),
    background = Color(0xFF171D19),
    onBackground = Color(0xFFE8ECE4),
    surface = Color(0xFF1C231E),
    onSurface = Color(0xFFE8ECE4),
    surfaceVariant = Color(0xFF343F35),
    onSurfaceVariant = Color(0xFFADB9AD),
    surfaceContainerLowest = Color(0xFF121813),
    surfaceContainerLow = Color(0xFF202921),
    surfaceContainer = Color(0xFF263027),
    surfaceContainerHigh = Color(0xFF303B31),
    surfaceContainerHighest = Color(0xFF3B473C),
    outline = Color(0xFF829282),
    outlineVariant = Color(0xFF364137),
)

private val NoteinTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 43.sp, letterSpacing = (-.8).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-.5).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 25.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = .3.sp),
)

@Composable
fun NoteinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = NoteinTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
