package com.radiosuite.pro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RadioColorScheme = lightColorScheme(
    primary = RadioTeal,
    onPrimary = RadioWhite,
    secondary = RadioAmber,
    onSecondary = RadioInk,
    background = RadioMist,
    onBackground = RadioInk,
    surface = RadioWhite,
    onSurface = RadioInk,
)

private val RadioDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB9EF62),
    onPrimary = Color(0xFF1B260B),
    secondary = Color(0xFFFFB46B),
    onSecondary = Color(0xFF2A1707),
    background = Color(0xFF0C1110),
    onBackground = Color(0xFFE7ECE7),
    surface = Color(0xFF151B19),
    onSurface = Color(0xFFE7ECE7),
    surfaceVariant = Color(0xFF252D2A),
    onSurfaceVariant = Color(0xFFC0CAC2),
    outlineVariant = Color(0xFF35403B),
    error = Color(0xFFFF8A78),
)

@Composable
fun RadioSuiteProTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) RadioDarkColorScheme else RadioColorScheme,
        typography = RadioTypography,
        content = content,
    )
}