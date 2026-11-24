package si.skladisce.app.ui.modals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import si.skladisce.app.ui.ReceiptDispatchUiState
import si.skladisce.app.ui.theme.SkladisceCard
import si.skladisce.app.ui.theme.SkladisceColors
import si.skladisce.app.ui.theme.StatusPill

@Composable
fun ReceiptDispatchDialog(
    state: ReceiptDispatchUiState,
    onToggleBoxPicked: (sku: String, boxNum: Int) -> Unit,
    onDismiss: () -> Unit,
    onFinish: () -> Unit
) {
    val progress = if (state.items.isNotEmpty()) state.pickedCount.toFloat() / state.items.size else 0f

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
                .heightIn(max = 650.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Izdaja blaga po računu", color = SkladisceColors.Warning, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Račun št. ${state.receiptNo}", color = SkladisceColors.TextMain, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Zapri", tint = SkladisceColors.TextMuted)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${state.pickedCount} / ${state.items.size} artiklov nabranih", color = SkladisceColors.TextMuted, fontSize = 12.sp)
                    LinearProgressIndicator(
                        progress = { progress },
                        color = SkladisceColors.Success,
                        trackColor = SkladisceColors.BgPrimary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.items, key = { it.sku }) { item ->
                        SkladisceCard(
                            borderColor = if (item.isAllPicked) SkladisceColors.Success else SkladisceColors.Border,
                            containerColor = if (item.isAllPicked) Color(0xFF0E2921) else SkladisceColors.BgPrimary
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${item.sku} • ${item.name}", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                        Text("Količina za izdajo: ${item.qty}x", color = SkladisceColors.TextMuted, fontSize = 11.5.sp)
                                    }
                                    if (item.isAllPicked) {
                                        Icon(Icons.Default.CheckCircle, null, tint = SkladisceColors.Success)
                                    }
                                }

                                item.boxPicks.forEach { bp ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Šk. ${bp.boxNum}/${bp.boxTotal} • Regal: ${bp.shelf}", color = SkladisceColors.TextMain, fontSize = 12.sp)
                                            Text("Zaloga na lokaciji: ${bp.stockAvailable} kos", color = SkladisceColors.TextDim, fontSize = 11.sp)
                                        }
                                        Button(
                                            onClick = { onToggleBoxPicked(item.sku, bp.boxNum) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (bp.picked) SkladisceColors.Success else SkladisceColors.PrimaryDark
                                            )
                                        ) {
                                            Text(if (bp.picked) "Odvzeto ✓" else "Potrdi odvzem", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(0.8f)) { Text("Zapri") }
                    Button(
                        onClick = onFinish,
                        enabled = state.allCompleted,
                        colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Success),
                        modifier = Modifier.weight(1.3f)
                    ) {
                        Text("Zaključi izdajo", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
