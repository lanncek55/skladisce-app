package si.skladisce.app.ui.modals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import si.skladisce.app.data.model.ReceivingSessionModel
import si.skladisce.app.data.model.WarehouseBusinessLogic
import si.skladisce.app.ui.theme.SkladisceCard
import si.skladisce.app.ui.theme.SkladisceColors
import si.skladisce.app.ui.theme.StatTile

@Composable
fun ReceivingBatchDialog(
    session: ReceivingSessionModel,
    onDismiss: () -> Unit,
    onExportPdf: () -> Unit,
    onFinish: () -> Unit
) {
    val totalBoxes = session.entries.sumOf { it.count }
    val uniqueSkus = session.entries.map { it.sku }.distinct()
    var completedOldCount = 0
    var missingCount = 0
    for (sku in uniqueSkus) {
        val first = session.entries.first { it.sku == sku }
        val res = WarehouseBusinessLogic.calculateSkuCompletion(
            sku = sku,
            boxTotal = first.boxTotal,
            initialBoxCounts = session.initialStock[sku].orEmpty(),
            entries = session.entries
        )
        completedOldCount += res.completedFromOld
        if (res.missingInBatch.isNotEmpty()) missingCount++
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = SkladisceColors.BgCard,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, SkladisceColors.Border),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 640.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Prevzemna pošiljka", color = SkladisceColors.Success, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(session.name, color = SkladisceColors.TextMain, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Zapri", tint = SkladisceColors.TextMuted)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatTile("Škatel", "$totalBoxes", SkladisceColors.Primary, Modifier.weight(1f))
                    StatTile("Kompletirano", "$completedOldCount", SkladisceColors.Success, Modifier.weight(1f))
                    StatTile("Primanjkljaj", "$missingCount", if (missingCount > 0) SkladisceColors.Warning else SkladisceColors.TextMuted, Modifier.weight(1f))
                }

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (session.entries.isEmpty()) {
                        item {
                            Text(
                                "Še ni poskeniranih škatel v tej pošiljki. Vsak prevzem (+) v skenerju ali na kartici se samodejno vpiše sem.",
                                color = SkladisceColors.TextMuted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    } else {
                        items(session.entries.reversed(), key = { it.id }) { e ->
                            SkladisceCard(containerColor = SkladisceColors.BgPrimary) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${e.sku} • ${e.name}", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                        Text("Šk. ${e.boxNum}/${e.boxTotal} • ${e.shelf}", color = SkladisceColors.TextMuted, fontSize = 11.sp)
                                    }
                                    Text("+${e.count} kos", color = SkladisceColors.Success, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }
                }

                OutlinedButton(onClick = onExportPdf, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(16.dp), tint = SkladisceColors.Primary)
                    Spacer(Modifier.width(6.dp))
                    Text("Prenesi PDF zapisnik prevzema")
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Nadaljuj", fontSize = 12.5.sp)
                    }
                    Button(
                        onClick = onFinish,
                        colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Success),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Zaključi pošiljko", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ReceivingArchiveDialog(
    sessions: List<ReceivingSessionModel>,
    onDismiss: () -> Unit,
    onExportPdf: (ReceivingSessionModel) -> Unit,
    onDelete: (String) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = SkladisceColors.BgCard,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, SkladisceColors.Border),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 620.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Arhiv prevzemnih pošiljk", color = SkladisceColors.TextMain, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Zapri", tint = SkladisceColors.TextMuted)
                    }
                }

                if (sessions.isEmpty()) {
                    Text("Arhiv prevzemnih pošiljk je prazen.", color = SkladisceColors.TextMuted, modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sessions, key = { it.id }) { s ->
                            SkladisceCard(containerColor = SkladisceColors.BgPrimary) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.name, color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(
                                            "${WarehouseBusinessLogic.formatDisplayDateTime(s.finishedAt ?: s.startedAt)} • ${s.totalBoxes} škatel",
                                            color = SkladisceColors.TextMuted,
                                            fontSize = 11.5.sp
                                        )
                                    }
                                    Row {
                                        IconButton(onClick = { onExportPdf(s) }) {
                                            Icon(Icons.Default.PictureAsPdf, contentDescription = "PDF", tint = SkladisceColors.Primary)
                                        }
                                        IconButton(onClick = { onDelete(s.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Izbriši", tint = SkladisceColors.Danger)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
