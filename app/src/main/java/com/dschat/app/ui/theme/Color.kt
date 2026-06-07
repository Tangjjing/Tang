package com.dschat.app.ui.theme

import androidx.compose.ui.graphics.Color

// Calm monochrome palette (Marvis-like): black / white / neutral grays, no color accents.

// Light
val LightBackground = Color(0xFFFFFFFF)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF2F2F3)   // user bubble, cards, chips, code blocks
val LightOnSurface = Color(0xFF1A1A1C)
val LightOnSurfaceVariant = Color(0xFF5F5F66)   // darkened to meet WCAG AA on white/灰卡 for small captions
val LightOutline = Color(0xFFE4E4E7)
val InkLight = Color(0xFF1A1A1C)              // primary: near-black (buttons, selected states)

// Dark
val DarkBackground = Color(0xFF111113)
val DarkSurface = Color(0xFF1A1A1D)
val DarkSurfaceVariant = Color(0xFF27272B)
val DarkOnSurface = Color(0xFFECECEE)
val DarkOnSurfaceVariant = Color(0xFF9B9BA2)
val DarkOutline = Color(0xFF3A3A3F)
val InkDark = Color(0xFFECECEE)               // primary on dark: near-white
