package si.skladisce.app.ui.modals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import si.skladisce.app.ui.MainViewModel
import si.skladisce.app.ui.QuickScannerTarget
import si.skladisce.app.ui.theme.SkladisceCard
import si.skladisce.app.ui.theme.SkladisceColors
import si.skladisce.app.ui.theme.StatusPill
import si.skladisce.app.ui.theme.warehouseLabel

@Composable
fun MixedBoxesDialog(viewModel: MainViewModel) {
    val mixedBoxes by viewModel.mixedBoxes.collectAsState()
    val draftItems by viewModel.mixedPackDraftItems.collectAsState()
    val pendingSku by viewModel.mixedPackPendingSku.collectAsState()
    val initialTab by viewModel.mixedBoxesInitialTab.collectAsState()

    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }
    var searchQuery by remember { mutableStateOf("") }

    var boxNumText by remember { mutableStateOf("101") }
    var packWarehouse by remember { mutableStateOf("zgornje") }
    var packShelf by remember { mutableStateOf("R1-A-P1") }
    var addSku by remember { mutableStateOf("") }
    var addQty by remember { mutableStateOf("1") }
    var packNotes by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        boxNumText = viewModel.repo.nextMixedBoxNumber()
    }
    LaunchedEffect(pendingSku) {
        if (pendingSku.isNotBlank()) {
            addSku = pendingSku
        }
    }

    val filteredBoxes = remember(mixedBoxes, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) mixedBoxes else mixedBoxes.filter { b ->
            b.boxNumber.lowercase().contains(q) ||
                b.shelf.lowercase().contains(q) ||
                b.items.any { it.sku.lowercase().contains(q) || it.name.lowercase().contains(q) }
        }
    }

    Dialog(
        onDismissRequest = { viewModel.closeMixedBoxesModal() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = SkladisceColors.BgCard,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, SkladisceColors.Border),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 670.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Mešane škatle (Soft blago)", color = SkladisceColors.TextMain, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Več artiklov v enem kartonu + Citizen 6×6 nalepke", color = SkladisceColors.TextMuted, fontSize = 11.5.sp)
                    }
                    IconButton(onClick = { viewModel.closeMixedBoxesModal() }) {
                        Icon(Icons.Default.Close, contentDescription = "Zapri", tint = SkladisceColors.TextMuted)
                    }
                }

                TabRow(selectedTabIndex = selectedTab, containerColor = SkladisceColors.BgPrimary) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Pregled (${mixedBoxes.size})", fontSize = 12.5.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("+ Pakiraj novo", fontSize = 12.5.sp, fontWeight = FontWeight.Bold) }
                    )
                }

                if (selectedTab == 0) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Išči številko škatle, regal ali SKU") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (filteredBoxes.isEmpty()) {
                        Text("Ni najdenih mešanih škatel.", color = SkladisceColors.TextMuted, modifier = Modifier.padding(16.dp))
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredBoxes, key = { it.boxId }) { box ->
                                SkladisceCard(containerColor = SkladisceColors.BgPrimary) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text("Škatla #${box.boxNumber}", color = SkladisceColors.Primary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                                                StatusPill("${box.totalItems} kos", SkladisceColors.Success, SkladisceColors.SuccessBg)
                                            }
                                            Button(
                                                onClick = { viewModel.exportAndOpenMixedBox6x6(box) },
                                                colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.PrimaryDark),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Icon(Icons.Default.Print, null, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("6×6 nalepka", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Text(
                                            "${warehouseLabel(box.warehouse)} • Regal: ${box.shelf}",
                                            color = SkladisceColors.TextMuted,
                                            fontSize = 11.5.sp
                                        )
                                        box.items.forEach { it ->
                                            Text("  • ${it.sku} (${it.name.take(28)}) × ${it.qty}", color = SkladisceColors.TextMain, fontSize = 11.5.sp)
                                        }
                                        TextButton(
                                            onClick = { viewModel.unpackMixedBox(box.boxId) },
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("Razpakiraj škatlo na posamične kose", color = SkladisceColors.Danger, fontSize = 11.5.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = boxNumText,
                                onValueChange = { boxNumText = it },
                                label = { Text("Številka škatle") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = {
                                scope.launch { boxNumText = viewModel.repo.nextMixedBoxNumber() }
                            }) { Text("Samodejno", fontSize = 12.sp) }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("zgornje" to "Zgornje (Soft)", "spodnje" to "Spodnje").forEach { (id, lbl) ->
                                FilterChip(
                                    selected = packWarehouse == id,
                                    onClick = { packWarehouse = id },
                                    label = { Text(lbl, fontSize = 11.5.sp) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = packShelf,
                            onValueChange = { packShelf = it },
                            label = { Text("Regal odlaganja") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        HorizontalDivider(color = SkladisceColors.Border)
                        Text("Dodaj artikle v škatlo:", color = SkladisceColors.Primary, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = addSku,
                                onValueChange = { addSku = it },
                                label = { Text("SKU") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1.2f)
                            )
                            Button(
                                onClick = { viewModel.openQuickScanner(QuickScannerTarget.MIXED_BOX_PACK) },
                                colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.BgCardElevated),
                                contentPadding = PaddingValues(10.dp),
                                modifier = Modifier.height(50.dp)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Skeniraj", tint = SkladisceColors.Primary)
                            }
                            OutlinedTextField(
                                value = addQty,
                                onValueChange = { addQty = it.filter(Char::isDigit) },
                                label = { Text("Kos") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(0.6f)
                            )
                            Button(
                                onClick = {
                                    viewModel.addMixedDraftItem(addSku, addQty.toIntOrNull() ?: 1)
                                    addSku = ""
                                    addQty = "1"
                                },
                                modifier = Modifier.height(50.dp)
                            ) { Text("+", fontWeight = FontWeight.ExtraBold) }
                        }

                        if (draftItems.isNotEmpty()) {
                            SkladisceCard(containerColor = SkladisceColors.BgPrimary) {
                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    draftItems.forEach { d ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text("${d.sku} (${d.name.take(22)}) • ${d.qty}x", color = SkladisceColors.TextMain, fontSize = 12.sp)
                                            IconButton(onClick = { viewModel.removeMixedDraftItem(d.sku) }, modifier = Modifier.size(28.dp)) {
                                                Icon(Icons.Default.Close, contentDescription = "Odstrani", tint = SkladisceColors.Danger, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = packNotes,
                            onValueChange = { packNotes = it },
                            label = { Text("Opombe (opcijsko)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = { viewModel.saveAndPrintMixedBox(boxNumText, packWarehouse, packShelf, packNotes) },
                            enabled = draftItems.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Success),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Print, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Shrani in natisni 6×6 nalepko", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
