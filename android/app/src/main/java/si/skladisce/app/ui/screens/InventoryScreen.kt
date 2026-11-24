package si.skladisce.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import si.skladisce.app.data.model.InventoryItem
import si.skladisce.app.ui.AppTab
import si.skladisce.app.ui.MainViewModel
import si.skladisce.app.ui.QuickScannerTarget
import si.skladisce.app.ui.theme.SkladisceCard
import si.skladisce.app.ui.theme.SkladisceColors
import si.skladisce.app.ui.theme.StatTile
import si.skladisce.app.ui.theme.StatusPill
import si.skladisce.app.ui.theme.warehouseColor
import si.skladisce.app.ui.theme.warehouseLabel

@Composable
fun InventoryScreen(viewModel: MainViewModel) {
    val allItems by viewModel.allItems.collectAsState()
    val filteredItems by viewModel.filteredInventory.collectAsState()
    val warehouses by viewModel.warehouses.collectAsState()
    val mixedBoxes by viewModel.mixedBoxes.collectAsState()
    val scattered by viewModel.scatteredItems.collectAsState()
    val activeReceiving by viewModel.activeReceivingSession.collectAsState()

    val searchQuery by viewModel.searchQuery.collectAsState()
    val whFilter by viewModel.warehouseFilter.collectAsState()
    val stockFilter by viewModel.stockFilter.collectAsState()
    val catFilter by viewModel.categoryFilter.collectAsState()

    var showStartReceivingDialog by remember { mutableStateOf(false) }
    var newReceivingName by remember { mutableStateOf("") }
    var newReceivingWh by remember { mutableStateOf("spodnje") }

    val categories = remember(allItems) {
        allItems.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val totalUnits = remember(allItems) { allItems.sumOf { it.completeSets } }
    val lowCount = remember(allItems) { allItems.count { it.isLowStock } }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. PASICA PREVZEMNE POŠILJKE
            item {
                SkladisceCard(
                    borderColor = if (activeReceiving != null) SkladisceColors.Success.copy(alpha = 0.6f) else SkladisceColors.Border,
                    containerColor = if (activeReceiving != null) Color(0xFF0F2924) else SkladisceColors.BgCard
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.LocalShipping,
                                contentDescription = null,
                                tint = if (activeReceiving != null) SkladisceColors.Success else SkladisceColors.Primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (activeReceiving != null) "Prevzem: ${activeReceiving!!.name}" else "Prevzem pošiljke (tovornjak)",
                                    color = SkladisceColors.TextMain,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (activeReceiving != null) {
                                        val cnt = activeReceiving!!.entries.sumOf { it.count }
                                        "Aktiven • $cnt razloženih škatel"
                                    } else {
                                        "Ni aktivne seje razkladanja"
                                    },
                                    color = SkladisceColors.TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { viewModel.openReceivingArchiveModal() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Arhiv", color = SkladisceColors.TextMuted, fontSize = 12.sp, maxLines = 1, softWrap = false)
                            }
                            Button(
                                onClick = {
                                    if (activeReceiving != null) {
                                        viewModel.openReceivingBatchModal()
                                    } else {
                                        newReceivingName = ""
                                        showStartReceivingDialog = true
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (activeReceiving != null) SkladisceColors.Success else SkladisceColors.Primary
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(
                                    if (activeReceiving != null) "Pregled" else "Začni prevzem",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // 2. STATISTIKA ZALOGE
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatTile("Artiklov", "${allItems.size}", SkladisceColors.Primary, Modifier.weight(1f))
                    StatTile("Skupaj kos", "$totalUnits", SkladisceColors.Success, Modifier.weight(1f))
                    StatTile(
                        "Kritično",
                        "$lowCount",
                        if (lowCount > 0) SkladisceColors.Danger else SkladisceColors.TextMuted,
                        Modifier.weight(1f)
                    )
                }
            }

            // 3. HITRA ORODNA VRSTICA (Quick Bar)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionPill("➕ Nov artikel", SkladisceColors.Primary) {
                        viewModel.openAddItemModal()
                    }
                    QuickActionPill("📦 Mešane (${mixedBoxes.size})", SkladisceColors.Primary) {
                        viewModel.openMixedBoxesModal(0)
                    }
                    QuickActionPill("🛒 Police & Nabiranje", SkladisceColors.Success) {
                        viewModel.selectTab(AppTab.REPLENISHMENT)
                    }
                    QuickActionPill("📄 Popolni (PDF)", SkladisceColors.TextMain) {
                        viewModel.exportCompleteSetsPdf()
                    }
                    QuickActionPill("⚠️ Nepopolne (PDF)", SkladisceColors.Warning) {
                        viewModel.exportIncompleteBoxesPdf()
                    }
                    QuickActionPill("🔀 Konsolidacija (${scattered.size})", SkladisceColors.Accent) {
                        viewModel.openConsolidationModal()
                    }
                    QuickActionPill("🧾 Izdaja po računu", Color(0xFFFB923C)) {
                        viewModel.openSampleReceiptDispatch()
                    }
                }
            }

            // 4. FILTRI SKLADIŠČ
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = whFilter == "all",
                            onClick = { viewModel.setWarehouseFilter("all") },
                            label = { Text("Vse lokacije", fontSize = 12.sp) },
                            colors = chipColors(SkladisceColors.Primary)
                        )
                    }
                    items(warehouses, key = { it.id }) { wh ->
                        FilterChip(
                            selected = whFilter == wh.id,
                            onClick = { viewModel.setWarehouseFilter(wh.id) },
                            label = { Text(wh.name, fontSize = 12.sp) },
                            colors = chipColors(warehouseColor(wh.id))
                        )
                    }
                }
            }

            // 5. ISKALNIK + HITRI SKENER + NIZKA ZALOGA + KATEGORIJE
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Išči šifro (SKU), naziv, regal...", fontSize = 13.sp, color = SkladisceColors.TextDim) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = SkladisceColors.TextMuted) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                        Icon(Icons.Default.Close, contentDescription = "Počisti", tint = SkladisceColors.TextMuted)
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = SkladisceColors.BgCard,
                                unfocusedContainerColor = SkladisceColors.BgCard,
                                focusedBorderColor = SkladisceColors.Primary,
                                unfocusedBorderColor = SkladisceColors.Border,
                                focusedTextColor = SkladisceColors.TextMain,
                                unfocusedTextColor = SkladisceColors.TextMain
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { viewModel.openQuickScanner(QuickScannerTarget.INVENTORY_SEARCH) },
                            colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.BgCardElevated),
                            border = BorderStroke(1.dp, SkladisceColors.Border),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(14.dp),
                            modifier = Modifier.height(54.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Skeniraj iskanje", tint = SkladisceColors.Primary)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = stockFilter == "all" && catFilter == "all",
                            onClick = {
                                viewModel.setStockFilter("all")
                                viewModel.setCategoryFilter("all")
                            },
                            label = { Text("Vse (${allItems.size})", fontSize = 12.sp) },
                            colors = chipColors(SkladisceColors.Primary)
                        )
                        FilterChip(
                            selected = stockFilter == "low",
                            onClick = { viewModel.setStockFilter(if (stockFilter == "low") "all" else "low") },
                            label = { Text("Nizka zaloga ($lowCount)", fontSize = 12.sp) },
                            colors = chipColors(SkladisceColors.Danger)
                        )
                        categories.forEach { cat ->
                            FilterChip(
                                selected = catFilter == cat,
                                onClick = { viewModel.setCategoryFilter(if (catFilter == cat) "all" else cat) },
                                label = { Text(cat, fontSize = 12.sp) },
                                colors = chipColors(SkladisceColors.Accent)
                            )
                        }
                    }
                }
            }

            // 6. SEZNAM ARTIKLOV
            if (filteredItems.isEmpty()) {
                item {
                    SkladisceCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Inventory2, contentDescription = null, tint = SkladisceColors.TextDim, modifier = Modifier.size(40.dp))
                            Text("Ni najdenih artiklov", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold)
                            Text("Poskusite drug iskalni pojem ali dodajte nov artikel.", color = SkladisceColors.TextMuted, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                items(filteredItems, key = { it.sku }) { item ->
                    InventoryArticleCard(
                        item = item,
                        onEdit = { viewModel.openEditItemModal(item) },
                        onMove = { viewModel.openMoveStock(item) },
                        onDecrease = { viewModel.openQuickStockChange(item, isReceive = false) },
                        onIncrease = { viewModel.openQuickStockChange(item, isReceive = true) }
                    )
                }
            }
        }
    }

    if (showStartReceivingDialog) {
        AlertDialog(
            onDismissRequest = { showStartReceivingDialog = false },
            containerColor = SkladisceColors.BgCard,
            title = { Text("Začni nov prevzem tovornjaka", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newReceivingName,
                        onValueChange = { newReceivingName = it },
                        label = { Text("Naziv pošiljke (opcijsko)") },
                        placeholder = { Text("npr. Jutranji tovornjak Poljska") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Ciljno skladišče prevzema:", color = SkladisceColors.TextMuted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("all" to "Vsa", "spodnje" to "Pohištvo", "zgornje" to "Soft").forEach { (id, lbl) ->
                            FilterChip(
                                selected = newReceivingWh == id,
                                onClick = { newReceivingWh = id },
                                label = { Text(lbl, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showStartReceivingDialog = false
                    viewModel.startReceivingSession(newReceivingName, newReceivingWh)
                }) {
                    Text("Zaženi prevzem")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStartReceivingDialog = false }) { Text("Prekliči") }
            }
        )
    }
}

@Composable
private fun InventoryArticleCard(
    item: InventoryItem,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    val borderCol = when {
        item.hasIncomplete -> SkladisceColors.Warning.copy(alpha = 0.65f)
        item.isLowStock -> SkladisceColors.Danger.copy(alpha = 0.65f)
        else -> SkladisceColors.Border
    }

    SkladisceCard(
        borderColor = borderCol,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = item.sku,
                            color = SkladisceColors.Primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        StatusPill(
                            text = if (item.boxTotal > 1) "${item.boxTotal} škatle" else "Box 1/1",
                            textColor = if (item.boxTotal > 1) SkladisceColors.Warning else SkladisceColors.TextMuted,
                            bgColor = if (item.boxTotal > 1) SkladisceColors.WarningBg else SkladisceColors.BgCardElevated
                        )
                        if (item.isLowStock) {
                            StatusPill("NIZKA", SkladisceColors.Danger, SkladisceColors.DangerBg)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.name,
                        color = SkladisceColors.TextMain,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${item.category} • Glavni regal: ${item.primaryShelf}",
                        color = SkladisceColors.TextMuted,
                        fontSize = 11.5.sp
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${item.completeSets}",
                        color = if (item.completeSets == 0) SkladisceColors.Danger else SkladisceColors.Success,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(item.unit, color = SkladisceColors.TextMuted, fontSize = 11.sp)
                }
            }

            // Oznake razporeditve po skladiščih in policah
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (item.spodnjeQuantity > 0) {
                    StatusPill("🛋️ Spodnje: ${item.spodnjeQuantity}", SkladisceColors.Warning, SkladisceColors.WarningBg)
                }
                if (item.zgornjeQuantity > 0) {
                    StatusPill("📦 Zgornje: ${item.zgornjeQuantity}", SkladisceColors.Success, SkladisceColors.SuccessBg)
                }
                if (item.storeQuantity > 0) {
                    StatusPill("🏬 Polica: ${item.storeQuantity}", SkladisceColors.Primary, Color(0x2238BDF8))
                }
                if (item.neededForShelf > 0) {
                    StatusPill("Manjka na polici: ${item.neededForShelf}", SkladisceColors.Danger, SkladisceColors.DangerBg)
                }
                item.mixedBoxIds.forEach { boxId ->
                    StatusPill("📦 Mešana #$boxId", SkladisceColors.Accent, Color(0x266366F1))
                }
            }

            // Razčlenitev za večdelne garniture
            if (item.boxTotal > 1) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0E1525))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    item.breakdown.forEach { b ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Škatla ${b.boxNum}/${b.boxTotal}: ${b.location}",
                                color = SkladisceColors.TextMuted,
                                fontSize = 11.5.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${b.count} kos",
                                color = if (b.count == 0) SkladisceColors.Danger else SkladisceColors.TextMain,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (item.hasIncomplete && item.missingSummary.isNotBlank()) {
                        Text(
                            text = "⚠ ${item.missingSummary}",
                            color = SkladisceColors.Warning,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            HorizontalDivider(color = SkladisceColors.Border)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onMove,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, SkladisceColors.Border),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp), tint = SkladisceColors.Primary)
                    Spacer(Modifier.width(4.dp))
                    Text("Regal / Premakni", fontSize = 12.sp, color = SkladisceColors.TextMain)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onDecrease,
                        colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.DangerBg, contentColor = SkladisceColors.Danger),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("−", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Button(
                        onClick = onIncrease,
                        colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.SuccessBg, contentColor = SkladisceColors.Success),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("+", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionPill(
    label: String,
    accent: Color,
    onClick: () -> Unit
) {
    SkladisceCard(
        borderColor = accent.copy(alpha = 0.45f),
        containerColor = SkladisceColors.BgCardElevated,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun chipColors(accent: Color) = FilterChipDefaults.filterChipColors(
    containerColor = SkladisceColors.BgCard,
    labelColor = SkladisceColors.TextMuted,
    selectedContainerColor = accent.copy(alpha = 0.2f),
    selectedLabelColor = accent
)
