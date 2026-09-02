package com.meshwhisper.app.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// =========================================================================
// SAHARA — EXACT WARM MINIMALISM DESIGN SYSTEM COLOR TOKENS
// =========================================================================

// Primary & Variations (Burnt Sienna)
val SaharaPrimary = Color(0xFF964407)
val SaharaOnPrimary = Color(0xFFFFFFFF)
val SaharaPrimaryContainer = Color(0xFFB65C21)
val SaharaOnPrimaryContainer = Color(0xFFFFFBFF)
val SaharaPrimaryFixed = Color(0xFFFFDBCA)
val SaharaPrimaryFixedDim = Color(0xFFFFB68E)
val SaharaOnPrimaryFixed = Color(0xFF331200)

// Secondary & Variations (Dusty Rose / Terracotta)
val SaharaSecondary = Color(0xFF974544)
val SaharaOnSecondary = Color(0xFFFFFFFF)
val SaharaSecondaryContainer = Color(0xFFFE9794)
val SaharaOnSecondaryContainer = Color(0xFF782C2D)
val SaharaSecondaryFixed = Color(0xFFFFDAD8)

// Tertiary (Teal / Slate Blue)
val SaharaTertiary = Color(0xFF006480)
val SaharaOnTertiary = Color(0xFFFFFFFF)
val SaharaTertiaryContainer = Color(0xFF007EA1)
val SaharaOnTertiaryContainer = Color(0xFFFBFDFF)

// Emergency / Error (Crimson Alert)
val SaharaError = Color(0xFFBA1A1A)
val SaharaOnError = Color(0xFFFFFFFF)
val SaharaErrorContainer = Color(0xFFFFDAD6)
val SaharaOnErrorContainer = Color(0xFF93000A)

// Surfaces & Backgrounds (Warm Linen Architecture)
val SaharaBackground = Color(0xFFFFF8EF)
val SaharaOnBackground = Color(0xFF1D1B16)
val SaharaSurface = Color(0xFFFFF8EF)
val SaharaSurfaceDim = Color(0xFFDFD9D0)
val SaharaSurfaceBright = Color(0xFFFFF8EF)
val SaharaSurfaceContainerLowest = Color(0xFFFFFFFF)
val SaharaSurfaceContainerLow = Color(0xFFF9F3EA)
val SaharaSurfaceContainer = Color(0xFFF3EDE4)
val SaharaSurfaceContainerHigh = Color(0xFFEDE7DE)
val SaharaSurfaceContainerHighest = Color(0xFFE8E2D9)

// Typography & Outlines
val SaharaOnSurface = Color(0xFF1D1B16)
val SaharaOnSurfaceVariant = Color(0xFF554339)
val SaharaOutline = Color(0xFF887368)
val SaharaOutlineVariant = Color(0xFFDBC1B5)

// Status & Online Accents
val SaharaOnline = Color(0xFF10B981)
val SaharaWarning = Color(0xFFD48A37)

// Backward Compatibility Aliases
val BurntSienna = SaharaPrimary
val BurntSiennaDim = Color(0xFFA8541F)
val BurntSiennaContainer = SaharaPrimaryContainer
val DustyRose = SaharaSecondary
val WarmAmber = SaharaWarning
val WarmGreen = SaharaOnline
val WarmRed = SaharaError
val WarmLinen = SaharaBackground
val WarmSurface = SaharaSurfaceContainerLowest
val WarmSurfaceContainer = SaharaSurfaceContainerLow
val WarmCardBorder = SaharaOutlineVariant
val WarmDivider = Color(0x66DBC1B5)
val TextPrimary = SaharaOnSurface
val TextSecondary = SaharaOnSurfaceVariant
val TextMuted = Color(0xFF887368)
val OutgoingBubble = SaharaPrimaryFixed
val OutgoingBubbleBorder = SaharaOutlineVariant
val IncomingBubble = SaharaSurfaceContainerLowest
val IncomingBubbleBorder = SaharaOutlineVariant
val DarkBackground = SaharaBackground
val DarkSurface = SaharaSurface
val DarkSurfaceVariant = SaharaSurfaceContainer
val DarkCardBorder = SaharaOutlineVariant
val EmeraldAccent = SaharaPrimary
val EmeraldDim = BurntSiennaDim
val CyanAccent = SaharaSecondary
val CyanDim = SaharaSecondary
val AmberAccent = SaharaWarning
val RedAccent = SaharaError
val WhatsAppGreen = SaharaOnline

val SaharaColorScheme = lightColorScheme(
    primary = SaharaPrimary,
    onPrimary = SaharaOnPrimary,
    primaryContainer = SaharaPrimaryContainer,
    onPrimaryContainer = SaharaOnPrimaryContainer,
    secondary = SaharaSecondary,
    onSecondary = SaharaOnSecondary,
    secondaryContainer = SaharaSecondaryContainer,
    onSecondaryContainer = SaharaOnSecondaryContainer,
    tertiary = SaharaTertiary,
    onTertiary = SaharaOnTertiary,
    tertiaryContainer = SaharaTertiaryContainer,
    onTertiaryContainer = SaharaOnTertiaryContainer,
    error = SaharaError,
    onError = SaharaOnError,
    errorContainer = SaharaErrorContainer,
    onErrorContainer = SaharaOnErrorContainer,
    background = SaharaBackground,
    onBackground = SaharaOnBackground,
    surface = SaharaSurface,
    onSurface = SaharaOnSurface,
    surfaceVariant = SaharaSurfaceContainerHigh,
    onSurfaceVariant = SaharaOnSurfaceVariant,
    outline = SaharaOutline,
    outlineVariant = SaharaOutlineVariant
)

val MeshColorScheme = SaharaColorScheme
