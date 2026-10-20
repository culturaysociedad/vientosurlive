package com.radiosuite.pro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val RadioColorScheme = lightColorScheme(
    primary = RadioBrick,
    onPrimary = RadioWhite,
    primaryContainer = Color(0xFFEBD5CC),
    onPrimaryContainer = Color(0xFF402119),
    secondary = RadioAmber,
    onSecondary = RadioInk,
    secondaryContainer = Color(0xFFF0E1C2),
    onSecondaryContainer = Color(0xFF35230C),
    tertiary = RadioSignalGreen,
    onTertiary = Color(0xFF162414),
    tertiaryContainer = Color(0xFFDCE9D5),
    onTertiaryContainer = Color(0xFF1C3219),
    background = RadioMist,
    onBackground = RadioInk,
    surface = RadioWhite,
    onSurface = RadioInk,
    surfaceVariant = Color(0xFFE9E1D5),
    onSurfaceVariant = Color(0xFF645B52),
    outline = Color(0xFF8E8173),
    outlineVariant = Color(0xFFD8CEC0),
    error = Color(0xFFAE3F32),
    surfaceTint = RadioBrick,
)

private val RadioDarkColorScheme = darkColorScheme(
    primary = Color(0xFFE3B66A),
    onPrimary = Color(0xFF33230E),
    primaryContainer = Color(0xFF594131),
    onPrimaryContainer = Color(0xFFF4DFC0),
    secondary = Color(0xFFD18468),
    onSecondary = Color(0xFF32190F),
    secondaryContainer = Color(0xFF55382B),
    onSecondaryContainer = Color(0xFFF2DBCC),
    tertiary = Color(0xFF9ABD8D),
    onTertiary = Color(0xFF172615),
    tertiaryContainer = Color(0xFF34472F),
    onTertiaryContainer = Color(0xFFDCEBD4),
    background = Color(0xFF171615),
    onBackground = Color(0xFFF1EAE0),
    surface = Color(0xFF211F1D),
    onSurface = Color(0xFFF1EAE0),
    surfaceVariant = Color(0xFF302C28),
    onSurfaceVariant = Color(0xFFD0C3B4),
    outline = Color(0xFF8D7D6B),
    outlineVariant = Color(0xFF49423A),
    error = Color(0xFFFF8A78),
    surfaceTint = Color(0xFFE3B66A),
)

private val RadioShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun RadioSuiteProTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) RadioDarkColorScheme else RadioColorScheme,
        typography = RadioTypography,
        shapes = RadioShapes,
        content = content,
    )
}