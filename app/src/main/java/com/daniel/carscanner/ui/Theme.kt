package com.daniel.carscanner.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.daniel.carscanner.obd.Level

/*
 * WHAT THIS FILE DOES
 * The look of the whole app: colors, text sizes, and day/night mode.
 *
 * There are two color sets (palettes), dark and light. Every screen asks
 * Ui.Bg, Ui.Text and so on for its colors, and those always return the
 * current palette, so switching modes recolors everything instantly.
 *
 * Green / amber / red are used ONLY for health status, so they always mean
 * something. The light palette uses slightly deeper shades so they stay
 * readable in sunlight.
 */

/** The three appearance choices on the About screen. */
enum class ThemeMode { System, Light, Dark }

/** One full set of app colors. */
class Palette(
    val bg: Color, val card: Color, val raised: Color, val line: Color,
    val text: Color, val sub: Color, val accent: Color,
    val good: Color, val watch: Color, val bad: Color,
)

private val DarkPalette = Palette(
    bg = Color(0xFF0E0F11), card = Color(0xFF1B1D21), raised = Color(0xFF26292E), line = Color(0xFF2E3136),
    text = Color(0xFFFFFFFF), sub = Color(0xFF8E9196), accent = Color(0xFF3E6AE1),
    good = Color(0xFF2EBD6B), watch = Color(0xFFF5A623), bad = Color(0xFFE8443A),
)

private val LightPalette = Palette(
    bg = Color(0xFFF4F5F7), card = Color(0xFFFFFFFF), raised = Color(0xFFE8EAEE), line = Color(0xFFD9DCE1),
    text = Color(0xFF111317), sub = Color(0xFF5F6670), accent = Color(0xFF3360D6),
    good = Color(0xFF1E9E57), watch = Color(0xFFC77F00), bad = Color(0xFFD23A30),
)

/**
 * The app's colors. Each one reads the current palette, which is "state",
 * so anything drawn with these colors redraws itself when the mode changes.
 */
object Ui {
    private var palette by mutableStateOf(DarkPalette)

    /** Switches palettes. Called by the theme and once at app start. */
    fun use(dark: Boolean) { palette = if (dark) DarkPalette else LightPalette }

    val isDark: Boolean get() = palette === DarkPalette
    val Bg: Color get() = palette.bg          // page background
    val Card: Color get() = palette.card      // cards and rows
    val Raised: Color get() = palette.raised  // buttons, pressed states
    val Line: Color get() = palette.line      // thin separators, empty gauge track
    val Text: Color get() = palette.text      // main text
    val Sub: Color get() = palette.sub        // secondary text
    val Accent: Color get() = palette.accent  // action blue
    val Good: Color get() = palette.good      // green: normal
    val Watch: Color get() = palette.watch    // amber: keep an eye on it
    val Bad: Color get() = palette.bad        // red: problem
}

/** The status color for a health level. Unknown is neutral grey. */
val Level.color: Color
    get() = when (this) {
        Level.Good -> Ui.Good
        Level.Watch -> Ui.Watch
        Level.Problem -> Ui.Bad
        Level.Unknown -> Ui.Sub
    }

/** Builds the Material color scheme (used by built-in parts like dialogs and text fields). */
private fun schemeFor(dark: Boolean, p: Palette) =
    if (dark) darkColorScheme(
        primary = p.accent, onPrimary = Color.White, secondary = p.raised, onSecondary = p.text,
        background = p.bg, onBackground = p.text, surface = p.card, onSurface = p.text,
        surfaceVariant = p.raised, onSurfaceVariant = p.sub, surfaceContainer = p.card,
        surfaceContainerHigh = p.raised, surfaceContainerLow = p.card, error = p.bad, onError = Color.White, outline = p.line,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = Color.White, secondary = p.raised, onSecondary = p.text,
        background = p.bg, onBackground = p.text, surface = p.card, onSurface = p.text,
        surfaceVariant = p.raised, onSurfaceVariant = p.sub, surfaceContainer = p.card,
        surfaceContainerHigh = p.raised, surfaceContainerLow = p.card, error = p.bad, onError = Color.White, outline = p.line,
    )

// Light weights for big numbers and titles give the clean, airy feel.
private val type = Typography(
    displayMedium = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
)

/** Wraps the whole app. Picks day or night from your setting (or the phone's, on System). */
@Composable
fun CarScannerTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val palette = if (dark) DarkPalette else LightPalette
    val view = LocalView.current

    // After drawing: switch the app palette and color the phone's status and navigation bars to match.
    SideEffect {
        Ui.use(dark)
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = palette.bg.toArgb()
        window.navigationBarColor = palette.bg.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = schemeFor(dark, palette), typography = type, content = content)
}
