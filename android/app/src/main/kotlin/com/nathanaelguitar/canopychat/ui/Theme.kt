package com.nathanaelguitar.canopychat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Port of AetherColors from iphone/AetherChat/Theme.swift.
object OakColors {
    val oakDark = Color(0xFF3D2914)
    val oakMedium = Color(0xFF6B4423)
    val oakLight = Color(0xFFA0784A)
    val oakPale = Color(0xFFD4B896)
    val oakCream = Color(0xFFF5EDE0)
    val forestMedium = Color(0xFF4A7C4A)
    val forestDark = Color(0xFF2F5233)
    val forestPale = Color(0xFFC8DEC8)
    val copper = Color(0xFFB87333)
    val amber = Color(0xFFD4A017)
    val warmGray100 = Color(0xFFF0EBE3)
    val warmGray200 = Color(0xFFE0D8CC)
    val warmGray400 = Color(0xFFB0A090)
    val warmGray500 = Color(0xFF907868)
    val warmGray600 = Color(0xFF706050)
    val warmGray700 = Color(0xFF504030)
    val warmGray800 = Color(0xFF302820)
    val warmGray900 = Color(0xFF1A1612)
    val info = Color(0xFF4A7CB8)
    val error = Color(0xFFC84040)
    val warmBlack = Color(0xFF1A1208)
}

// Material components (AlertDialog, DropdownMenu, OutlinedTextField, Switch, Slider)
// inherit from MaterialTheme, so an oak-toned scheme here is what keeps dialogs and
// controls from falling back to light surfaces with purple accents in dark mode.
private val LightCanopyScheme = lightColorScheme(
    primary = OakColors.oakMedium,
    onPrimary = Color.White,
    primaryContainer = OakColors.oakPale,
    onPrimaryContainer = OakColors.oakDark,
    secondary = OakColors.forestMedium,
    onSecondary = Color.White,
    surface = OakColors.oakCream,
    onSurface = OakColors.warmBlack,
    surfaceVariant = OakColors.warmGray100,
    onSurfaceVariant = OakColors.warmGray600,
    background = OakColors.oakCream,
    onBackground = OakColors.warmBlack,
    outline = OakColors.warmGray400,
    error = OakColors.error,
    onError = Color.White
)

private val DarkCanopyScheme = darkColorScheme(
    primary = OakColors.oakLight,
    onPrimary = OakColors.warmGray900,
    primaryContainer = OakColors.oakMedium,
    onPrimaryContainer = OakColors.oakCream,
    secondary = OakColors.forestPale,
    onSecondary = OakColors.warmGray900,
    surface = OakColors.warmGray900,
    onSurface = OakColors.warmGray100,
    surfaceVariant = OakColors.warmGray800,
    onSurfaceVariant = OakColors.warmGray400,
    background = OakColors.warmGray900,
    onBackground = OakColors.warmGray100,
    outline = OakColors.warmGray600,
    error = OakColors.error,
    onError = Color.White
)

/** Tracks the app's oak dark toggle (distinct from the OS theme) for color helpers. */
val LocalIsOakDark = androidx.compose.runtime.compositionLocalOf { false }

@Composable
fun CanopyTheme(isDark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (isDark) DarkCanopyScheme else LightCanopyScheme
    MaterialTheme(colorScheme = scheme) {
        // iOS renders near-white text on the dark oak background. Unstyled Text
        // composables default to LocalContentColor (black), which is unreadable on
        // warmGray800/900 surfaces — drive the default from the scheme instead.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides scheme.onBackground,
            LocalIsOakDark provides isDark,
            content = content
        )
    }
}

/** iOS uses warmGray400 subtitles in dark mode, warmGray500 in light. */
@Composable
fun oakSubtitle(): Color = if (LocalIsOakDark.current) OakColors.warmGray400 else OakColors.warmGray500
