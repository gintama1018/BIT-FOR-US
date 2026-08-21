package com.meshwhisper.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// Calm WhatsApp-inspired dark palette
val DarkBackground = Color(0xFF0B141B)
val DarkSurface = Color(0xFF111B21)
val DarkSurfaceVariant = Color(0xFF202C33)
val DarkCardBorder = Color(0xFF222D34)

// Chat Bubbles
val OutgoingBubble = Color(0xFF005C4B)
val OutgoingBubbleBorder = Color(0xFF006B57)
val IncomingBubble = Color(0xFF202C33)
val IncomingBubbleBorder = Color(0xFF2A3942)

// WhatsApp Accent Accents
val WhatsAppGreen = Color(0xFF25D366)
val EmeraldAccent = Color(0xFF00A884)
val EmeraldDim = Color(0xFF008069)
val CyanAccent = Color(0xFF53BDEB)
val CyanDim = Color(0xFF3B9AC6)
val AmberAccent = Color(0xFFFFD279)
val RedAccent = Color(0xFFF15C6D)

// Typography
val TextPrimary = Color(0xFFE9EDEF)
val TextSecondary = Color(0xFF8696A0)
val TextMuted = Color(0xFF667781)

val MeshColorScheme = darkColorScheme(
    primary = EmeraldAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF00381C),
    onPrimaryContainer = EmeraldAccent,
    secondary = CyanAccent,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1F2C34),
    onSecondaryContainer = CyanAccent,
    tertiary = AmberAccent,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = RedAccent,
    onError = Color.White
)

