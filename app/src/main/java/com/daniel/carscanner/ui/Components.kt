package com.daniel.carscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/*
 * WHAT THIS FILE DOES
 * Small reusable building blocks, so every screen looks consistent:
 *   Page        - a screen with a back arrow and title
 *   NavRow      - a tappable list row with icon, text, status dot and ">"
 *   RoundAction - the round quick-action buttons on the Home screen
 *   StatusDot   - the little green / amber / red dot
 *   PrimaryButton / SecondaryButton - the two button styles
 */

/** A full screen with a back arrow (if onBack is given) and a title. */
@Composable
fun Page(title: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ui.Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Back", tint = Ui.Text, modifier = Modifier.size(32.dp))
                }
            } else Spacer(Modifier.width(20.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** A list row like the Tesla app's: icon, title, optional subtitle and status dot, then a chevron. */
@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String? = null, dot: Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Ui.Text, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ui.Sub, maxLines = 1)
        }
        if (dot != null) { StatusDot(dot); Spacer(Modifier.width(10.dp)) }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Ui.Sub)
    }
}

/** Round icon button with a label underneath. */
@Composable
fun RoundAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Box(
            Modifier.size(60.dp).clip(CircleShape).background(Ui.Raised)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (enabled) Ui.Text else Ui.Sub, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (enabled) Ui.Text else Ui.Sub)
    }
}

@Composable
fun StatusDot(color: Color, size: Int = 9) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/** Main action: solid blue pill. */
@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ui.Accent, disabledContainerColor = Ui.Raised),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Secondary action: dark grey pill. color lets you make a red "Stop" button. */
@Composable
fun SecondaryButton(text: String, enabled: Boolean = true, color: Color = Ui.Raised, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Ui.Text, disabledContainerColor = Ui.Card),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Shown on car screens when nothing is connected yet. */
@Composable
fun NeedsConnection(goConnect: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Connect your adapter to use this.", color = Ui.Sub)
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Connect adapter", onClick = goConnect)
    }
}
