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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import si.skladisce.app.data.model.InventoryItem
import si.skladisce.app.data.model.ScatteredItem
import si.skladisce.app.ui.QuickStockChangeRequest
import si.skladisce.app.ui.theme.SkladisceCard
import si.skladisce.app.ui.theme.SkladisceColors

@Composable
fun QuickStockChangeDialog(
    req: QuickStockChangeRequest,
    onDismiss: () -> Unit,
    onConfirm: (boxNum: Int, shelf: String, amount: Int, note: String) -> Unit
) {
    val item = req.item
    var boxNum by remember { mutableIntStateOf(1) }
    var shelf by remember { mutableStateOf(item.boxes[1]?.locations?.firstOrNull()?.name ?: item.primaryShelf) }
    var amountText by remember { mutableStateOf("1") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SkladisceColors.BgCard,
        title = {
            Text(
                if (req.isReceive) "Prevzem (+): ${item.sku}" else "Izdaja (−): ${item.sku}",
                color = if (req.isReceive) SkladisceColors.Success else SkladisceColors.Danger,
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.name, color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                if (item.boxTotal > 1) {
                    Text("Škatla:", color = SkladisceColors.TextMuted, fontSize = 11.5.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (b in 1..item.boxTotal) {
                            FilterChip(
                                selected = boxNum == b,
                                onClick = {
                                    boxNum = b
                                    shelf = item.boxes[b]?.locations?.firstOrNull()?.name ?: item.primaryShelf
                                },
                                label = { Text("$b/${item.boxTotal}", fontSize = 11.sp) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = shelf,
                    onValueChange = { shelf = it },
                    label = { Text("Regal / polica") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter(Char::isDigit) },
                    label = { Text("Količina (kos)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Razlog / dobavnica (opcijsko)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(boxNum, shelf, amountText.toIntOrNull() ?: 1, note) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (req.isReceive) SkladisceColors.Success else SkladisceColors.Danger
                )
            ) { Text("Potrdi premik", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Prekliči") } }
    )
}

@Composable
fun MoveStockDialog(
    item: InventoryItem,
    onDismiss: () -> Unit,
    onConfirm: (boxNum: Int, fromShelf: String, toShelf: String, amount: Int, note: String) -> Unit
) {
    var boxNum by remember { mutableIntStateOf(1) }
    val availLocs = item.boxes[boxNum]?.locations?.filter { it.count > 0 && it.boxId == null }.orEmpty()
    var fromShelf by remember(boxNum) { mutableStateOf(availLocs.firstOrNull()?.name ?: item.primaryShelf) }
    var toShelf by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("1") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SkladisceColors.BgCard,
        title = { Text("Premestitev med regali", color = SkladisceColors.TextMain, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${item.sku} • ${item.name}", color = SkladisceColors.Primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                if (item.boxTotal > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (b in 1..item.boxTotal) {
                            FilterChip(
                                selected = boxNum == b,
                                onClick = { boxNum = b },
                                label = { Text("Šk. $b/${item.boxTotal}", fontSize = 11.sp) }
                            )
                        }
                    }
                }
                if (availLocs.isNotEmpty()) {
                    Text("Izvorna lokacija:", color = SkladisceColors.TextMuted, fontSize = 11.5.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        availLocs.forEach { loc ->
                            FilterChip(
                                selected = fromShelf == loc.name,
                                onClick = { fromShelf = loc.name },
                                label = { Text("${loc.name} (${loc.count})", fontSize = 11.sp) }
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = fromShelf,
                        onValueChange = { fromShelf = it },
                        label = { Text("Iz regala") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = toShelf,
                    onValueChange = { toShelf = it },
                    label = { Text("Na ciljni regal (npr. R1-A-P1)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter(Char::isDigit) },
                    label = { Text("Količina") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Opomba (opcijsko)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(boxNum, fromShelf, toShelf, amountText.toIntOrNull() ?: 1, note) }) {
                Text("Potrdi premestitev", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Prekliči") } }
    )
}

@Composable
fun ConsolidationDialog(
    scattered: List<ScatteredItem>,
    onDismiss: () -> Unit,
    onExportPdf: () -> Unit,
    onQuickConsolidate: (sku: String, targetShelf: String) -> Unit
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
                    Column {
                        Text("Konsolidacija zaloge", color = SkladisceColors.TextMain, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Združevanje razpršenih škatel na en regal", color = SkladisceColors.TextMuted, fontSize = 12.sp)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Zapri", tint = SkladisceColors.TextMuted)
                    }
                }

                OutlinedButton(onClick = onExportPdf, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(16.dp), tint = SkladisceColors.Primary)
                    Spacer(Modifier.width(6.dp))
                    Text("Prenesi delovni nalog (PDF)")
                }

                if (scattered.isEmpty()) {
                    Text("Noben artikel ni razpršen čez več regalov!", color = SkladisceColors.Success, modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(scattered, key = { it.sku }) { sc ->
                            SkladisceCard(containerColor = SkladisceColors.BgPrimary) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${sc.sku} • ${sc.name}", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                    Text("Ciljni glavni regal: ${sc.targetShelf}", color = SkladisceColors.Success, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    sc.boxesToMove.forEach { loc ->
                                        Text("• ${loc.count}x Šk.${loc.boxNum}/${loc.boxTotal} na [${loc.shelf}]", color = SkladisceColors.TextMuted, fontSize = 11.5.sp)
                                    }
                                    Button(
                                        onClick = { onQuickConsolidate(sc.sku, sc.targetShelf) },
                                        colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Accent),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Premakni vse na ${sc.targetShelf}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
