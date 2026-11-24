package si.skladisce.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import si.skladisce.app.ui.MainViewModel
import si.skladisce.app.ui.QuickScannerTarget
import si.skladisce.app.ui.theme.SkladisceCard
import si.skladisce.app.ui.theme.SkladisceColors
import si.skladisce.app.ui.theme.StatusPill
import si.skladisce.app.ui.theme.warehouseColor
import si.skladisce.app.ui.theme.warehouseLabel
import kotlin.math.max

@Composable
fun ReplenishmentScreen(viewModel: MainViewModel) {
    val lookupInput by viewModel.repLookupInput.collectAsState()
    val hitItem by viewModel.repHitItem.collectAsState()
    val listFilter by viewModel.repListWarehouseFilter.collectAsState()
    val replenishments by viewModel.replenishments.collectAsState()
    val mixedBoxes by viewModel.mixedBoxes.collectAsState()

    var capText by remember { mutableStateOf("10") }
    var currentText by remember { mutableStateOf("0") }
    var targetShelfText by remember { mutableStateOf("") }

    LaunchedEffect(hitItem) {
        hitItem?.let { item ->
            capText = (if (item.shelfCapacity > 0) item.shelfCapacity else 10).toString()
            currentText = item.storeQuantity.toString()
            targetShelfText = item.shelfLocation.ifBlank { "Polica T-01" }
        }
    }

    val filteredList = remember(replenishments, listFilter) {
        replenishments.filter { r ->
            listFilter == "all" || r.warehouse == listFilter
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Glava + Mešane škatle pill
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Police & Nabiranje", color = SkladisceColors.TextMain, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Preverba cenovke v prodajalni in prenos iz skladišča", color = SkladisceColors.TextMuted, fontSize = 12.sp)
                }
                SkladisceCard(
                    borderColor = SkladisceColors.Primary.copy(alpha = 0.5f),
                    containerColor = SkladisceColors.BgCardElevated,
                    modifier = Modifier.clickable { viewModel.openMixedBoxesModal(0) }
                ) {
                    Text(
                        "📦 Mešane (${mixedBoxes.size})",
                        color = SkladisceColors.Primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Zgornji del: Preverba police / cenovke
        item {
            SkladisceCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Preverba police / cenovke", color = SkladisceColors.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = lookupInput,
                            onValueChange = { viewModel.setRepLookupInput(it) },
                            label = { Text("7-mestna šifra (SKU)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { viewModel.openQuickScanner(QuickScannerTarget.REPLENISHMENT) },
                            colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.BgCardElevated),
                            border = BorderStroke(1.dp, SkladisceColors.Border),
                            contentPadding = PaddingValues(12.dp),
                            modifier = Modifier.height(54.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Skeniraj", tint = SkladisceColors.Primary)
                        }
                        Button(
                            onClick = { viewModel.lookupReplenishmentSku() },
                            colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Primary),
                            modifier = Modifier.height(54.dp)
                        ) {
                            Text("Išči", fontWeight = FontWeight.Bold)
                        }
                    }

                    // Kartica zadetka
                    hitItem?.let { item ->
                        val capVal = capText.toIntOrNull() ?: 10
                        val curVal = currentText.toIntOrNull() ?: 0
                        val needed = max(0, capVal - curVal)

                        SkladisceCard(
                            borderColor = SkladisceColors.Success.copy(alpha = 0.5f),
                            containerColor = Color(0xFF0E1D27)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${item.sku} • ${item.name}", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(
                                            "Skladišče: ${item.warehouseQuantity} kos (Regal: ${item.primaryShelf})",
                                            color = SkladisceColors.TextMuted,
                                            fontSize = 11.5.sp
                                        )
                                    }
                                    StatusPill("Manjka: $needed kos", SkladisceColors.Warning, SkladisceColors.WarningBg)
                                }

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = capText,
                                        onValueChange = { capText = it.filter { ch -> ch.isDigit() } },
                                        label = { Text("Kapaciteta") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = currentText,
                                        onValueChange = { currentText = it.filter { ch -> ch.isDigit() } },
                                        label = { Text("Na polici") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = targetShelfText,
                                        onValueChange = { targetShelfText = it },
                                        label = { Text("Polica TRG") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1.2f)
                                    )
                                }

                                Button(
                                    onClick = { viewModel.addToReplenishment(item.sku, capVal, curVal, targetShelfText) },
                                    colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Success),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("+ Dodaj na nabiralni seznam ($needed kos)", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Glava nabiralnega seznama + PDF + Počisti + Filtri
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Nabiralni seznam (${filteredList.count { it.status == "pending" }} aktivnih)",
                    color = SkladisceColors.TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.exportReplenishmentPdf() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp), tint = SkladisceColors.Primary)
                        Spacer(Modifier.width(4.dp))
                        Text("PDF nalog", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = { viewModel.clearCompletedReplenishments() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Počisti", fontSize = 12.sp, color = SkladisceColors.TextMuted, maxLines = 1, softWrap = false)
                    }
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("all" to "Vse", "zgornje" to "Zgornje skl.", "spodnje" to "Spodnje skl.").forEach { (id, lbl) ->
                        FilterChip(
                            selected = listFilter == id,
                            onClick = { viewModel.setRepListWarehouseFilter(id) },
                            label = { Text(lbl, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }

        if (filteredList.isEmpty()) {
            item {
                SkladisceCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Nabiralni seznam je prazen.",
                        color = SkladisceColors.TextMuted,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
        } else {
            items(filteredList, key = { it.id }) { rep ->
                val isDone = rep.status == "completed"
                SkladisceCard(
                    borderColor = if (isDone) SkladisceColors.Success.copy(alpha = 0.45f) else SkladisceColors.Border
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(rep.sku, color = SkladisceColors.Primary, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                                    StatusPill(
                                        warehouseLabel(rep.warehouse),
                                        warehouseColor(rep.warehouse),
                                        warehouseColor(rep.warehouse).copy(alpha = 0.16f)
                                    )
                                    if (isDone) {
                                        StatusPill("PRENESENO", SkladisceColors.Success, SkladisceColors.SuccessBg)
                                    }
                                }
                                Text(rep.name, color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(
                                    "Vzemi iz: ${rep.sourceLocation}  ➔  Polica: ${rep.targetShelf}",
                                    color = SkladisceColors.TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                "${rep.neededQty} kos",
                                color = if (isDone) SkladisceColors.Success else SkladisceColors.Warning,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { viewModel.removeReplenishmentItem(rep.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Odstrani", tint = SkladisceColors.Danger)
                            }
                            if (!isDone) {
                                Button(
                                    onClick = { viewModel.completeReplenishmentItem(rep.id) },
                                    colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Success)
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Prenesi na polico (${rep.neededQty} kos)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
