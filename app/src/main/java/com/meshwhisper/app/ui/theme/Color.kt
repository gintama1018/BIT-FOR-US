package com.meshwhisper.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

val DarkBackground = Color(0xFF090D16)
val DarkSurface = Color(0xFF111827)
val DarkSurfaceVariant = Color(0xFF1F2937)
val DarkCardBorder = Color(0xFF374151)

val EmeraldAccent = Color(0xFF00E676)
val EmeraldDim = Color(0xFF00B359)
val CyanAccent = Color(0xFF00B0FF)
val CyanDim = Color(0xFF0081CB)
val AmberAccent = Color(0xFFFFB300)
val RedAccent = Color(0xFFFF5252)

val TextPrimary = Color(0xFFF3F4F6)
val TextSecondary = Color(0xFF9CA3AF)
val TextMuted = Color(0xFF6B7280)

val MeshColorScheme = darkColorScheme(
    primary = EmeraldAccent,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF00381C),
    onPrimaryContainer = EmeraldAccent,
    secondary = CyanAccent,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF00324E),
    onSecondaryContainer = CyanAccent,
    tertiary = AmberAccent,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = RedAccent,
    onError = Color.Black
)
