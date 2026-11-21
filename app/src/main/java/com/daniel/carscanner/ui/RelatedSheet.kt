@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.daniel.carscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.net.Related
import com.daniel.carscanner.net.RelatedLink
import com.daniel.carscanner.obd.Vin

/*
 * WHAT THIS FILE DOES
 * The "Related for your car" sheet that slides up when you tap Look up on a
 * code, or "Related for your car" on a red or amber result.
 *  - Recalls for your car that touch the same system come first.
 *  - Then searches for your exact year, make and model plus the problem:
 *    web, repair videos, owner forums and service bulletins.
 * Every row opens in your browser (or the YouTube app).
 *
 * topic    what to search for, e.g. "P0420 catalyst efficiency below threshold"
 * keywords which recall components count as related (see Related.kt)
 */
@Composable
fun RelatedSheet(vm: ScannerViewModel, topic: String, keywords: List<String>, onDismiss: () -> Unit) {
    val info by vm.vehicle.collectAsStateWithLifecycle()
    val recalls by vm.recalls.collectAsStateWithLifecycle()
    val vin by vm.vin.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current

    val car = Related.carName(info, vin?.let { Vin.describe(it) })
    val links = remember(car, topic) { Related.links(car, info?.make, topic) }
    val matches = remember(recalls, keywords) { Related.matchingRecalls(recalls, keywords) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ui.Card) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp)) {
            Text("Related for your car", style = MaterialTheme.typography.titleLarge)
            Text(
                if (car.isBlank()) "Connect or enter your VIN on the Vehicle screen for results matched to your car." else car,
                color = Ui.Sub,
            )
            Text(topic, style = MaterialTheme.typography.bodySmall, color = Ui.Sub, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))

            if (matches.isNotEmpty()) {
                Text("Recalls that may be related", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                matches.forEach { r ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                            .background(Ui.Raised).clickable { runCatching { uri.openUri(r.url) } }.padding(14.dp),
                    ) {
                        Row { StatusDot(Ui.Bad); Spacer(Modifier.width(8.dp)); Text(r.component, style = MaterialTheme.typography.labelMedium, color = Ui.Bad) }
                        Text(r.summary, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
                        Text("Campaign ${r.campaign}. Tap for details on nhtsa.gov.", color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            links.forEach { l ->
                NavRow(
                    icon = when (l.kind) {
                        RelatedLink.Kind.Web -> Icons.Filled.Search
                        RelatedLink.Kind.Video -> Icons.Filled.PlayCircle
                        RelatedLink.Kind.Forum -> Icons.Filled.Forum
                        RelatedLink.Kind.Bulletin -> Icons.Filled.Article
                    },
                    title = l.title,
                    subtitle = l.subtitle,
                ) { runCatching { uri.openUri(l.url) } }
            }
        }
    }
}
