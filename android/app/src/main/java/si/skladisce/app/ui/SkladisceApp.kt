package si.skladisce.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import si.skladisce.app.ble.BleMeshState
import si.skladisce.app.ui.modals.AddEditItemDialog
import si.skladisce.app.ui.modals.BleMeshDialog
import si.skladisce.app.ui.modals.ConsolidationDialog
import si.skladisce.app.ui.modals.MixedBoxesDialog
import si.skladisce.app.ui.modals.MoveStockDialog
import si.skladisce.app.ui.modals.QuickCameraScannerDialog
import si.skladisce.app.ui.modals.QuickStockChangeDialog
import si.skladisce.app.ui.modals.ReceiptDispatchDialog
import si.skladisce.app.ui.modals.ReceivingArchiveDialog
import si.skladisce.app.ui.modals.ReceivingBatchDialog
import si.skladisce.app.ui.screens.AuditScreen
import si.skladisce.app.ui.screens.HistoryScreen
import si.skladisce.app.ui.screens.InventoryScreen
import si.skladisce.app.ui.screens.ReplenishmentScreen
import si.skladisce.app.ui.screens.ScannerScreen
import si.skladisce.app.ui.screens.SettingsScreen
import si.skladisce.app.ui.theme.SkladisceColors

@Composable
fun SkladisceApp(viewModel: MainViewModel) {
    val currentTab by viewModel.currentTab.collectAsState()
    val syncHeader by viewModel.syncHeader.collectAsState()
    val bleStatus by viewModel.bleStatus.collectAsState()
    val snackbarMsg by viewModel.snackbarMessage.collectAsState()
    val warehouses by viewModel.warehouses.collectAsState()
    val scatteredItems by viewModel.scatteredItems.collectAsState()
    val activeReceiving by viewModel.activeReceivingSession.collectAsState()
    val archivedReceiving by viewModel.archivedReceivingSessions.collectAsState()

    val addEditModal by viewModel.addEditModal.collectAsState()
    val quickStockModal by viewModel.quickStockModal.collectAsState()
    val moveStockItem by viewModel.moveStockItem.collectAsState()
    val showConsolidation by viewModel.showConsolidationModal.collectAsState()
    val showReceivingBatch by viewModel.showReceivingBatchModal.collectAsState()
    val showReceivingArchive by viewModel.showReceivingArchiveModal.collectAsState()
    val dispatchModal by viewModel.dispatchModal.collectAsState()
    val showMixedBoxes by viewModel.mixedBoxesModalOpen.collectAsState()
    val showBleModal by viewModel.showBleModal.collectAsState()
    val quickScannerTarget by viewModel.quickScannerTarget.collectAsState()
    val quickScannerContinuous by viewModel.quickScannerContinuous.collectAsState()
    val pendingImportJson by viewModel.pendingBackupImportJson.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMsg) {
        val msg = snackbarMsg
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = SkladisceColors.BgPrimary,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Surface(
                color = SkladisceColors.BgHeader,
                border = BorderStroke(0.5.dp, SkladisceColors.Border)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Logo + Ime
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFF2563EB)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Inventory2, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            text = "Skladišče",
                            color = SkladisceColors.TextMain,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // BLE Mesh gumb + Status offline/lokalno
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val bleDotColor = when (bleStatus.state) {
                            BleMeshState.OFF, BleMeshState.ERROR -> SkladisceColors.Danger
                            BleMeshState.SYNCING, BleMeshState.MANUAL_SENDING, BleMeshState.MANUAL_RECEIVING -> SkladisceColors.Primary
                            BleMeshState.READY, BleMeshState.SYNCED -> SkladisceColors.Success
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(SkladisceColors.BgCardElevated)
                                .clickable { viewModel.openBleModal() }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(bleDotColor))
                            Icon(Icons.Default.Bluetooth, contentDescription = null, tint = SkladisceColors.Primary, modifier = Modifier.size(13.dp))
                            Text("Mesh ${syncHeader.hashHex}", color = SkladisceColors.TextMain, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(SkladisceColors.SuccessBg)
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(SkladisceColors.Success))
                            Text("Native", color = SkladisceColors.Success, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = SkladisceColors.BgHeader,
                tonalElevation = 0.dp,
                modifier = Modifier.navigationBarsPadding()
            ) {
                val navItems: List<Triple<AppTab, ImageVector, String>> = listOf(
                    Triple(AppTab.INVENTORY, Icons.Default.Inventory2, "Zaloga"),
                    Triple(AppTab.REPLENISHMENT, Icons.Default.ShoppingCart, "Police"),
                    Triple(AppTab.SCANNER, Icons.Default.QrCodeScanner, "Škatle"),
                    Triple(AppTab.AUDIT, Icons.Default.FactCheck, "Inventura"),
                    Triple(AppTab.HISTORY, Icons.Default.History, "Premiki"),
                    Triple(AppTab.SETTINGS, Icons.Default.Settings, "Nastavitve")
                )
                navItems.forEach { (tab, icon, label) ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        icon = { Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp)) },
                        label = { Text(label, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Clip) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SkladisceColors.Primary,
                            selectedTextColor = SkladisceColors.Primary,
                            unselectedIconColor = SkladisceColors.TextMuted,
                            unselectedTextColor = SkladisceColors.TextMuted,
                            indicatorColor = Color(0x2238BDF8)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                AppTab.INVENTORY -> InventoryScreen(viewModel)
                AppTab.REPLENISHMENT -> ReplenishmentScreen(viewModel)
                AppTab.SCANNER -> ScannerScreen(viewModel)
                AppTab.AUDIT -> AuditScreen(viewModel)
                AppTab.HISTORY -> HistoryScreen(viewModel)
                AppTab.SETTINGS -> SettingsScreen(viewModel)
            }
        }
    }

    // --- HOST MODALNIH OKEN IN HITREGA KAMERSKEGA SKENERJA ---
    addEditModal?.let { req ->
        AddEditItemDialog(
            request = req,
            warehouses = warehouses.map { it.id to it.name },
            onGenerateSku = { viewModel.generateRandom7DigitSku() },
            onDismiss = { viewModel.closeAddEditModal() },
            onSave = { isEdit, sku, name, cat, bt, minQ, sc, sl, ct, pwh, unit, notes, boxes ->
                viewModel.saveAddEditItem(isEdit, sku, name, cat, bt, minQ, sc, sl, ct, pwh, unit, notes, boxes)
            },
            onDelete = { viewModel.deleteItem(it) }
        )
    }

    quickStockModal?.let { req ->
        QuickStockChangeDialog(
            req = req,
            onDismiss = { viewModel.closeQuickStockChange() },
            onConfirm = { boxNum, shelf, amount, note ->
                viewModel.confirmQuickStockChange(req.item, req.isReceive, boxNum, shelf, amount, note)
            }
        )
    }

    moveStockItem?.let { item ->
        MoveStockDialog(
            item = item,
            onDismiss = { viewModel.closeMoveStock() },
            onConfirm = { boxNum, fromShelf, toShelf, amount, note ->
                viewModel.confirmMoveStock(item.sku, boxNum, fromShelf, toShelf, amount, note)
            }
        )
    }

    if (showConsolidation) {
        ConsolidationDialog(
            scattered = scatteredItems,
            onDismiss = { viewModel.closeConsolidationModal() },
            onExportPdf = { viewModel.exportConsolidationPdf() },
            onQuickConsolidate = { sku, target -> viewModel.quickConsolidateItem(sku, target) }
        )
    }

    if (showReceivingBatch && activeReceiving != null) {
        ReceivingBatchDialog(
            session = activeReceiving!!,
            onDismiss = { viewModel.closeReceivingBatchModal() },
            onExportPdf = { viewModel.exportReceivingPdf(activeReceiving!!) },
            onFinish = { viewModel.finishReceivingSession() }
        )
    }

    if (showReceivingArchive) {
        ReceivingArchiveDialog(
            sessions = archivedReceiving,
            onDismiss = { viewModel.closeReceivingArchiveModal() },
            onExportPdf = { viewModel.exportReceivingPdf(it) },
            onDelete = { viewModel.deleteArchivedReceivingSession(it) }
        )
    }

    dispatchModal?.let { state ->
        ReceiptDispatchDialog(
            state = state,
            onToggleBoxPicked = { sku, boxNum -> viewModel.toggleDispatchBoxPicked(sku, boxNum) },
            onDismiss = { viewModel.closeReceiptDispatch() },
            onFinish = { viewModel.finishReceiptDispatch() }
        )
    }

    if (showMixedBoxes) {
        MixedBoxesDialog(viewModel = viewModel)
    }

    if (showBleModal) {
        BleMeshDialog(
            syncHeader = syncHeader,
            bleStatus = bleStatus,
            onDismiss = { viewModel.closeBleModal() },
            onForceSync = { viewModel.forceBleSync() },
            onManualSend = { viewModel.startBleManualSend() },
            onManualReceive = { viewModel.startBleManualReceive() },
            onCancelManual = { viewModel.cancelBleManual() }
        )
    }

    if (quickScannerTarget != QuickScannerTarget.NONE) {
        QuickCameraScannerDialog(
            target = quickScannerTarget,
            continuousMode = quickScannerContinuous,
            onToggleContinuous = { viewModel.toggleQuickScannerContinuous() },
            onDetectedCode = { viewModel.onQuickScannerDetected(it) },
            onDismiss = { viewModel.closeQuickScanner() }
        )
    }

    if (pendingImportJson != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPendingBackupImport() },
            containerColor = SkladisceColors.BgCard,
            title = { Text("Uvoz varnostne kopije (JSON)?", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold) },
            text = { Text("Združim prejete podatke o zalogah in dnevnikih z lokalno SQLite bazo?", color = SkladisceColors.TextMuted) },
            confirmButton = {
                Button(onClick = { viewModel.confirmPendingBackupImport() }) {
                    Text("Uvozi in združi")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPendingBackupImport() }) { Text("Prekliči") }
            }
        )
    }
}
