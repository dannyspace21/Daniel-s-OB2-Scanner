package com.daniel.carscanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.daniel.carscanner.obd.Level

/*
 * WHAT THIS FILE DOES
 * The look of the whole app: colors and text sizes. Change a color here and
 * it changes everywhere.
 *
 * Style: dark, minimal and spacious, like a premium EV app. Mostly white text on
 * charcoal, one blue for actions, and green / amber / red used ONLY for
 * health status so they always mean something.
 */
object Ui {
    val Bg = Color(0xFF0E0F11)       // page background
    val Card = Color(0xFF1B1D21)     // cards and rows
    val Raised = Color(0xFF26292E)   // buttons, pressed states
    val Line = Color(0xFF2E3136)     // thin separators, empty gauge track
    val Text = Color(0xFFFFFFFF)     // main text
    val Sub = Color(0xFF8E9196)      // secondary text
    val Accent = Color(0xFF3E6AE1)   // action blue
    val Good = Color(0xFF2EBD6B)     // green: normal
    val Watch = Color(0xFFF5A623)    // amber: keep an eye on it
    val Bad = Color(0xFFE8443A)      // red: problem
}

/** The status color for a health level. Unknown is neutral grey. */
val Level.color: Color
    get() = when (this) {
        Level.Good -> Ui.Good
        Level.Watch -> Ui.Watch
        Level.Problem -> Ui.Bad
        Level.Unknown -> Ui.Sub
    }

private val scheme = darkColorScheme(
    primary = Ui.Accent, onPrimary = Color.White,
    secondary = Ui.Raised, onSecondary = Ui.Text,
    background = Ui.Bg, onBackground = Ui.Text,
    surface = Ui.Card, onSurface = Ui.Text,
    surfaceVariant = Ui.Raised, onSurfaceVariant = Ui.Sub,
    surfaceContainer = Ui.Card, surfaceContainerHigh = Ui.Raised, surfaceContainerLow = Ui.Card,
    error = Ui.Bad, onError = Color.White,
    outline = Ui.Line,
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

@Composable
fun CarScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
