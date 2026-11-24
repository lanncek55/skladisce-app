package si.skladisce.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import si.skladisce.app.SkladisceApplication
import si.skladisce.app.ble.BleSyncEvent
import si.skladisce.app.ble.BleSyncStatus
import si.skladisce.app.data.SyncHeaderInfo
import si.skladisce.app.data.db.MovementLogEntity
import si.skladisce.app.data.db.ReplenishmentEntity
import si.skladisce.app.data.db.WarehouseEntity
import si.skladisce.app.data.model.AuditEntry
import si.skladisce.app.data.model.DispatchBoxPick
import si.skladisce.app.data.model.DispatchItemState
import si.skladisce.app.data.model.InventoryItem
import si.skladisce.app.data.model.MixedBoxItem
import si.skladisce.app.data.model.MixedBoxWithItems
import si.skladisce.app.data.model.ReceivingSessionModel
import si.skladisce.app.data.model.ScatteredItem
import si.skladisce.app.data.model.ShelfLocEntry
import si.skladisce.app.data.model.WarehouseBusinessLogic
import si.skladisce.app.export.PdfReportGenerator
import si.skladisce.app.scanner.CameraOcrAnalyzer
import si.skladisce.app.scanner.ReceiptItem
import si.skladisce.app.scanner.ReceiptScanResult
import si.skladisce.app.scanner.ScanEvent
import si.skladisce.app.scanner.WarehouseOcrParser
import si.skladisce.app.scanner.WarehouseScanResult
import kotlin.math.max
import kotlin.random.Random

enum class AppTab(val label: String) {
    INVENTORY("Zaloga"),
    REPLENISHMENT("Police"),
    SCANNER("Škatle"),
    AUDIT("Inventura"),
    HISTORY("Premiki"),
    SETTINGS("Nastavitve")
}

enum class QuickScannerTarget {
    NONE,
    INVENTORY_SEARCH,
    REPLENISHMENT,
    AUDIT,
    MIXED_BOX_PACK
}

data class ActiveScanCardState(
    val sku: String,
    val primarySku: String,
    val secondarySku: String,
    val barcode: String,
    val ocrName: String,
    val nameInput: String,
    val boxCurrent: Int,
    val boxTotal: Int,
    val piecesPerBox: Int,
    val existingItem: InventoryItem?,
    val selectedWarehouse: String,
    val locationInput: String,
    val suggestedShelf: String?,
    val suggestedReason: String?,
    val multiplier: Int = 1,
    val mode: String = "ocr_0",
    val latencyMs: Long = 0L
)

data class AddEditModalRequest(
    val existingItem: InventoryItem? = null,
    val prefillSku: String = "",
    val prefillName: String = "",
    val prefillBoxTotal: Int = 1,
    val prefillWarehouse: String = "spodnje",
    val prefillLocation: String = ""
)

data class QuickStockChangeRequest(
    val item: InventoryItem,
    val isReceive: Boolean
)

data class ReceiptDispatchUiState(
    val receiptNo: String,
    val receiptDate: String,
    val barcode: String,
    val items: List<DispatchItemState>
) {
    val allCompleted: Boolean get() = items.isNotEmpty() && items.all { it.isAllPicked }
    val pickedCount: Int get() = items.count { it.isAllPicked }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SkladisceApplication
    val repo = app.repository
    val ble = app.bleSyncManager
    val feedback = app.feedback

    // Glavni podatkovni tokovi iz Room baze
    val allItems: StateFlow<List<InventoryItem>> = repo.inventoryItems
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val warehouses: StateFlow<List<WarehouseEntity>> = repo.warehouses
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val mixedBoxes: StateFlow<List<MixedBoxWithItems>> = repo.mixedBoxes
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val replenishments: StateFlow<List<ReplenishmentEntity>> = repo.replenishments
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val logs: StateFlow<List<MovementLogEntity>> = repo.logs
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeReceivingSession: StateFlow<ReceivingSessionModel?> = repo.activeReceivingSession
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val archivedReceivingSessions: StateFlow<List<ReceivingSessionModel>> = repo.archivedReceivingSessions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val syncHeader: StateFlow<SyncHeaderInfo> = repo.syncHeader
    val bleStatus: StateFlow<BleSyncStatus> = ble.status

    // Navigacija in sporočila
    private val _currentTab = MutableStateFlow(AppTab.INVENTORY)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // Filtri na zavihku Zaloga
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _warehouseFilter = MutableStateFlow("all")
    val warehouseFilter: StateFlow<String> = _warehouseFilter.asStateFlow()

    private val _stockFilter = MutableStateFlow("all") // "all" ali "low"
    val stockFilter: StateFlow<String> = _stockFilter.asStateFlow()

    private val _categoryFilter = MutableStateFlow("all")
    val categoryFilter: StateFlow<String> = _categoryFilter.asStateFlow()

    val scatteredItems: StateFlow<List<ScatteredItem>> = combine(allItems) { (list) ->
        WarehouseBusinessLogic.findScatteredItems(list)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val filteredInventory: StateFlow<List<InventoryItem>> = combine(
        allItems, _searchQuery, _warehouseFilter, _stockFilter, _categoryFilter
    ) { items, q, wh, stockF, cat ->
        val query = q.trim().lowercase()
        items.filter { item ->
            val matchWh = when (wh) {
                "all" -> true
                "trgovina" -> item.storeQuantity > 0 || item.primaryWarehouse == "trgovina"
                "zgornje" -> item.zgornjeQuantity > 0 || item.primaryWarehouse == "zgornje" || item.categoryType == "soft"
                "spodnje" -> item.spodnjeQuantity > 0 || item.primaryWarehouse == "spodnje" || item.categoryType == "furniture"
                else -> item.breakdown.any { b -> b.locations.any { it.warehouse == wh && it.count > 0 } } || item.primaryWarehouse == wh
            }
            val matchStock = (stockF != "low") || item.isLowStock
            val matchCat = (cat == "all") || item.category.equals(cat, ignoreCase = true)
            val matchQuery = if (query.isEmpty()) true else {
                item.sku.lowercase().contains(query) ||
                    item.name.lowercase().contains(query) ||
                    item.category.lowercase().contains(query) ||
                    item.primaryShelf.lowercase().contains(query) ||
                    item.notes.lowercase().contains(query) ||
                    item.breakdown.any { b -> b.location.lowercase().contains(query) }
            }
            matchWh && matchStock && matchCat && matchQuery
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Skener (Zavihek Škatle)
    private val _isAutoScanEnabled = MutableStateFlow(true)
    val isAutoScanEnabled: StateFlow<Boolean> = _isAutoScanEnabled.asStateFlow()

    private val _isStaticScanBusy = MutableStateFlow(false)
    val isStaticScanBusy: StateFlow<Boolean> = _isStaticScanBusy.asStateFlow()

    private val _activeShelfCode = MutableStateFlow(repo.prefs.lastUsedShelf)
    val activeShelfCode: StateFlow<String> = _activeShelfCode.asStateFlow()

    private val _activeScannerWarehouse = MutableStateFlow(repo.prefs.activeScannerWarehouse)
    val activeScannerWarehouse: StateFlow<String> = _activeScannerWarehouse.asStateFlow()

    private val _currentScanResult = MutableStateFlow<ActiveScanCardState?>(null)
    val currentScanResult: StateFlow<ActiveScanCardState?> = _currentScanResult.asStateFlow()

    private val _lastScanLatencyMs = MutableStateFlow(0L)
    val lastScanLatencyMs: StateFlow<Long> = _lastScanLatencyMs.asStateFlow()

    private var lastDetectedBoxKey: String = ""
    private var lastDetectedElapsedMs: Long = 0L
    private var lastShelfScannedCode: String = ""
    private var lastShelfScannedElapsedMs: Long = 0L

    // Police & Nabiranje
    private val _repLookupInput = MutableStateFlow("")
    val repLookupInput: StateFlow<String> = _repLookupInput.asStateFlow()

    private val _repHitItem = MutableStateFlow<InventoryItem?>(null)
    val repHitItem: StateFlow<InventoryItem?> = _repHitItem.asStateFlow()

    private val _repListWarehouseFilter = MutableStateFlow("all")
    val repListWarehouseFilter: StateFlow<String> = _repListWarehouseFilter.asStateFlow()

    // Inventura
    private val _auditShelfFilter = MutableStateFlow("all")
    val auditShelfFilter: StateFlow<String> = _auditShelfFilter.asStateFlow()

    private val _auditItemFilter = MutableStateFlow("all") // "all", "diff", "uncounted", "matched"
    val auditItemFilter: StateFlow<String> = _auditItemFilter.asStateFlow()

    private val _auditEntries = MutableStateFlow<List<AuditEntry>>(emptyList())
    val auditEntries: StateFlow<List<AuditEntry>> = _auditEntries.asStateFlow()

    private val _auditScanInput = MutableStateFlow("")
    val auditScanInput: StateFlow<String> = _auditScanInput.asStateFlow()

    // Modalna okna
    private val _addEditModal = MutableStateFlow<AddEditModalRequest?>(null)
    val addEditModal: StateFlow<AddEditModalRequest?> = _addEditModal.asStateFlow()

    private val _quickStockModal = MutableStateFlow<QuickStockChangeRequest?>(null)
    val quickStockModal: StateFlow<QuickStockChangeRequest?> = _quickStockModal.asStateFlow()

    private val _moveStockItem = MutableStateFlow<InventoryItem?>(null)
    val moveStockItem: StateFlow<InventoryItem?> = _moveStockItem.asStateFlow()

    private val _showConsolidationModal = MutableStateFlow(false)
    val showConsolidationModal: StateFlow<Boolean> = _showConsolidationModal.asStateFlow()

    private val _showReceivingBatchModal = MutableStateFlow(false)
    val showReceivingBatchModal: StateFlow<Boolean> = _showReceivingBatchModal.asStateFlow()

    private val _showReceivingArchiveModal = MutableStateFlow(false)
    val showReceivingArchiveModal: StateFlow<Boolean> = _showReceivingArchiveModal.asStateFlow()

    private val _dispatchModal = MutableStateFlow<ReceiptDispatchUiState?>(null)
    val dispatchModal: StateFlow<ReceiptDispatchUiState?> = _dispatchModal.asStateFlow()

    private val _mixedBoxesModalOpen = MutableStateFlow(false)
    val mixedBoxesModalOpen: StateFlow<Boolean> = _mixedBoxesModalOpen.asStateFlow()

    private val _mixedBoxesInitialTab = MutableStateFlow(0)
    val mixedBoxesInitialTab: StateFlow<Int> = _mixedBoxesInitialTab.asStateFlow()

    private val _mixedPackDraftItems = MutableStateFlow<List<MixedBoxItem>>(emptyList())
    val mixedPackDraftItems: StateFlow<List<MixedBoxItem>> = _mixedPackDraftItems.asStateFlow()

    private val _mixedPackPendingSku = MutableStateFlow("")
    val mixedPackPendingSku: StateFlow<String> = _mixedPackPendingSku.asStateFlow()

    private val _showBleModal = MutableStateFlow(false)
    val showBleModal: StateFlow<Boolean> = _showBleModal.asStateFlow()

    private val _quickScannerTarget = MutableStateFlow(QuickScannerTarget.NONE)
    val quickScannerTarget: StateFlow<QuickScannerTarget> = _quickScannerTarget.asStateFlow()

    private val _quickScannerContinuous = MutableStateFlow(false)
    val quickScannerContinuous: StateFlow<Boolean> = _quickScannerContinuous.asStateFlow()

    private val _pendingBackupImportJson = MutableStateFlow<String?>(null)
    val pendingBackupImportJson: StateFlow<String?> = _pendingBackupImportJson.asStateFlow()

    init {
        viewModelScope.launch {
            ble.events.collectLatest { event ->
                when (event) {
                    is BleSyncEvent.AutoMerged -> {
                        feedback.playSuccessBeep()
                        feedback.vibrateSuccess()
                        showMessage("BLE Mesh: usklajeno z DEV-${event.peerId} (+${event.result.addedArticles} novih)")
                    }
                    is BleSyncEvent.ManualSent -> {
                        feedback.playSuccessBeep()
                        showMessage("Zaloga uspešno poslana preko BLE (${event.bytesSent} B).")
                    }
                    is BleSyncEvent.ManualReceived -> {
                        feedback.playSuccessBeep()
                        feedback.vibrateSuccess()
                        showMessage("BLE prejem zaključen (+${event.result.addedArticles} novih, ${event.result.updatedRecords} posodobljenih).")
                    }
                    is BleSyncEvent.Error -> showMessage(event.message)
                }
            }
        }

        // Samodejno osveži seznam inventure, ko se inventar spremeni in inventura še ni napolnjena
        viewModelScope.launch {
            allItems.collectLatest { items ->
                if (_auditEntries.value.isEmpty() && items.isNotEmpty()) {
                    rebuildAuditSession(items)
                }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _currentTab.value = tab
        if (tab == AppTab.AUDIT && _auditEntries.value.isEmpty()) {
            rebuildAuditSession(allItems.value)
        }
    }

    fun showMessage(msg: String) {
        _snackbarMessage.value = msg
    }

    fun clearMessage() {
        _snackbarMessage.value = null
    }

    // --- ZALOGA & FILTRI ---
    fun setSearchQuery(q: String) { _searchQuery.value = q }
    fun setWarehouseFilter(wh: String) { _warehouseFilter.value = wh }
    fun setStockFilter(f: String) { _stockFilter.value = f }
    fun setCategoryFilter(c: String) { _categoryFilter.value = c }

    fun openAddItemModal(prefill: AddEditModalRequest = AddEditModalRequest()) {
        _addEditModal.value = prefill
    }

    fun openEditItemModal(item: InventoryItem) {
        _addEditModal.value = AddEditModalRequest(existingItem = item)
    }

    fun closeAddEditModal() { _addEditModal.value = null }

    fun saveAddEditItem(
        isEdit: Boolean,
        sku: String,
        name: String,
        category: String,
        boxTotal: Int,
        minQuantity: Int,
        shelfCapacity: Int,
        shelfLocation: String,
        categoryType: String,
        primaryWarehouse: String,
        unit: String,
        notes: String,
        boxes: Map<Int, List<ShelfLocEntry>>
    ) {
        viewModelScope.launch {
            runCatching {
                if (isEdit) {
                    repo.updateItem(sku, name, category, boxTotal, minQuantity, shelfCapacity, shelfLocation, categoryType, primaryWarehouse, unit, notes, boxes)
                    showMessage("Artikel $sku posodobljen.")
                } else {
                    val merged = repo.addOrMergeItem(sku, name, category, boxTotal, minQuantity, shelfCapacity, shelfLocation, categoryType, primaryWarehouse, unit, notes, boxes)
                    showMessage(if (merged) "Zaloga združena z obstoječim artiklom $sku." else "Artikel $sku uspešno dodan.")
                }
                _addEditModal.value = null
                refreshCurrentScanExistingItem()
            }.onFailure { showMessage(it.message ?: "Napaka pri shranjevanju") }
        }
    }

    fun deleteItem(sku: String) {
        viewModelScope.launch {
            runCatching {
                repo.deleteItem(sku)
                _addEditModal.value = null
                showMessage("Artikel $sku izbrisan.")
            }.onFailure { showMessage(it.message ?: "Napaka pri brisanju") }
        }
    }

    fun openQuickStockChange(item: InventoryItem, isReceive: Boolean) {
        _quickStockModal.value = QuickStockChangeRequest(item, isReceive)
    }

    fun closeQuickStockChange() { _quickStockModal.value = null }

    fun confirmQuickStockChange(item: InventoryItem, isReceive: Boolean, boxNum: Int, shelf: String, amount: Int, note: String) {
        viewModelScope.launch {
            runCatching {
                val delta = if (isReceive) max(1, amount) else -max(1, amount)
                val (updated, compBanner) = repo.adjustBoxStock(
                    sku = item.sku,
                    delta = delta,
                    boxNum = boxNum,
                    shelf = shelf,
                    note = note
                )
                _quickStockModal.value = null
                showMessage(compBanner ?: "Zaloga za ${updated.sku} posodobljena (${updated.completeSets} ${updated.unit}).")
            }.onFailure { showMessage(it.message ?: "Napaka pri spremembi zaloge") }
        }
    }

    fun openMoveStock(item: InventoryItem) { _moveStockItem.value = item }
    fun closeMoveStock() { _moveStockItem.value = null }

    fun confirmMoveStock(sku: String, boxNum: Int, fromShelf: String, toShelf: String, amount: Int, note: String) {
        viewModelScope.launch {
            runCatching {
                repo.moveStock(sku, boxNum, fromShelf, toShelf, amount, note)
                _moveStockItem.value = null
                showMessage("Premestitev uspešna ($amount kos: $fromShelf ➔ $toShelf).")
            }.onFailure { showMessage(it.message ?: "Napaka pri premestitvi") }
        }
    }

    fun openConsolidationModal() { _showConsolidationModal.value = true }
    fun closeConsolidationModal() { _showConsolidationModal.value = false }

    fun quickConsolidateItem(sku: String, targetShelf: String) {
        viewModelScope.launch {
            runCatching {
                repo.consolidateItemToShelf(sku, targetShelf)
                showMessage("Škatle artikla $sku združene na regal $targetShelf.")
            }.onFailure { showMessage(it.message ?: "Napaka pri konsolidaciji") }
        }
    }

    // --- PREVZEMNA SEJA ---
    fun startReceivingSession(name: String, warehouse: String) {
        viewModelScope.launch {
            val s = repo.startReceivingSession(name, warehouse)
            showMessage("Prevzem pošiljke \"${s.name}\" je zagnan.")
        }
    }

    fun updateReceivingWarehouse(wh: String) {
        viewModelScope.launch {
            repo.updateReceivingWarehouse(wh)
            if (wh != "all") _activeScannerWarehouse.value = wh
        }
    }

    fun openReceivingBatchModal() { _showReceivingBatchModal.value = true }
    fun closeReceivingBatchModal() { _showReceivingBatchModal.value = false }

    fun finishReceivingSession() {
        viewModelScope.launch {
            val finished = repo.finishReceivingSession()
            _showReceivingBatchModal.value = false
            if (finished != null) {
                showMessage("Pošiljka \"${finished.name}\" zaključena in shranjena v arhiv.")
            }
        }
    }

    fun openReceivingArchiveModal() { _showReceivingArchiveModal.value = true }
    fun closeReceivingArchiveModal() { _showReceivingArchiveModal.value = false }

    fun deleteArchivedReceivingSession(id: String) {
        viewModelScope.launch {
            repo.deleteArchivedReceivingSession(id)
            showMessage("Pošiljka odstranjena iz arhiva.")
        }
    }

    // --- SKENER (Zavihek Škatle + CameraX) ---
    fun toggleAutoScan() {
        _isAutoScanEnabled.value = !_isAutoScanEnabled.value
    }

    fun setActiveScannerWarehouse(wh: String) {
        _activeScannerWarehouse.value = wh
        repo.prefs.activeScannerWarehouse = wh
    }

    fun setManualActiveShelf(shelfRaw: String) {
        val trimmed = shelfRaw.trim()
        if (trimmed.isEmpty()) return
        val parsed = WarehouseOcrParser.parseShelfQR(trimmed)
        _activeShelfCode.value = parsed.code
        repo.prefs.lastUsedShelf = parsed.code
        _currentScanResult.value = _currentScanResult.value?.copy(locationInput = parsed.code)
        feedback.playShelfChime()
        feedback.vibrateShort()
        showMessage("Aktivni regal nastavljen: ${parsed.displayName}")
    }

    fun clearScanResult() {
        _currentScanResult.value = null
        lastDetectedBoxKey = ""
        lastDetectedElapsedMs = 0L
    }

    fun onScannerEvent(event: ScanEvent) {
        if (_currentTab.value != AppTab.SCANNER) return
        if (_currentScanResult.value != null) return // Ko je kartica potrditve odprta, počakamo na delavca

        when (event) {
            is ScanEvent.ShelfQr -> {
                val now = SystemClock.elapsedRealtime()
                if (event.info.code == lastShelfScannedCode && now - lastShelfScannedElapsedMs < 2500L) return
                lastShelfScannedCode = event.info.code
                lastShelfScannedElapsedMs = now
                setManualActiveShelf(event.info.code)
            }
            is ScanEvent.MixedBox -> {
                feedback.playSuccessBeep()
                feedback.vibrateSuccess()
                openMixedBoxesModal(tabIndex = 0)
                showMessage("Zaznana mešana škatla #${event.boxId}")
            }
            is ScanEvent.ProductBarcode -> {
                val now = SystemClock.elapsedRealtime()
                val key = "${event.value}_b1"
                if (key == lastDetectedBoxKey && now - lastDetectedElapsedMs < 1200L) return
                lastDetectedBoxKey = key
                lastDetectedElapsedMs = now
                _lastScanLatencyMs.value = event.elapsedMs
                feedback.playSuccessBeep()
                feedback.vibrateSuccess()
                openScanResultCard(
                    WarehouseScanResult(
                        primarySku = if (event.value.length == 7 && WarehouseOcrParser.isPrimaryValid(event.value)) event.value else "",
                        barcode = event.value,
                        name = "",
                        boxCurrent = 1,
                        boxTotal = 1,
                        mode = "barcode"
                    ),
                    event.elapsedMs
                )
            }
            is ScanEvent.BoxOcr -> {
                val now = SystemClock.elapsedRealtime()
                val key = "${event.result.bestSku}_b${event.result.boxCurrent}"
                if (key == lastDetectedBoxKey && now - lastDetectedElapsedMs < 1200L) return
                lastDetectedBoxKey = key
                lastDetectedElapsedMs = now
                _lastScanLatencyMs.value = event.elapsedMs
                feedback.playSuccessBeep()
                feedback.vibrateSuccess()
                openScanResultCard(event.result, event.elapsedMs)
            }
        }
    }

    fun manualLookupSku(skuRaw: String) {
        val clean = skuRaw.trim()
        if (clean.isEmpty()) return
        openScanResultCard(
            WarehouseScanResult(
                primarySku = clean,
                name = "",
                boxCurrent = 1,
                boxTotal = 1,
                mode = "manual"
            ),
            0L
        )
    }

    private fun openScanResultCard(res: WarehouseScanResult, latencyMs: Long) {
        val lookupKey = res.bestSku
        val itemsList = allItems.value
        val existing = itemsList.firstOrNull { it.sku == lookupKey }
            ?: if (res.secondarySku.isNotEmpty()) itemsList.firstOrNull { it.sku == res.secondarySku } else null

        val boxNum = max(1, res.boxCurrent)
        val boxTotal = max(1, max(res.boxTotal, existing?.boxTotal ?: 1))
        val existingBoxLoc = existing?.boxes?.get(boxNum)?.location ?: existing?.primaryShelf

        val selectedWh = when {
            existing != null && existing.primaryWarehouse.isNotBlank() -> existing.primaryWarehouse
            existing != null && existing.categoryType == "furniture" && _activeScannerWarehouse.value == "zgornje" -> "spodnje"
            else -> _activeScannerWarehouse.value
        }

        // Pametno usmerjanje na isti regal kot sorodne škatle garniture
        var suggestedShelf: String? = null
        var suggestedReason: String? = null
        if (existing != null && boxTotal > 1) {
            val siblingLocs = existing.breakdown
                .filter { it.boxNum != boxNum && it.count > 0 && it.location != "Brez lokacije" }
            val bestSibling = siblingLocs.firstOrNull()
            if (bestSibling != null) {
                suggestedShelf = bestSibling.locations.firstOrNull { it.count > 0 }?.name ?: bestSibling.location
                suggestedReason = "Škatla ${bestSibling.boxNum}/$boxTotal je na regalu $suggestedShelf (${bestSibling.count} kos)."
            }
        }

        val currentLoc = _activeShelfCode.value.takeIf { it.isNotBlank() }
            ?: existingBoxLoc?.takeIf { it.isNotBlank() && it != "Brez lokacije" }
            ?: suggestedShelf
            ?: "A0/0"

        val resolvedName = existing?.name?.takeIf { it.isNotBlank() }
            ?: res.name.takeIf { it.isNotBlank() }
            ?: "Artikel $lookupKey"

        _currentScanResult.value = ActiveScanCardState(
            sku = lookupKey,
            primarySku = res.primarySku,
            secondarySku = res.secondarySku,
            barcode = res.barcode,
            ocrName = res.name,
            nameInput = resolvedName,
            boxCurrent = boxNum,
            boxTotal = boxTotal,
            piecesPerBox = max(1, res.quantityInBox),
            existingItem = existing,
            selectedWarehouse = selectedWh,
            locationInput = currentLoc,
            suggestedShelf = suggestedShelf,
            suggestedReason = suggestedReason,
            multiplier = 1,
            mode = res.mode,
            latencyMs = latencyMs
        )
    }

    private fun refreshCurrentScanExistingItem() {
        val cur = _currentScanResult.value ?: return
        val existing = allItems.value.firstOrNull { it.sku == cur.sku }
        if (existing != null) {
            _currentScanResult.value = cur.copy(
                existingItem = existing,
                nameInput = existing.name
            )
        }
    }

    fun updateScanCardName(name: String) {
        _currentScanResult.value = _currentScanResult.value?.copy(nameInput = name)
    }

    fun updateScanCardWarehouse(wh: String) {
        _currentScanResult.value = _currentScanResult.value?.copy(selectedWarehouse = wh)
    }

    fun updateScanCardLocation(loc: String) {
        _currentScanResult.value = _currentScanResult.value?.copy(locationInput = loc)
    }

    fun updateScanCardPieces(pieces: Int) {
        _currentScanResult.value = _currentScanResult.value?.copy(piecesPerBox = max(1, pieces))
    }

    fun updateScanCardMultiplier(mult: Int) {
        _currentScanResult.value = _currentScanResult.value?.copy(multiplier = max(1, mult))
    }

    fun executeScannerStockChange(isReceive: Boolean) {
        val card = _currentScanResult.value ?: return
        val effectiveDelta = card.multiplier * max(1, card.piecesPerBox) * (if (isReceive) 1 else -1)
        val shelf = card.locationInput.trim().ifEmpty { "A0/0" }

        viewModelScope.launch {
            runCatching {
                val (updatedItem, compBanner) = repo.adjustBoxStock(
                    sku = card.sku,
                    delta = effectiveDelta,
                    boxNum = card.boxCurrent,
                    shelf = shelf,
                    warehouse = card.selectedWarehouse,
                    note = if (isReceive) "Prevzem s skenerjem" else "Izdaja s skenerjem",
                    fallbackName = card.nameInput.trim(),
                    boxTotalIfNew = card.boxTotal
                )
                _activeShelfCode.value = shelf
                feedback.playSuccessBeep()
                feedback.vibrateShort()
                _currentScanResult.value = null
                showMessage(
                    compBanner ?: "${if (isReceive) "+" else ""}$effectiveDelta kos • ${updatedItem.name} (${updatedItem.completeSets} ${updatedItem.unit})"
                )
            }.onFailure { showMessage(it.message ?: "Napaka pri potrditvi") }
        }
    }

    fun openAddModalFromScanCard() {
        val card = _currentScanResult.value ?: return
        _addEditModal.value = AddEditModalRequest(
            prefillSku = card.sku,
            prefillName = card.nameInput,
            prefillBoxTotal = card.boxTotal,
            prefillWarehouse = card.selectedWarehouse,
            prefillLocation = card.locationInput
        )
    }

    fun onHardwareVolumeKey(isUp: Boolean): Boolean {
        if (_currentTab.value != AppTab.SCANNER || _currentScanResult.value == null) return false
        if (isUp) {
            executeScannerStockChange(isReceive = true)
        } else {
            clearScanResult()
            showMessage("Zaznava zavržena.")
        }
        return true
    }

    fun processBitmapForScanner(bitmap: Bitmap, isReceipt: Boolean = false) {
        _isStaticScanBusy.value = true
        viewModelScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val (boxRes, receiptRes) = withContext(Dispatchers.Default) {
                CameraOcrAnalyzer.analyzeStaticBitmap(bitmap, preferReceipt = isReceipt)
            }
            val elapsed = SystemClock.elapsedRealtime() - t0
            _isStaticScanBusy.value = false

            when {
                receiptRes != null -> {
                    feedback.playSuccessBeep()
                    feedback.vibrateSuccess()
                    openReceiptDispatch(receiptRes)
                }
                boxRes != null -> {
                    feedback.playSuccessBeep()
                    feedback.vibrateSuccess()
                    openScanResultCard(boxRes, elapsed)
                }
                else -> showMessage("Na sliki ni bilo zaznane veljavne etikete ali računa.")
            }
        }
    }

    fun processGalleryUriForScanner(uri: Uri, isReceipt: Boolean = false) {
        _isStaticScanBusy.value = true
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bmp != null) {
                processBitmapForScanner(bmp, isReceipt = isReceipt)
            } else {
                _isStaticScanBusy.value = false
                showMessage("Slike ni bilo mogoče prebrati.")
            }
        }
    }

    // --- IZDAJA PO RAČUNU ---
    fun openSampleReceiptDispatch() {
        val sample = ReceiptScanResult(
            receiptNo = "0519-101-264866",
            receiptDate = "09.10.26 11:04",
            barcode = "00105190910261010370",
            items = listOf(
                ReceiptItem("3618930", "Predalnik LIMFJORDEN 4 predali bela", 1, "95.00"),
                ReceiptItem("3670619", "DIVERSES", 1, "315.00")
            ),
            rawText = ""
        )
        openReceiptDispatch(sample)
    }

    fun openReceiptDispatch(receipt: ReceiptScanResult) {
        val currentInventory = allItems.value
        val dispatchItems = receipt.items.map { ri ->
            val dbItem = currentInventory.firstOrNull { it.sku == ri.sku }
            val picks = if (dbItem != null) {
                dbItem.breakdown.map { b ->
                    val activeLoc = b.locations.firstOrNull { it.count > 0 } ?: b.locations.firstOrNull()
                    DispatchBoxPick(
                        boxNum = b.boxNum,
                        boxTotal = dbItem.boxTotal,
                        shelf = activeLoc?.name ?: b.location,
                        warehouse = activeLoc?.warehouse ?: "spodnje",
                        stockAvailable = b.count,
                        picked = false
                    )
                }
            } else {
                listOf(DispatchBoxPick(1, 1, "Artikel ni v bazi", "spodnje", 0, false))
            }
            DispatchItemState(
                sku = ri.sku,
                name = dbItem?.name ?: ri.name,
                qty = ri.qty,
                price = ri.price,
                existsInDb = dbItem != null,
                boxPicks = picks
            )
        }
        _dispatchModal.value = ReceiptDispatchUiState(
            receiptNo = receipt.receiptNo,
            receiptDate = receipt.receiptDate,
            barcode = receipt.barcode,
            items = dispatchItems
        )
    }

    fun toggleDispatchBoxPicked(sku: String, boxNum: Int) {
        val cur = _dispatchModal.value ?: return
        val updatedItems = cur.items.map { it ->
            if (it.sku != sku) it else it.copy(
                boxPicks = it.boxPicks.map { bp ->
                    if (bp.boxNum == boxNum) bp.copy(picked = !bp.picked) else bp
                }
            )
        }
        _dispatchModal.value = cur.copy(items = updatedItems)
        feedback.vibrateShort()
    }

    fun closeReceiptDispatch() { _dispatchModal.value = null }

    fun finishReceiptDispatch() {
        val state = _dispatchModal.value ?: return
        viewModelScope.launch {
            runCatching {
                for (it in state.items) {
                    if (it.existsInDb) {
                        repo.issueCompleteItem(it.sku, it.qty, "Izdaja po računu ${state.receiptNo}")
                    }
                }
                _dispatchModal.value = null
                feedback.playSuccessBeep()
                showMessage("Izdaja po računu ${state.receiptNo} je zaključena.")
            }.onFailure { showMessage(it.message ?: "Napaka pri izdaji po računu") }
        }
    }

    // --- POLICE & NABIRANJE ---
    fun setRepLookupInput(sku: String) { _repLookupInput.value = sku }
    fun setRepListWarehouseFilter(wh: String) { _repListWarehouseFilter.value = wh }

    fun lookupReplenishmentSku(skuOverride: String? = null) {
        val clean = (skuOverride ?: _repLookupInput.value).trim()
        if (clean.isEmpty()) return
        _repLookupInput.value = clean
        val match = allItems.value.firstOrNull { it.sku == clean || it.name.contains(clean, ignoreCase = true) }
        _repHitItem.value = match
        if (match == null) showMessage("Artikla '$clean' ni v bazi.")
    }

    fun addToReplenishment(sku: String, cap: Int, currentShelfQty: Int, targetShelf: String) {
        viewModelScope.launch {
            runCatching {
                repo.addToReplenishment(sku, cap, currentShelfQty, targetShelf)
                _repHitItem.value = null
                _repLookupInput.value = ""
                showMessage("Artikel $sku dodan na nabiralni seznam.")
            }.onFailure { showMessage(it.message ?: "Napaka pri dodajanju") }
        }
    }

    fun completeReplenishmentItem(repId: Long) {
        viewModelScope.launch {
            runCatching {
                repo.completeReplenishmentItem(repId)
                feedback.playSuccessBeep()
                showMessage("Artikel prenešen na polico v trgovini.")
            }.onFailure { showMessage(it.message ?: "Napaka pri prenosu") }
        }
    }

    fun removeReplenishmentItem(repId: Long) {
        viewModelScope.launch { repo.removeReplenishmentItem(repId) }
    }

    fun clearCompletedReplenishments() {
        viewModelScope.launch {
            repo.clearCompletedReplenishments()
            showMessage("Opravljeni prenosi počiščeni.")
        }
    }

    // --- MEŠANE ŠKATLE ---
    fun openMixedBoxesModal(tabIndex: Int = 0) {
        _mixedBoxesInitialTab.value = tabIndex
        _mixedBoxesModalOpen.value = true
    }

    fun closeMixedBoxesModal() {
        _mixedBoxesModalOpen.value = false
    }

    fun addMixedDraftItem(sku: String, qty: Int) {
        val cleanSku = sku.trim()
        if (cleanSku.isEmpty()) return
        val dbItem = allItems.value.firstOrNull { it.sku == cleanSku }
        val cur = _mixedPackDraftItems.value.toMutableList()
        val idx = cur.indexOfFirst { it.sku == cleanSku }
        if (idx >= 0) {
            cur[idx] = cur[idx].copy(qty = cur[idx].qty + max(1, qty))
        } else {
            cur.add(MixedBoxItem(cleanSku, dbItem?.name ?: "Artikel $cleanSku", max(1, qty)))
        }
        _mixedPackDraftItems.value = cur
        _mixedPackPendingSku.value = ""
    }

    fun removeMixedDraftItem(sku: String) {
        _mixedPackDraftItems.value = _mixedPackDraftItems.value.filterNot { it.sku == sku }
    }

    fun clearMixedDraft() {
        _mixedPackDraftItems.value = emptyList()
    }

    fun saveAndPrintMixedBox(boxNumber: String, warehouse: String, shelf: String, notes: String) {
        val items = _mixedPackDraftItems.value
        viewModelScope.launch {
            runCatching {
                val boxId = repo.saveMixedBox(boxNumber, warehouse, shelf, notes, items)
                _mixedPackDraftItems.value = emptyList()
                _mixedBoxesInitialTab.value = 0
                showMessage("Mešana škatla #$boxNumber shranjena!")
                // Takoj pripravi in ponudi 6x6 nalepko za Citizen
                val savedBox = mixedBoxes.value.firstOrNull { it.boxId == boxId }
                    ?: MixedBoxWithItems(boxId, boxNumber, warehouse, shelf, notes, items.sumOf { it.qty }, items, WarehouseBusinessLogic.isoNow(), WarehouseBusinessLogic.isoNow())
                exportAndOpenMixedBox6x6(savedBox)
            }.onFailure { showMessage(it.message ?: "Napaka pri pakiranju mešane škatle") }
        }
    }

    fun unpackMixedBox(boxId: String) {
        viewModelScope.launch {
            runCatching {
                repo.unpackAndDeleteMixedBox(boxId)
                showMessage("Mešana škatla #$boxId razpakirana nazaj na polico.")
            }.onFailure { showMessage(it.message ?: "Napaka pri razpakiranju") }
        }
    }

    // --- INVENTURA ---
    fun setAuditShelfFilter(shelf: String) { _auditShelfFilter.value = shelf }
    fun setAuditItemFilter(f: String) { _auditItemFilter.value = f }
    fun setAuditScanInput(s: String) { _auditScanInput.value = s }

    fun resetAuditSession() {
        rebuildAuditSession(allItems.value)
        showMessage("Inventurni popis ponastavljen na trenutno knjižno stanje.")
    }

    private fun rebuildAuditSession(items: List<InventoryItem>) {
        val entries = mutableListOf<AuditEntry>()
        for (item in items) {
            for (b in item.breakdown) {
                for (loc in b.locations) {
                    if (loc.boxId != null) continue
                    val shelfName = loc.name.ifBlank { "Brez lokacije" }
                    entries.add(
                        AuditEntry(
                            key = "${item.sku}_b${b.boxNum}_$shelfName",
                            sku = item.sku,
                            name = item.name,
                            category = item.category,
                            boxNum = b.boxNum,
                            boxTotal = item.boxTotal,
                            shelf = shelfName,
                            warehouse = loc.warehouse,
                            bookQty = loc.count,
                            countedQty = 0,
                            isCounted = false
                        )
                    )
                }
            }
        }
        _auditEntries.value = entries
    }

    fun incrementAuditBySku(skuRaw: String, delta: Int = 1) {
        val clean = skuRaw.trim()
        if (clean.isEmpty()) return
        val shelfF = _auditShelfFilter.value
        val list = _auditEntries.value.toMutableList()
        val matchIdx = list.indexOfFirst {
            it.sku == clean && (shelfF == "all" || it.shelf.equals(shelfF, ignoreCase = true))
        }
        if (matchIdx >= 0) {
            val e = list[matchIdx]
            val next = max(0, (if (e.isCounted) e.countedQty else 0) + delta)
            list[matchIdx] = e.copy(countedQty = next, isCounted = true)
            _auditEntries.value = list
            _auditScanInput.value = ""
            feedback.playSuccessBeep()
            showMessage("Šteto: ${e.name} ($next kos)")
        } else {
            showMessage("Šifre $clean ni na izbranem regalu.")
        }
    }

    fun updateAuditEntryCount(key: String, newCount: Int) {
        _auditEntries.value = _auditEntries.value.map {
            if (it.key == key) it.copy(countedQty = max(0, newCount), isCounted = true) else it
        }
    }

    fun matchAuditEntryToBook(key: String) {
        _auditEntries.value = _auditEntries.value.map {
            if (it.key == key) it.copy(countedQty = it.bookQty, isCounted = true) else it
        }
    }

    fun applyAuditResults() {
        viewModelScope.launch {
            runCatching {
                val adj = repo.applyAuditResults(_auditEntries.value)
                rebuildAuditSession(allItems.value)
                feedback.playSuccessBeep()
                showMessage("Inventura potrjena ($adj lokacij usklajenih).")
            }.onFailure { showMessage(it.message ?: "Napaka pri potrditvi inventure") }
        }
    }

    // --- HITRI KAMERSKI SKENER (Modal za Zalogo, Police, Inventuro, Mešane škatle) ---
    fun openQuickScanner(target: QuickScannerTarget, continuous: Boolean = false) {
        _quickScannerContinuous.value = continuous
        _quickScannerTarget.value = target
    }

    fun closeQuickScanner() {
        _quickScannerTarget.value = QuickScannerTarget.NONE
    }

    fun toggleQuickScannerContinuous() {
        _quickScannerContinuous.value = !_quickScannerContinuous.value
    }

    fun onQuickScannerDetected(code: String) {
        val clean = code.trim()
        if (clean.isEmpty()) return
        feedback.playSuccessBeep()
        feedback.vibrateSuccess()

        when (_quickScannerTarget.value) {
            QuickScannerTarget.INVENTORY_SEARCH -> {
                _searchQuery.value = clean
                closeQuickScanner()
            }
            QuickScannerTarget.REPLENISHMENT -> {
                lookupReplenishmentSku(clean)
                closeQuickScanner()
            }
            QuickScannerTarget.AUDIT -> {
                if (WarehouseOcrParser.isShelfQRCode(clean)) {
                    val info = WarehouseOcrParser.parseShelfQR(clean)
                    _auditShelfFilter.value = info.code
                    showMessage("Regal inventure: ${info.code}")
                } else {
                    incrementAuditBySku(clean, 1)
                }
                if (!_quickScannerContinuous.value) {
                    closeQuickScanner()
                }
            }
            QuickScannerTarget.MIXED_BOX_PACK -> {
                _mixedPackPendingSku.value = clean
                closeQuickScanner()
            }
            QuickScannerTarget.NONE -> Unit
        }
    }

    // --- BLE MESH MODAL ---
    fun openBleModal() { _showBleModal.value = true }
    fun closeBleModal() { _showBleModal.value = false }
    fun forceBleSync() { ble.forceSyncNow() }
    fun startBleManualSend() { ble.startManualSending() }
    fun startBleManualReceive() { ble.startManualReceiving() }
    fun cancelBleManual() { ble.cancelManual() }

    // --- NASTAVITVE & IZVOZI (PDF / CSV / JSON) ---
    fun clearLogs() {
        viewModelScope.launch {
            repo.clearLogs()
            showMessage("Zgodovina premikov je počiščena.")
        }
    }

    fun clearAllItems() {
        viewModelScope.launch {
            repo.clearAllItems()
            _auditEntries.value = emptyList()
            showMessage("Vsi artikli so izbrisani.")
        }
    }

    fun loadDemoData() {
        viewModelScope.launch {
            repo.seedDemoData()
            showMessage("Vzorčni artikli uspešno naloženi.")
        }
    }

    fun deduplicateItems() {
        viewModelScope.launch {
            val count = repo.deduplicateItems()
            showMessage(if (count > 0) "Združenih $count podvojenih vnosov." else "Ni podvojenih vnosov.")
        }
    }

    fun addWarehouse(name: String) {
        viewModelScope.launch {
            runCatching {
                repo.saveWarehouse(name, isStore = false)
                showMessage("Dodano skladišče \"$name\".")
            }.onFailure { showMessage(it.message ?: "Napaka pri dodajanju skladišča") }
        }
    }

    fun deleteWarehouse(id: String) {
        viewModelScope.launch {
            runCatching {
                repo.deleteWarehouse(id)
                showMessage("Skladišče odstranjeno.")
            }.onFailure { showMessage(it.message ?: "Napaka") }
        }
    }

    fun handleIncomingBackupUri(uri: Uri) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (!content.isNullOrBlank()) {
                _pendingBackupImportJson.value = content
            } else {
                showMessage("Datoteke ni bilo mogoče prebrati.")
            }
        }
    }

    fun confirmPendingBackupImport() {
        val json = _pendingBackupImportJson.value ?: return
        _pendingBackupImportJson.value = null
        viewModelScope.launch {
            runCatching {
                val res = repo.mergeSyncPayloadJson(json)
                feedback.playSuccessBeep()
                showMessage("Uvoz uspešen: +${res.addedArticles} novih artiklov, ${res.updatedRecords} posodobljenih lokacij.")
            }.onFailure { showMessage("Napaka pri uvozu JSON: ${it.message}") }
        }
    }

    fun dismissPendingBackupImport() { _pendingBackupImportJson.value = null }

    fun exportCompleteSetsPdf() = runExport { PdfReportGenerator.exportCompleteSetsPdf(app, allItems.value) }
    fun exportIncompleteBoxesPdf() = runExport { PdfReportGenerator.exportIncompleteBoxesPdf(app, allItems.value) }
    fun exportConsolidationPdf() = runExport { PdfReportGenerator.exportConsolidationPdf(app, scatteredItems.value) }
    fun exportReplenishmentPdf() = runExport { PdfReportGenerator.exportReplenishmentPdf(app, replenishments.value) }
    fun exportAuditReportPdf() = runExport { PdfReportGenerator.exportAuditReportPdf(app, _auditEntries.value, _auditShelfFilter.value) }
    fun exportShelfQrLabelsPdf() = runExport { PdfReportGenerator.exportShelfQrLabelsPdf(app) }
    fun exportReceivingPdf(session: ReceivingSessionModel) = runExport { PdfReportGenerator.exportReceivingReportPdf(app, session) }
    fun exportAndOpenMixedBox6x6(box: MixedBoxWithItems) = runExport { PdfReportGenerator.exportCitizenMixedBoxLabel6x6Pdf(app, box) }
    fun exportInventoryCsv() = runExport { PdfReportGenerator.exportInventoryCsv(app, allItems.value) }
    fun exportBackupJson() = runExport {
        val json = repo.exportBackupJson()
        PdfReportGenerator.exportBackupJson(app, json)
    }

    private fun runExport(block: suspend () -> si.skladisce.app.export.ExportedFileResult) {
        viewModelScope.launch {
            runCatching {
                val res = withContext(Dispatchers.IO) { block() }
                showMessage("Shranjeno v Prenosi/Skladisce/${res.fileName}")
                PdfReportGenerator.launchShareOrViewIntent(app, res)
            }.onFailure {
                showMessage("Napaka pri izvozu: ${it.message}")
            }
        }
    }

    fun generateRandom7DigitSku(): String {
        val prefix = listOf("36", "37", "64").random()
        val tail = Random.nextInt(10000, 99999)
        return "$prefix$tail"
    }
}
