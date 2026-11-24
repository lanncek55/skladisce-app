package si.skladisce.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import si.skladisce.app.data.model.AuditEntry
import si.skladisce.app.ui.MainViewModel
import si.skladisce.app.ui.QuickScannerTarget
import si.skladisce.app.ui.theme.SkladisceCard
import si.skladisce.app.ui.theme.SkladisceColors
import si.skladisce.app.ui.theme.StatTile
import si.skladisce.app.ui.theme.StatusPill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(viewModel: MainViewModel) {
    val entries by viewModel.auditEntries.collectAsState()
    val shelfFilter by viewModel.auditShelfFilter.collectAsState()
    val itemFilter by viewModel.auditItemFilter.collectAsState()
    val scanInput by viewModel.auditScanInput.collectAsState()

    val shelves = remember(entries) {
        listOf("all") + entries.map { it.shelf }.distinct().sorted()
    }

    val filteredByShelf = remember(entries, shelfFilter) {
        entries.filter { shelfFilter == "all" || it.shelf.equals(shelfFilter, ignoreCase = true) }
    }

    val displayed = remember(filteredByShelf, itemFilter) {
        when (itemFilter) {
            "diff" -> filteredByShelf.filter { it.isCounted && it.diff != 0 }
            "uncounted" -> filteredByShelf.filter { !it.isCounted }
            "matched" -> filteredByShelf.filter { it.isCounted && it.diff == 0 }
            else -> filteredByShelf
        }
    }

    val totalPos = filteredByShelf.size
    val totalCountedQty = filteredByShelf.filter { it.isCounted }.sumOf { it.countedQty }
    val matchedCount = filteredByShelf.count { it.isCounted && it.diff == 0 }
    val diffCount = filteredByShelf.count { it.isCounted && it.diff != 0 }

    var dropdownExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Glava in Ponastavi
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Inventura po regalih", color = SkladisceColors.TextMain, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Štetje in uskladitev knjižnega stanja", color = SkladisceColors.TextMuted, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.resetAuditSession() },
                        border = BorderStroke(1.dp, SkladisceColors.Danger.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp), tint = SkladisceColors.Danger)
                        Spacer(Modifier.width(4.dp))
                        Text("Ponastavi", color = SkladisceColors.Danger, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 4 statistične kartice
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatTile("Pozicij", "$totalPos", SkladisceColors.Primary, Modifier.weight(1f))
                    StatTile("Prešteto", "$totalCountedQty", SkladisceColors.TextMain, Modifier.weight(1f))
                    StatTile("Usklajeno", "$matchedCount", SkladisceColors.Success, Modifier.weight(1f))
                    StatTile("Odstopanja", "$diffCount", if (diffCount > 0) SkladisceColors.Danger else SkladisceColors.TextMuted, Modifier.weight(1f))
                }
            }

            // Izbira regala + vnos šifre + skener
            item {
                SkladisceCard(Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExposedDropdownMenuBox(
                            expanded = dropdownExpanded,
                            onExpandedChange = { dropdownExpanded = !dropdownExpanded }
                        ) {
                            OutlinedTextField(
                                value = if (shelfFilter == "all") "Vsi regali in police" else shelfFilter,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Izberi regal za popis") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false }
                            ) {
                                shelves.forEach { sh ->
                                    DropdownMenuItem(
                                        text = { Text(if (sh == "all") "Vsi regali in police" else sh) },
                                        onClick = {
                                            viewModel.setAuditShelfFilter(sh)
                                            dropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = scanInput,
                                onValueChange = { viewModel.setAuditScanInput(it) },
                                label = { Text("Šifra artikla (SKU)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { viewModel.openQuickScanner(QuickScannerTarget.AUDIT, continuous = true) },
                                colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.PrimaryDark),
                                contentPadding = PaddingValues(12.dp),
                                modifier = Modifier.height(52.dp)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Zaporedni skener")
                            }
                            Button(
                                onClick = { viewModel.incrementAuditBySku(scanInput, 1) },
                                colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Success),
                                modifier = Modifier.height(52.dp)
                            ) {
                                Text("Štej (+1)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Filtri postavk
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "all" to "Vse ($totalPos)",
                        "diff" to "Samo odstopanja ($diffCount)",
                        "uncounted" to "Neprešteto (${filteredByShelf.count { !it.isCounted }})",
                        "matched" to "Usklajeno ($matchedCount)"
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = itemFilter == id,
                            onClick = { viewModel.setAuditItemFilter(id) },
                            label = { Text(label, fontSize = 11.5.sp) }
                        )
                    }
                }
            }

            items(displayed, key = { it.key }) { entry ->
                AuditEntryRowCard(
                    entry = entry,
                    onCountChange = { viewModel.updateAuditEntryCount(entry.key, it) },
                    onMatchBook = { viewModel.matchAuditEntryToBook(entry.key) }
                )
            }
        }

        // Spodnja pripeta akcijska vrstica
        Surface(
            color = SkladisceColors.BgHeader,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, SkladisceColors.Border),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.exportAuditReportPdf() },
                    modifier = Modifier.weight(0.9f)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp), tint = SkladisceColors.Primary)
                    Spacer(Modifier.width(4.dp))
                    Text("PDF zapisnik", fontSize = 12.sp)
                }
                Button(
                    onClick = { viewModel.applyAuditResults() },
                    colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Success),
                    modifier = Modifier.weight(1.3f)
                ) {
                    Text("Potrdi in uskladi zalogo", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                }
            }
        }
    }
}

@Composable
private fun AuditEntryRowCard(
    entry: AuditEntry,
    onCountChange: (Int) -> Unit,
    onMatchBook: () -> Unit
) {
    val borderCol = when {
        !entry.isCounted -> SkladisceColors.Border
        entry.diff == 0 -> SkladisceColors.Success.copy(alpha = 0.55f)
        else -> SkladisceColors.Danger.copy(alpha = 0.65f)
    }

    SkladisceCard(borderColor = borderCol) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.sku, color = SkladisceColors.Primary, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                        StatusPill("Šk. ${entry.boxNum}/${entry.boxTotal}", SkladisceColors.TextMuted, SkladisceColors.BgCardElevated)
                        StatusPill(entry.shelf, SkladisceColors.Accent, Color(0x226366F1))
                    }
                    Text(entry.name, color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    Text("Knjižno stanje: ${entry.bookQty} kos", color = SkladisceColors.TextMuted, fontSize = 11.5.sp)
                }
                if (entry.isCounted) {
                    val badgeTxt = when {
                        entry.diff == 0 -> "OK (0)"
                        entry.diff > 0 -> "+${entry.diff}"
                        else -> "${entry.diff}"
                    }
                    StatusPill(
                        badgeTxt,
                        if (entry.diff == 0) SkladisceColors.Success else SkladisceColors.Danger,
                        if (entry.diff == 0) SkladisceColors.SuccessBg else SkladisceColors.DangerBg
                    )
                } else {
                    StatusPill("Neprešteto", SkladisceColors.Warning, SkladisceColors.WarningBg)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { onCountChange((if (entry.isCounted) entry.countedQty else 0) - 1) },
                        colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.BgCardElevated),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(36.dp)
                    ) { Text("−", fontWeight = FontWeight.Bold) }

                    OutlinedTextField(
                        value = if (entry.isCounted) entry.countedQty.toString() else "",
                        onValueChange = { txt ->
                            val parsed = txt.filter { it.isDigit() }.toIntOrNull() ?: 0
                            onCountChange(parsed)
                        },
                        placeholder = { Text("0") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(84.dp)
                    )

                    Button(
                        onClick = { onCountChange((if (entry.isCounted) entry.countedQty else 0) + 1) },
                        colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.BgCardElevated),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(36.dp)
                    ) { Text("+", fontWeight = FontWeight.Bold) }
                }

                OutlinedButton(
                    onClick = onMatchBook,
                    border = BorderStroke(1.dp, SkladisceColors.Success.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp), tint = SkladisceColors.Success)
                    Spacer(Modifier.width(4.dp))
                    Text("Usklajeno (${entry.bookQty})", color = SkladisceColors.Success, fontSize = 11.5.sp)
                }
            }
        }
    }
}
