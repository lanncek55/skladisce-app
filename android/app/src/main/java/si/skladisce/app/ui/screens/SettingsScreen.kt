package si.skladisce.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import si.skladisce.app.ui.MainViewModel
import si.skladisce.app.ui.theme.SkladisceCard
import si.skladisce.app.ui.theme.SkladisceColors
import si.skladisce.app.ui.theme.StatusPill

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val warehouses by viewModel.warehouses.collectAsState()
    val syncHeader by viewModel.syncHeader.collectAsState()
    val bleStatus by viewModel.bleStatus.collectAsState()
    val scattered by viewModel.scatteredItems.collectAsState()

    var newWhName by remember { mutableStateOf("") }
    var showConfirmClearAll by remember { mutableStateOf(false) }

    val importJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.handleIncomingBackupUri(uri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Nastavitve, Izvozi & Lokacije", color = SkladisceColors.TextMain, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }

        // 1. BLE Mesh Sinhronizacija
        item {
            SkladisceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, tint = SkladisceColors.Primary)
                        Text("Bluetooth Mesh sinhronizacija", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold)
                    }
                    StatusPill("DEV-${syncHeader.deviceId} • ${syncHeader.hashHex}", SkladisceColors.Primary, Color(0x2238BDF8))
                    Text(bleStatus.statusMessage, color = SkladisceColors.TextMuted, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                viewModel.openBleModal()
                                viewModel.startBleManualSend()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Pošlji zalogo", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            onClick = {
                                viewModel.openBleModal()
                                viewModel.startBleManualReceive()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Prejmi zalogo", fontSize = 12.sp) }
                    }
                }
            }
        }

        // 2. Poročila in izvoz podatkov
        item {
            SkladisceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Poročila in izvoz podatkov", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.exportCompleteSetsPdf() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(16.dp), tint = SkladisceColors.Success)
                            Spacer(Modifier.width(4.dp))
                            Text("Popolni (PDF)", fontSize = 11.5.sp)
                        }
                        OutlinedButton(onClick = { viewModel.exportIncompleteBoxesPdf() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(16.dp), tint = SkladisceColors.Warning)
                            Spacer(Modifier.width(4.dp))
                            Text("Nepopolne (PDF)", fontSize = 11.5.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.openConsolidationModal() }, modifier = Modifier.weight(1f)) {
                            Text("Konsolidacija (${scattered.size})", fontSize = 11.5.sp)
                        }
                        OutlinedButton(onClick = { viewModel.openReceivingArchiveModal() }, modifier = Modifier.weight(1f)) {
                            Text("Arhiv prevzemov", fontSize = 11.5.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.exportInventoryCsv() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Izvoz CSV (Excel)", fontSize = 11.5.sp)
                        }
                        OutlinedButton(onClick = { viewModel.exportBackupJson() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Backup JSON", fontSize = 11.5.sp)
                        }
                    }
                    Button(
                        onClick = { importJsonLauncher.launch(arrayOf("application/json", "*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.BgCardElevated),
                        border = BorderStroke(1.dp, SkladisceColors.Primary.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.UploadFile, null, tint = SkladisceColors.Primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Uvozi varnostno kopijo (JSON)", color = SkladisceColors.Primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 3. QR Nalepke za 48 regalov in polic
        item {
            SkladisceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.QrCode2, null, tint = SkladisceColors.Primary)
                        Text("QR nalepke za regale in police", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold)
                    }
                    Text("A4 PDF dokument z QR kodami za vseh 48 regalov in polic (R1–R6 + stenski regali SZ/SS/SL1/SL2).", color = SkladisceColors.TextMuted, fontSize = 12.sp)
                    Button(onClick = { viewModel.exportShelfQrLabelsPdf() }, modifier = Modifier.fillMaxWidth()) {
                        Text("📄 Prenesi QR nalepke za 48 polic (PDF)", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    }
                }
            }
        }

        // 4. Skladišča in prodajalna
        item {
            SkladisceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Skladišča in prodajalna", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold)
                    warehouses.forEach { wh ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("• ${wh.name}", color = SkladisceColors.TextMain, fontSize = 13.sp)
                            if (!wh.isDefault) {
                                IconButton(onClick = { viewModel.deleteWarehouse(wh.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Izbriši", tint = SkladisceColors.Danger)
                                }
                            } else {
                                Text("Privzeto", color = SkladisceColors.TextDim, fontSize = 11.sp)
                            }
                        }
                        HorizontalDivider(color = SkladisceColors.Border)
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newWhName,
                            onValueChange = { newWhName = it },
                            label = { Text("Novo skladišče / oddelek") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            if (newWhName.isNotBlank()) {
                                viewModel.addWarehouse(newWhName)
                                newWhName = ""
                            }
                        }) { Text("+ Dodaj") }
                    }
                }
            }
        }

        // 5. Orodja baze SQLite (Room)
        item {
            SkladisceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Orodja lokalne baze (SQLite Room)", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { viewModel.deduplicateItems() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Združi podvojene zapise zaloge")
                    }
                    OutlinedButton(onClick = { viewModel.loadDemoData() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Naloži vzorčne artikle")
                    }
                    Button(
                        onClick = { showConfirmClearAll = true },
                        colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.DangerBg, contentColor = SkladisceColors.Danger),
                        border = BorderStroke(1.dp, SkladisceColors.Danger.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Izbriši vse artikle iz baze", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showConfirmClearAll) {
        AlertDialog(
            onDismissRequest = { showConfirmClearAll = false },
            containerColor = SkladisceColors.BgCard,
            title = { Text("Izbrisati celotno zalogo?", color = SkladisceColors.Danger, fontWeight = FontWeight.Bold) },
            text = { Text("To dejanje nepovratno izbriše vse artikle in zaloge na vseh regalih v bazi.", color = SkladisceColors.TextMuted) },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmClearAll = false
                        viewModel.clearAllItems()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Danger)
                ) { Text("Izbriši vse") }
            },
            dismissButton = { TextButton(onClick = { showConfirmClearAll = false }) { Text("Prekliči") } }
        )
    }
}
