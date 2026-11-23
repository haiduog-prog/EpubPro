package com.epubpro.core.designsystem.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Premium Color Palettes (Based on TTS Setup style)
val PrimaryLight = Color(0xFFD97757) // Terracotta / Burnt Orange
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFFFF7F2)
val OnPrimaryContainerLight = Color(0xFFD97757)
val BackgroundLight = Color(0xFFF8F9FA)
val SurfaceLight = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF212121)
val SurfaceVariantLight = Color(0xFFF5F5F5)
val OnSurfaceVariantLight = Color(0xFF424242)

val PrimaryDark = Color(0xFFE88A6A) // Lighter Terracotta for dark mode
val OnPrimaryDark = Color(0xFF212121)
val PrimaryContainerDark = Color(0xFF5A3125)
val OnPrimaryContainerDark = Color(0xFFFFECE5)
val BackgroundDark = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E1E)
val OnSurfaceDark = Color(0xFFE0E0E0)
val SurfaceVariantDark = Color(0xFF2D2D2D)
val OnSurfaceVariantDark = Color(0xFFA0A0A0)

val SepiaBackground = Color(0xFFFBF0D9)
val SepiaSurface = Color(0xFFF3E5AB)
val SepiaOnSurface = Color(0xFF4A3B32)
val SepiaPrimary = Color(0xFFD97757) // Use same primary for consistency

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    background = BackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    background = BackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark
)

val SepiaColorScheme = lightColorScheme(
    primary = SepiaPrimary,
    onPrimary = Color.White,
    primaryContainer = SepiaSurface,
    onPrimaryContainer = SepiaOnSurface,
    background = SepiaBackground,
    onBackground = SepiaOnSurface,
    surface = SepiaSurface,
    onSurface = SepiaOnSurface,
    surfaceVariant = SepiaSurface,
    onSurfaceVariant = SepiaOnSurface
)

@Composable
fun EpubProTheme(
    darkTheme: Boolean = false,
    sepiaTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        sepiaTheme -> SepiaColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
