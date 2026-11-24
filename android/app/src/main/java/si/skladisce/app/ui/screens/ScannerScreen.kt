package si.skladisce.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import si.skladisce.app.scanner.NativeCameraController
import si.skladisce.app.scanner.NativeCameraPreview
import si.skladisce.app.scanner.WarehouseOcrParser
import si.skladisce.app.ui.ActiveScanCardState
import si.skladisce.app.ui.MainViewModel
import si.skladisce.app.ui.theme.SkladisceCard
import si.skladisce.app.ui.theme.SkladisceColors
import si.skladisce.app.ui.theme.StatusPill

@Composable
fun ScannerScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val isAutoScan by viewModel.isAutoScanEnabled.collectAsState()
    val isStaticBusy by viewModel.isStaticScanBusy.collectAsState()
    val activeShelf by viewModel.activeShelfCode.collectAsState()
    val scanCard by viewModel.currentScanResult.collectAsState()
    val latencyMs by viewModel.lastScanLatencyMs.collectAsState()
    val warehouses by viewModel.warehouses.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    var pickIsReceipt by remember { mutableStateOf(false) }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.processGalleryUriForScanner(uri, isReceipt = pickIsReceipt)
        }
    }

    var manualSkuText by remember { mutableStateOf("") }
    var showShelfDialog by remember { mutableStateOf(false) }
    var shelfDialogInput by remember { mutableStateOf("") }
    var showReceiptChoiceDialog by remember { mutableStateOf(false) }

    val cameraController = remember { NativeCameraController() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. ZGORNJA VRSTICA AKTIVNEGA REGALA
        SkladisceCard(
            borderColor = SkladisceColors.Primary.copy(alpha = 0.45f),
            containerColor = Color(0xFF0E1B2A)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.QrCode2, contentDescription = null, tint = SkladisceColors.Primary)
                    Column {
                        Text("Aktivni regal", color = SkladisceColors.TextMuted, fontSize = 10.5.sp)
                        val shelfDisplay = remember(activeShelf) {
                            if (activeShelf.isBlank()) "Ni določen (Poskeniraj QR)"
                            else WarehouseOcrParser.parseShelfQR(activeShelf).let { "${it.code} (${it.displayName})" }
                        }
                        Text(shelfDisplay, color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    }
                }
                TextButton(onClick = {
                    shelfDialogInput = activeShelf.ifBlank { "R1-A-P1" }
                    showShelfDialog = true
                }) {
                    Text("Spremeni", color = SkladisceColors.Primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        // 2. NATIVNI CAMERAX VIZIR + 60FPS HUD SLEDILNIK
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(275.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black)
        ) {
            if (hasCameraPermission) {
                NativeCameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    controller = cameraController,
                    isAutoScanEnabled = isAutoScan && scanCard == null,
                    showIdleReticle = scanCard == null,
                    onScanEvent = { event -> viewModel.onScannerEvent(event) }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Za skeniranje je potrebno dovoljenje za kamero.", color = SkladisceColors.TextMain, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { cameraPermLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Dovoli kamero")
                    }
                }
            }

            // Zgornja HUD orodna vrstica na kameri
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isAutoScan) Color(0xCC064E3B) else Color(0xCC1E293B))
                        .clickable { viewModel.toggleAutoScan() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isAutoScan) SkladisceColors.Success else SkladisceColors.TextDim)
                    )
                    Text(
                        if (isAutoScan) "SAMODEJNO: VKLOP" else "SAMODEJNO: IZK.",
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (latencyMs > 0L) {
                        StatusPill("⚡ ${latencyMs}ms", SkladisceColors.Success, Color(0xCC0F172A))
                    }
                    SmallCamChip(
                        icon = {
                            Icon(
                                if (cameraController.isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = "Luč",
                                tint = if (cameraController.isTorchOn) Color.Black else Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        text = if (cameraController.isTorchOn) "VKLOP" else "Luč",
                        bgColor = if (cameraController.isTorchOn) SkladisceColors.Warning else Color(0xCC0F172A),
                        textColor = if (cameraController.isTorchOn) Color.Black else Color.White,
                        onClick = { cameraController.toggleTorch() }
                    )
                    SmallCamChip(
                        icon = { Icon(Icons.Default.ZoomIn, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) },
                        text = "${cameraController.zoomFactor}x",
                        bgColor = if (cameraController.zoomFactor > 1f) Color(0xCC0369A1) else Color(0xCC0F172A),
                        textColor = Color.White,
                        onClick = { cameraController.cycleZoom() }
                    )
                }
            }

            if (isStaticBusy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xAA0B0F19)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(color = SkladisceColors.Primary)
                        Text("Strojna ML Kit analiza...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }

        // 3. KONTROLNI GUMBI POD KAMERO (HD Zajem / Galerija / Izdaja račun)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    cameraController.captureHighResBitmap(
                        onCaptured = { bmp -> viewModel.processBitmapForScanner(bmp, isReceipt = false) },
                        onError = { err -> viewModel.showMessage("HD zajem ni uspel: ${err.message}") }
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.PrimaryDark),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("HD Zajem", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    pickIsReceipt = false
                    galleryLauncher.launch("image/*")
                },
                colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.BgCardElevated),
                border = BorderStroke(1.dp, SkladisceColors.Border),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp), tint = SkladisceColors.TextMain)
                Spacer(Modifier.width(4.dp))
                Text("Galerija", fontSize = 12.sp, color = SkladisceColors.TextMain)
            }
            Button(
                onClick = { showReceiptChoiceDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Warning),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                Spacer(Modifier.width(4.dp))
                Text("Račun", fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        // 4. KARTICA REZULTATA SKENIRANJA
        scanCard?.let { card ->
            ScanResultConfirmCard(
                card = card,
                warehouses = warehouses.map { it.id to it.name },
                onDismiss = { viewModel.clearScanResult() },
                onNameChange = { viewModel.updateScanCardName(it) },
                onWarehouseChange = { viewModel.updateScanCardWarehouse(it) },
                onLocationChange = { viewModel.updateScanCardLocation(it) },
                onPiecesChange = { viewModel.updateScanCardPieces(it) },
                onMultiplierChange = { viewModel.updateScanCardMultiplier(it) },
                onReceive = { viewModel.executeScannerStockChange(isReceive = true) },
                onIssue = { viewModel.executeScannerStockChange(isReceive = false) },
                onOpenFullAdd = { viewModel.openAddModalFromScanCard() }
            )
        }

        // 5. SPODNJI ROČNI VNOS 7-MESTNE ŠIFRE
        SkladisceCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = manualSkuText,
                    onValueChange = { manualSkuText = it },
                    label = { Text("Ročni vpis 7-mestne šifre") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        viewModel.manualLookupSku(manualSkuText)
                        manualSkuText = ""
                    },
                    modifier = Modifier.height(52.dp)
                ) {
                    Text("Najdi", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showShelfDialog) {
        AlertDialog(
            onDismissRequest = { showShelfDialog = false },
            containerColor = SkladisceColors.BgCard,
            title = { Text("Spremeni aktivni regal", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = shelfDialogInput,
                    onValueChange = { shelfDialogInput = it },
                    label = { Text("Oznaka regala (npr. R1-A-P1 ali SZ-P2)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.setManualActiveShelf(shelfDialogInput)
                    showShelfDialog = false
                }) {
                    Text("Nastavi")
                }
            },
            dismissButton = {
                TextButton(onClick = { showShelfDialog = false }) { Text("Prekliči") }
            }
        )
    }

    if (showReceiptChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showReceiptChoiceDialog = false },
            containerColor = SkladisceColors.BgCard,
            title = { Text("Izdaja blaga po računu", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold) },
            text = { Text("Izberite vir računa za vodenje po regalih:", color = SkladisceColors.TextMuted) },
            confirmButton = {
                Button(onClick = {
                    showReceiptChoiceDialog = false
                    cameraController.captureHighResBitmap(
                        onCaptured = { bmp -> viewModel.processBitmapForScanner(bmp, isReceipt = true) },
                        onError = { err -> viewModel.showMessage("Napaka zajema: ${err.message}") }
                    )
                }) { Text("📸 Slikaj račun") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        showReceiptChoiceDialog = false
                        pickIsReceipt = true
                        galleryLauncher.launch("image/*")
                    }) { Text("Galerija") }
                    TextButton(onClick = {
                        showReceiptChoiceDialog = false
                        viewModel.openSampleReceiptDispatch()
                    }) { Text("Vzorčni račun") }
                }
            }
        )
    }
}

@Composable
private fun ScanResultConfirmCard(
    card: ActiveScanCardState,
    warehouses: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onWarehouseChange: (String) -> Unit,
    onLocationChange: (String) -> Unit,
    onPiecesChange: (Int) -> Unit,
    onMultiplierChange: (Int) -> Unit,
    onReceive: () -> Unit,
    onIssue: () -> Unit,
    onOpenFullAdd: () -> Unit
) {
    val totalDelta = card.multiplier * card.piecesPerBox

    SkladisceCard(
        borderColor = SkladisceColors.Primary,
        containerColor = Color(0xFF101C30),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("ŠIFRA ARTIKLA", color = SkladisceColors.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(card.sku, color = SkladisceColors.Primary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                        StatusPill("Box ${card.boxCurrent}/${card.boxTotal}", SkladisceColors.Primary, Color(0x2238BDF8))
                        if (card.latencyMs > 0L) {
                            StatusPill("${card.latencyMs}ms", SkladisceColors.Success, SkladisceColors.SuccessBg)
                        }
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Zapri", tint = SkladisceColors.TextMuted)
                }
            }

            OutlinedTextField(
                value = card.nameInput,
                onValueChange = onNameChange,
                label = { Text(if (card.existingItem != null) "Naziv artikla (v bazi)" else "Naziv artikla (prebrano s škatle)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Pametno priporočilo regala iz sorodne škatle
            if (card.suggestedShelf != null && card.suggestedReason != null) {
                SkladisceCard(
                    borderColor = SkladisceColors.Success,
                    containerColor = Color(0x2010B981)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Priporočeni regal: ${card.suggestedShelf}", color = SkladisceColors.Success, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                            Text(card.suggestedReason, color = SkladisceColors.TextMain, fontSize = 11.sp)
                        }
                        Button(
                            onClick = { onLocationChange(card.suggestedShelf) },
                            colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Success),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("Izberi", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Razčlenitev obstoječe zaloge
            card.existingItem?.let { ex ->
                StatusPill(
                    "Na zalogi: ${ex.completeSets} ${ex.unit} (${ex.primaryShelf})",
                    SkladisceColors.Success,
                    SkladisceColors.SuccessBg
                )
            }

            // Izbira skladišča
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                warehouses.forEach { (id, name) ->
                    FilterChip(
                        selected = card.selectedWarehouse == id,
                        onClick = { onWarehouseChange(id) },
                        label = { Text(name, fontSize = 11.5.sp) }
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = card.locationInput,
                    onValueChange = onLocationChange,
                    label = { Text("Lokacija Škatle ${card.boxCurrent}/${card.boxTotal}") },
                    singleLine = true,
                    modifier = Modifier.weight(1.4f)
                )
                OutlinedTextField(
                    value = card.piecesPerBox.toString(),
                    onValueChange = { onPiecesChange(it.filter(Char::isDigit).toIntOrNull() ?: 1) },
                    label = { Text("Kosov/šk.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(0.7f)
                )
            }

            // Množilnik škatel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { if (card.multiplier > 1) onMultiplierChange(card.multiplier - 1) },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(34.dp)
                    ) { Text("−", fontWeight = FontWeight.Bold) }
                    Text("${card.multiplier}", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    OutlinedButton(
                        onClick = { onMultiplierChange(card.multiplier + 1) },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(34.dp)
                    ) { Text("+", fontWeight = FontWeight.Bold) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1, 2, 5, 10).forEach { mult ->
                        FilterChip(
                            selected = card.multiplier == mult,
                            onClick = { onMultiplierChange(mult) },
                            label = { Text("${mult}x", fontSize = 11.sp) }
                        )
                    }
                }
            }

            // Akcijska gumba Prevzem / Izdaja
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onReceive,
                    colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Primary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PREVZEMI (+$totalDelta)", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Color(0xFF042F2E))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color(0xFF042F2E))
                            Text("Tipka Glasnost GOR", fontSize = 10.sp, color = Color(0xFF042F2E))
                        }
                    }
                }
                OutlinedButton(
                    onClick = onIssue,
                    border = BorderStroke(1.dp, SkladisceColors.Border),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(0.85f).height(52.dp)
                ) {
                    Text("IZDAJ (−$totalDelta)", color = SkladisceColors.Danger, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            if (card.existingItem == null) {
                OutlinedButton(
                    onClick = onOpenFullAdd,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, SkladisceColors.Primary.copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Odpri celoten obrazec za nov artikel", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SmallCamChip(
    icon: @Composable () -> Unit,
    text: String,
    bgColor: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        icon()
        Text(text, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
