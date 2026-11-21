package com.daniel.carscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.daniel.carscanner.data.CodeWatch
import com.daniel.carscanner.data.ReturnedCode

/*
 * WHAT THIS FILE DOES
 * The red "came back" card shown on Home and the Codes screen when a code you
 * cleared reappears. Tap it to look the code up; Dismiss hides it.
 */
@Composable
fun ReturnedBanner(r: ReturnedCode, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.Bad.copy(alpha = 0.14f))
            .clickable(onClick = onOpen).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(Ui.Bad, 10)
            Spacer(Modifier.width(10.dp))
            Text("${r.code} came back", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss", color = Ui.Sub) }
        }
        Text(
            "${r.where}. You cleared it ${CodeWatch.ago(r.clearedAt, System.currentTimeMillis())} ago. " +
                "The repair didn't hold, or the cause is still there. Tap to look it up for your car.",
            color = Ui.Text,
        )
    }
}
