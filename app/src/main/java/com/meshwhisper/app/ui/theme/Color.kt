package com.meshwhisper.app.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// =========================================================================
// SAHARA — WARM MINIMALISM DESIGN TOKENS
// =========================================================================

// Primary & Accents
val BurntSienna = Color(0xFFC2652A)       // Primary CTA / Focus / High-intent
val BurntSiennaDim = Color(0xFFA8541F)    // Darker Sienna for pressed/dim states
val BurntSiennaContainer = Color(0xFFF3DFD3) // Soft Sienna container tint
val DustyRose = Color(0xFF8C3C3C)         // Tertiary Accent for emphasis
val WarmAmber = Color(0xFFD48A37)         // Warning / Pending state
val WarmGreen = Color(0xFF2E7D32)         // Direct BLE online / Delivered status
val WarmRed = Color(0xFFB71C1C)           // Danger / Panic / Blocked

// Surfaces & Backgrounds (Warm Linen & Warm White)
val WarmLinen = Color(0xFFFAF5EE)         // Canvas Background — never cold white
val WarmSurface = Color(0xFFFFFFFF)       // Primary Card / Surface container
val WarmSurfaceContainer = Color(0xFFF4ECE1) // Secondary container / header tint
val WarmCardBorder = Color(0x99D8D0C8)    // 60% Opacity warm border (#d8d0c8)
val WarmDivider = Color(0x66D8D0C8)       // Subtle divider border

// Typography (Warm Espresso & Taupe)
val TextPrimary = Color(0xFF2A231D)       // Deep warm espresso
val TextSecondary = Color(0xFF6E6359)     // Warm muted taupe
val TextMuted = Color(0xFF9E9286)         // Light warm taupe

// Chat Bubbles (Warm Minimalist)
val OutgoingBubble = Color(0xFFF3DFD3)    // Soft warm sienna tint
val OutgoingBubbleBorder = Color(0xFFE5C4B1)
val IncomingBubble = Color(0xFFFFFFFF)    // Crisp warm white
val IncomingBubbleBorder = Color(0xFFE3DAD0)

// Backward Compatibility Aliases
val DarkBackground = WarmLinen
val DarkSurface = WarmSurface
val DarkSurfaceVariant = WarmSurfaceContainer
val DarkCardBorder = WarmCardBorder
val EmeraldAccent = BurntSienna
val EmeraldDim = BurntSiennaDim
val CyanAccent = DustyRose
val CyanDim = DustyRose
val AmberAccent = WarmAmber
val RedAccent = WarmRed
val WhatsAppGreen = WarmGreen

val SaharaColorScheme = lightColorScheme(
    primary = BurntSienna,
    onPrimary = Color.White,
    primaryContainer = BurntSiennaContainer,
    onPrimaryContainer = BurntSienna,
    secondary = DustyRose,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E2DE),
    onSecondaryContainer = DustyRose,
    tertiary = WarmAmber,
    background = WarmLinen,
    onBackground = TextPrimary,
    surface = WarmSurface,
    onSurface = TextPrimary,
    surfaceVariant = WarmSurfaceContainer,
    onSurfaceVariant = TextSecondary,
    outline = WarmCardBorder,
    error = WarmRed,
    onError = Color.White
)

val MeshColorScheme = SaharaColorScheme
