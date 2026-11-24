package si.skladisce.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import si.skladisce.app.data.model.WarehouseBusinessLogic
import si.skladisce.app.ui.MainViewModel
import si.skladisce.app.ui.theme.SkladisceCard
import si.skladisce.app.ui.theme.SkladisceColors
import si.skladisce.app.ui.theme.StatusPill

@Composable
fun HistoryScreen(viewModel: MainViewModel) {
    val logs by viewModel.logs.collectAsState()
    var showConfirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Dnevnik premikov", color = SkladisceColors.TextMain, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Revizijska sled prevzemov, izdaj in prenosov (${logs.size})", color = SkladisceColors.TextMuted, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { showConfirmClear = true },
                    border = BorderStroke(1.dp, SkladisceColors.Danger.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp), tint = SkladisceColors.Danger)
                    Spacer(Modifier.width(4.dp))
                    Text("Počisti", color = SkladisceColors.Danger, fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                SkladisceCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = SkladisceColors.TextDim, modifier = Modifier.size(36.dp))
                        Text("Dnevnik premikov je prazen.", color = SkladisceColors.TextMuted)
                    }
                }
            }
        } else {
            items(logs, key = { it.id }) { log ->
                val (label, col, bg) = when (log.changeType) {
                    "in", "create" -> Triple(if (log.changeType == "create") "NOV VNOS" else "PREVZEM", SkladisceColors.Success, SkladisceColors.SuccessBg)
                    "out" -> Triple("IZDAJA", SkladisceColors.Danger, SkladisceColors.DangerBg)
                    "delete", "delete_mixed_box" -> Triple("IZBRIS", SkladisceColors.Danger, SkladisceColors.DangerBg)
                    "pack_mixed_box" -> Triple("MEŠANA ŠKATLA", SkladisceColors.Accent, Color(0x266366F1))
                    "replenish_shelf" -> Triple("POLICA", SkladisceColors.Primary, Color(0x2238BDF8))
                    else -> Triple("PREMIK", SkladisceColors.Warning, SkladisceColors.WarningBg)
                }

                SkladisceCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(label, col, bg)
                                Text(log.sku, color = SkladisceColors.Primary, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                            }
                            Text(
                                text = WarehouseBusinessLogic.formatDisplayDateTime(log.timestamp),
                                color = SkladisceColors.TextDim,
                                fontSize = 11.sp
                            )
                        }
                        Text(log.itemName, color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                        if (log.note.isNotBlank() || log.shelf.isNotBlank()) {
                            Text(
                                text = listOf(log.note, log.shelf).filter { it.isNotBlank() }.joinToString(" • "),
                                color = SkladisceColors.TextMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val signStr = if (log.changeAmount > 0) "+${log.changeAmount}" else "${log.changeAmount}"
                            Text("Sprememba: $signStr kos", color = col, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                            if (log.deviceId.isNotBlank()) {
                                Text("Naprava: DEV-${log.deviceId}", color = SkladisceColors.TextDim, fontSize = 10.5.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConfirmClear) {
        AlertDialog(
            onDismissRequest = { showConfirmClear = false },
            containerColor = SkladisceColors.BgCard,
            title = { Text("Počisti zgodovino premikov?", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold) },
            text = { Text("Zaloga artiklov bo ostala nespremenjena, izbrišejo se le zapisi v dnevniku.", color = SkladisceColors.TextMuted) },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmClear = false
                        viewModel.clearLogs()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Danger)
                ) { Text("Počisti") }
            },
            dismissButton = { TextButton(onClick = { showConfirmClear = false }) { Text("Prekliči") } }
        )
    }
}
