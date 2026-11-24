package si.skladisce.app.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import si.skladisce.app.data.db.ArticleEntity
import si.skladisce.app.data.db.MixedBoxEntity
import si.skladisce.app.data.db.MovementLogEntity
import si.skladisce.app.data.db.ReceivingSessionEntity
import si.skladisce.app.data.db.ReplenishmentEntity
import si.skladisce.app.data.db.StockLocationEntity
import si.skladisce.app.data.db.WarehouseDao
import si.skladisce.app.data.db.WarehouseEntity
import si.skladisce.app.data.model.AuditEntry
import si.skladisce.app.data.model.BoxStockSummary
import si.skladisce.app.data.model.InventoryItem
import si.skladisce.app.data.model.MixedBoxItem
import si.skladisce.app.data.model.MixedBoxWithItems
import si.skladisce.app.data.model.ReceivingEntry
import si.skladisce.app.data.model.ReceivingSessionModel
import si.skladisce.app.data.model.ShelfLocEntry
import si.skladisce.app.data.model.SyncMergeResult
import si.skladisce.app.data.model.WarehouseBusinessLogic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class WarehouseRepository(
    private val dao: WarehouseDao,
    val prefs: SyncPrefs
) {
    private val gson: Gson = GsonBuilder().create()
    private val prettyGson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val _syncHeader = MutableStateFlow(prefs.getSyncHeaderInfo())
    val syncHeader: StateFlow<SyncHeaderInfo> = _syncHeader.asStateFlow()

    var onDatabaseMutated: ((SyncHeaderInfo) -> Unit)? = null

    val inventoryItems: Flow<List<InventoryItem>> =
        combine(dao.observeArticles(), dao.observeAllStocks()) { articles, stocks ->
            val bySku = stocks.groupBy { it.sku.trim() }
            articles.map { art ->
                WarehouseBusinessLogic.buildInventoryItem(art, bySku[art.sku.trim()].orEmpty())
            }
        }

    val warehouses: Flow<List<WarehouseEntity>> = dao.observeWarehouses().map { list ->
        list.ifEmpty { DemoCatalogSeeder.defaultWarehouses() }
    }

    val mixedBoxes: Flow<List<MixedBoxWithItems>> = dao.observeMixedBoxes().map { entities ->
        entities.map { entity -> toMixedBoxModel(entity) }
    }

    val replenishments: Flow<List<ReplenishmentEntity>> = dao.observeReplenishments()

    val logs: Flow<List<MovementLogEntity>> = dao.observeLogs()

    val activeReceivingSession: Flow<ReceivingSessionModel?> =
        dao.observeActiveReceivingSession().map { it?.let(::toReceivingModel) }

    val archivedReceivingSessions: Flow<List<ReceivingSessionModel>> =
        dao.observeArchivedReceivingSessions().map { list -> list.map(::toReceivingModel) }

    suspend fun initializeAppDatabase() {
        DemoCatalogSeeder.ensureDefaultWarehouses(dao)
        if (!prefs.isInitialSeedDone) {
            if (dao.getArticleCount() == 0) {
                DemoCatalogSeeder.seedDemoData(dao, prefs.getDeviceId())
            }
            prefs.isInitialSeedDone = true
            notifyMutated()
        }
    }

    suspend fun getItemBySku(sku: String): InventoryItem? {
        val cleanSku = sku.trim()
        if (cleanSku.isEmpty()) return null
        val art = dao.getArticle(cleanSku) ?: return null
        val stocks = dao.getStocksForSku(cleanSku)
        return WarehouseBusinessLogic.buildInventoryItem(art, stocks)
    }

    suspend fun getAllItemsSnapshot(): List<InventoryItem> {
        val articles = dao.getAllArticles()
        val stocksBySku = dao.getAllStocks().groupBy { it.sku.trim() }
        return articles.map { WarehouseBusinessLogic.buildInventoryItem(it, stocksBySku[it.sku.trim()].orEmpty()) }
    }

    suspend fun addOrMergeItem(
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
    ): Boolean {
        val cleanSku = sku.trim()
        require(cleanSku.isNotEmpty()) { "Šifra artikla (SKU) je obvezna." }
        val now = WarehouseBusinessLogic.isoNow()
        var wasMerged = false
        var addedQty = 0

        dao.runInTransaction {
            val existing = dao.getArticle(cleanSku)
            wasMerged = existing != null
            val effectiveBoxTotal = max(max(1, boxTotal), existing?.boxTotal ?: 1)
            val resolvedName = name.trim().ifEmpty { existing?.name ?: "Artikel $cleanSku" }

            val artRecord = ArticleEntity(
                sku = cleanSku,
                name = resolvedName,
                category = category.trim().ifEmpty { "Splošno" },
                boxTotal = effectiveBoxTotal,
                minQuantity = max(0, minQuantity),
                shelfCapacity = max(0, shelfCapacity),
                shelfLocation = shelfLocation.trim(),
                categoryType = categoryType.ifBlank { existing?.categoryType ?: "furniture" },
                primaryWarehouse = primaryWarehouse.ifBlank { existing?.primaryWarehouse ?: "" },
                unit = unit.trim().ifEmpty { if (effectiveBoxTotal > 1) "garnitura" else "kos" },
                notes = notes.trim(),
                lastUpdated = now
            )
            dao.upsertArticle(artRecord)

            for (b in 1..effectiveBoxTotal) {
                val locList = boxes[b].orEmpty().ifEmpty {
                    listOf(ShelfLocEntry("Brez lokacije", 0, isPrimary = true, warehouse = primaryWarehouse.ifBlank { "spodnje" }))
                }
                for ((idx, loc) in locList.withIndex()) {
                    val shelfName = loc.name.trim().ifEmpty { "Brez lokacije" }
                    val wh = loc.warehouse.ifBlank {
                        primaryWarehouse.ifBlank { WarehouseBusinessLogic.inferWarehouseFromShelf(shelfName) }
                    }
                    val stockId = WarehouseBusinessLogic.buildStockId(cleanSku, b, shelfName, loc.boxId)
                    val prev = dao.getStockById(stockId)
                    val newCount = max(0, (prev?.count ?: 0) + max(0, loc.count))
                    if (b == 1) addedQty += max(0, loc.count)
                    dao.upsertStock(
                        StockLocationEntity(
                            id = stockId,
                            sku = cleanSku,
                            boxNum = b,
                            shelf = shelfName,
                            warehouse = wh,
                            boxId = loc.boxId,
                            count = newCount,
                            isPrimary = loc.isPrimary || idx == 0,
                            lastUpdated = now
                        )
                    )
                }
            }

            val finalStocks = dao.getStocksForSku(cleanSku)
            val resultingItem = WarehouseBusinessLogic.buildInventoryItem(artRecord, finalStocks)

            dao.insertLog(
                MovementLogEntity(
                    sku = cleanSku,
                    itemName = resolvedName,
                    boxNum = 1,
                    shelf = resultingItem.primaryShelf,
                    changeType = if (wasMerged) "in" else "create",
                    changeAmount = addedQty,
                    resultingQty = resultingItem.completeSets,
                    note = if (wasMerged) "Združitev zaloge ob ponovnem vnosu" else "Začetni vnos ($effectiveBoxTotal škatel)",
                    deviceId = prefs.getDeviceId(),
                    timestamp = now
                )
            )
        }

        notifyMutated()
        return wasMerged
    }

    suspend fun updateItem(
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
        val cleanSku = sku.trim()
        require(cleanSku.isNotEmpty()) { "Šifra artikla (SKU) je obvezna." }
        val effectiveBoxTotal = max(1, boxTotal)
        val now = WarehouseBusinessLogic.isoNow()

        dao.runInTransaction {
            val artRecord = ArticleEntity(
                sku = cleanSku,
                name = name.trim().ifEmpty { "Artikel $cleanSku" },
                category = category.trim().ifEmpty { "Splošno" },
                boxTotal = effectiveBoxTotal,
                minQuantity = max(0, minQuantity),
                shelfCapacity = max(0, shelfCapacity),
                shelfLocation = shelfLocation.trim(),
                categoryType = categoryType.ifBlank { "furniture" },
                primaryWarehouse = primaryWarehouse,
                unit = unit.trim().ifEmpty { if (effectiveBoxTotal > 1) "garnitura" else "kos" },
                notes = notes.trim(),
                lastUpdated = now
            )
            dao.upsertArticle(artRecord)

            // Ohranimo morebitne vrstice iz mešanih škatel, prepišemo navadne police
            val existingStocks = dao.getStocksForSku(cleanSku)
            existingStocks.filter { it.boxId == null }.forEach { dao.deleteStockById(it.id) }

            for (b in 1..effectiveBoxTotal) {
                val locList = boxes[b].orEmpty().ifEmpty {
                    listOf(ShelfLocEntry("Brez lokacije", 0, isPrimary = true, warehouse = primaryWarehouse.ifBlank { "spodnje" }))
                }
                locList.filter { it.boxId == null }.forEachIndexed { idx, loc ->
                    val shelfName = loc.name.trim().ifEmpty { "Brez lokacije" }
                    val wh = loc.warehouse.ifBlank {
                        primaryWarehouse.ifBlank { WarehouseBusinessLogic.inferWarehouseFromShelf(shelfName) }
                    }
                    val stockId = WarehouseBusinessLogic.buildStockId(cleanSku, b, shelfName, null)
                    dao.upsertStock(
                        StockLocationEntity(
                            id = stockId,
                            sku = cleanSku,
                            boxNum = b,
                            shelf = shelfName,
                            warehouse = wh,
                            boxId = null,
                            count = max(0, loc.count),
                            isPrimary = loc.isPrimary || idx == 0,
                            lastUpdated = now
                        )
                    )
                }
            }
        }
        notifyMutated()
    }

    suspend fun deleteItem(sku: String) {
        val cleanSku = sku.trim()
        val now = WarehouseBusinessLogic.isoNow()
        dao.runInTransaction {
            val art = dao.getArticle(cleanSku)
            if (art != null) {
                dao.insertLog(
                    MovementLogEntity(
                        sku = cleanSku,
                        itemName = art.name,
                        changeType = "delete",
                        changeAmount = 0,
                        resultingQty = 0,
                        note = "Izbris artikla iz zaloge",
                        deviceId = prefs.getDeviceId(),
                        timestamp = now
                    )
                )
                dao.deleteArticle(cleanSku)
            }
            dao.deleteStocksForSku(cleanSku)
        }
        notifyMutated()
    }

    /**
     * Spremeni zalogo posamezne škatle (in po potrebi avtomatsko ustvari nov artikel ob skeniranju!).
     * Če je aktivna seja prevzema tovora in je delta > 0, samodejno zabeleži prevzem in vrne obvestilo ob kompletiranju.
     */
    suspend fun adjustBoxStock(
        sku: String,
        delta: Int,
        boxNum: Int = 1,
        shelf: String? = null,
        warehouse: String? = null,
        note: String = "",
        type: String = if (delta >= 0) "in" else "out",
        fallbackName: String = "",
        boxTotalIfNew: Int = 1
    ): Pair<InventoryItem, String?> {
        val cleanSku = sku.trim()
        val bNum = max(1, boxNum)
        val now = WarehouseBusinessLogic.isoNow()
        var resultingItem: InventoryItem? = null
        var completionBannerMsg: String? = null
        var resolvedShelf = shelf?.trim().orEmpty()
        var resolvedWh = warehouse?.trim().orEmpty()

        dao.runInTransaction {
            var art = dao.getArticle(cleanSku)
            val preExistingStocks = dao.getStocksForSku(cleanSku)

            if (art == null) {
                require(delta > 0) { "Artikel s šifro $cleanSku ne obstaja." }
                val newTotal = max(boxTotalIfNew, bNum)
                val targetWh = resolvedWh.ifEmpty { "spodnje" }
                val targetShelf = resolvedShelf.ifEmpty { "A0/0" }
                art = ArticleEntity(
                    sku = cleanSku,
                    name = fallbackName.trim().ifEmpty { "Artikel $cleanSku" },
                    category = "Pohištvo",
                    boxTotal = newTotal,
                    primaryWarehouse = targetWh,
                    unit = if (newTotal > 1) "garnitura" else "kos",
                    lastUpdated = now
                )
                dao.upsertArticle(art!!)
            } else if (bNum > art!!.boxTotal) {
                art = art!!.copy(boxTotal = bNum, lastUpdated = now)
                dao.upsertArticle(art!!)
            }

            val allStocks = dao.getStocksForSku(cleanSku).toMutableList()
            val boxStocks = allStocks.filter { it.boxNum == bNum }

            val targetStock: StockLocationEntity
            if (resolvedShelf.isNotEmpty()) {
                val existingAtShelf = boxStocks.firstOrNull {
                    it.shelf.equals(resolvedShelf, ignoreCase = true) && it.boxId == null
                }
                if (existingAtShelf != null) {
                    targetStock = existingAtShelf
                    if (resolvedWh.isEmpty()) resolvedWh = existingAtShelf.warehouse
                } else {
                    val wh = resolvedWh.ifEmpty { WarehouseBusinessLogic.inferWarehouseFromShelf(resolvedShelf) }
                    resolvedWh = wh
                    targetStock = StockLocationEntity(
                        id = WarehouseBusinessLogic.buildStockId(cleanSku, bNum, resolvedShelf),
                        sku = cleanSku,
                        boxNum = bNum,
                        shelf = resolvedShelf,
                        warehouse = wh,
                        count = 0,
                        isPrimary = boxStocks.isEmpty(),
                        lastUpdated = now
                    )
                    allStocks.add(targetStock)
                }
            } else {
                val existingPrimary = boxStocks.firstOrNull { it.isPrimary } ?: boxStocks.firstOrNull()
                if (existingPrimary != null) {
                    targetStock = existingPrimary
                    resolvedShelf = existingPrimary.shelf
                    if (resolvedWh.isEmpty()) resolvedWh = existingPrimary.warehouse
                } else {
                    resolvedShelf = "Regal Glavni"
                    val wh = resolvedWh.ifEmpty { "spodnje" }
                    resolvedWh = wh
                    targetStock = StockLocationEntity(
                        id = WarehouseBusinessLogic.buildStockId(cleanSku, bNum, resolvedShelf),
                        sku = cleanSku,
                        boxNum = bNum,
                        shelf = resolvedShelf,
                        warehouse = wh,
                        count = 0,
                        isPrimary = true,
                        lastUpdated = now
                    )
                    allStocks.add(targetStock)
                }
            }

            if (delta >= 0) {
                val updated = targetStock.copy(
                    count = targetStock.count + delta,
                    warehouse = resolvedWh.ifEmpty { targetStock.warehouse },
                    lastUpdated = now
                )
                dao.upsertStock(updated)
                val idx = allStocks.indexOfFirst { it.id == targetStock.id }
                if (idx >= 0) allStocks[idx] = updated else allStocks.add(updated)
            } else {
                var toRemove = -delta
                val updatedMainCount = max(0, targetStock.count - toRemove)
                toRemove = max(0, toRemove - targetStock.count)
                val updatedMain = targetStock.copy(count = updatedMainCount, lastUpdated = now)
                dao.upsertStock(updatedMain)
                val mainIdx = allStocks.indexOfFirst { it.id == targetStock.id }
                if (mainIdx >= 0) allStocks[mainIdx] = updatedMain

                if (toRemove > 0) {
                    for (other in boxStocks) {
                        if (other.id != targetStock.id && other.count > 0 && toRemove > 0) {
                            val take = min(other.count, toRemove)
                            toRemove -= take
                            val updOther = other.copy(count = other.count - take, lastUpdated = now)
                            dao.upsertStock(updOther)
                            val oIdx = allStocks.indexOfFirst { it.id == other.id }
                            if (oIdx >= 0) allStocks[oIdx] = updOther
                        }
                    }
                }
            }

            val updatedItem = WarehouseBusinessLogic.buildInventoryItem(art!!, allStocks)
            resultingItem = updatedItem

            val boxTag = if (art!!.boxTotal > 1) " (Šk. $bNum/${art!!.boxTotal} @ $resolvedShelf)" else " (@ $resolvedShelf)"
            val baseNote = note.ifBlank { if (delta >= 0) "Prevzem škatle" else "Izdaja škatle" }
            dao.insertLog(
                MovementLogEntity(
                    sku = cleanSku,
                    itemName = art!!.name,
                    boxNum = bNum,
                    shelf = resolvedShelf,
                    changeType = type,
                    changeAmount = delta,
                    resultingQty = updatedItem.completeSets,
                    note = baseNote + boxTag,
                    deviceId = prefs.getDeviceId(),
                    timestamp = now
                )
            )

            // Če je aktivna seja prevzema pošiljke in gre za prevzem (+):
            if (delta > 0) {
                val activeSession = dao.getActiveReceivingSession()
                if (activeSession != null && activeSession.active) {
                    completionBannerMsg = recordReceivingEntryInTx(
                        sessionEntity = activeSession,
                        sku = cleanSku,
                        name = art!!.name,
                        boxNum = bNum,
                        boxTotal = art!!.boxTotal,
                        shelf = resolvedShelf,
                        warehouse = resolvedWh.ifEmpty { activeSession.warehouse.takeIf { it != "all" } ?: "spodnje" },
                        count = delta,
                        preExistingStocks = preExistingStocks,
                        now = now
                    )
                }
            }
        }

        if (resolvedShelf.isNotEmpty() && resolvedShelf != "Brez lokacije") {
            prefs.lastUsedShelf = resolvedShelf
        }
        notifyMutated()
        return resultingItem!! to completionBannerMsg
    }

    private suspend fun recordReceivingEntryInTx(
        sessionEntity: ReceivingSessionEntity,
        sku: String,
        name: String,
        boxNum: Int,
        boxTotal: Int,
        shelf: String,
        warehouse: String,
        count: Int,
        preExistingStocks: List<StockLocationEntity>,
        now: String
    ): String? {
        val initType = object : TypeToken<MutableMap<String, MutableMap<Int, Int>>>() {}.type
        val entriesType = object : TypeToken<MutableList<ReceivingEntry>>() {}.type

        val initMap: MutableMap<String, MutableMap<Int, Int>> =
            runCatching { gson.fromJson<MutableMap<String, MutableMap<Int, Int>>>(sessionEntity.initialStockJson, initType) }
                .getOrNull() ?: mutableMapOf()
        val entries: MutableList<ReceivingEntry> =
            runCatching { gson.fromJson<MutableList<ReceivingEntry>>(sessionEntity.entriesJson, entriesType) }
                .getOrNull() ?: mutableListOf()

        if (!initMap.containsKey(sku)) {
            val boxInit = mutableMapOf<Int, Int>()
            for (b in 1..boxTotal) {
                boxInit[b] = preExistingStocks.filter { it.boxNum == b }.sumOf { it.count }
            }
            initMap[sku] = boxInit
        }

        val prevCompleted = WarehouseBusinessLogic.calculateSkuCompletion(
            sku = sku,
            boxTotal = boxTotal,
            initialBoxCounts = initMap[sku].orEmpty(),
            entries = entries
        ).completedFromOld

        entries.add(
            ReceivingEntry(
                id = "entry_${System.currentTimeMillis()}_${Random.nextInt(1000)}",
                timestamp = now,
                sku = sku,
                name = name,
                boxNum = boxNum,
                boxTotal = boxTotal,
                shelf = shelf.ifBlank { "Glavni regal" },
                warehouse = warehouse,
                count = count
            )
        )

        val newCompleted = WarehouseBusinessLogic.calculateSkuCompletion(
            sku = sku,
            boxTotal = boxTotal,
            initialBoxCounts = initMap[sku].orEmpty(),
            entries = entries
        ).completedFromOld

        dao.upsertReceivingSession(
            sessionEntity.copy(
                initialStockJson = gson.toJson(initMap),
                entriesJson = gson.toJson(entries)
            )
        )

        return if (newCompleted > prevCompleted) {
            val diff = newCompleted - prevCompleted
            "Kompletirano: +$diff komplet artikla \"$name\" (dopolnjeno s predhodno zalogo)."
        } else null
    }

    suspend fun issueCompleteItem(sku: String, qty: Int = 1, note: String = "") {
        val cleanSku = sku.trim()
        val issueQty = max(1, qty)
        val now = WarehouseBusinessLogic.isoNow()

        dao.runInTransaction {
            val art = dao.getArticle(cleanSku) ?: throw IllegalArgumentException("Artikel s šifro $cleanSku ne obstaja.")
            val boxTotal = max(1, art.boxTotal)
            val stocks = dao.getStocksForSku(cleanSku).toMutableList()
            val pickDetails = mutableListOf<String>()

            for (b in 1..boxTotal) {
                var remaining = issueQty
                for (i in stocks.indices) {
                    val s = stocks[i]
                    if (s.boxNum == b && s.count > 0 && remaining > 0) {
                        val take = min(s.count, remaining)
                        remaining -= take
                        val updated = s.copy(count = s.count - take, lastUpdated = now)
                        stocks[i] = updated
                        dao.upsertStock(updated)
                        pickDetails.add("Šk.$b: ${take}x iz ${s.shelf}")
                    }
                }
            }

            val updatedItem = WarehouseBusinessLogic.buildInventoryItem(art, stocks)
            val baseNote = note.ifBlank { "Izdaja po računu" }
            dao.insertLog(
                MovementLogEntity(
                    sku = cleanSku,
                    itemName = art.name,
                    changeType = "out",
                    changeAmount = -issueQty,
                    resultingQty = updatedItem.completeSets,
                    note = "$baseNote (-${issueQty}x garnitura [${pickDetails.joinToString(", ")}], preostalo: ${updatedItem.completeSets})",
                    deviceId = prefs.getDeviceId(),
                    timestamp = now
                )
            )
        }
        notifyMutated()
    }

    suspend fun moveStock(
        sku: String,
        boxNum: Int,
        fromShelf: String,
        toShelf: String,
        amount: Int,
        note: String = ""
    ) {
        val cleanSku = sku.trim()
        val bNum = max(1, boxNum)
        val moveQty = max(1, amount)
        val fromS = fromShelf.trim()
        val toS = toShelf.trim()
        require(fromS.isNotEmpty() && toS.isNotEmpty()) { "Izvorna in ciljna lokacija sta obvezni." }
        require(!fromS.equals(toS, ignoreCase = true)) { "Izvorna in ciljna lokacija ne moreta biti enaki." }

        val now = WarehouseBusinessLogic.isoNow()
        dao.runInTransaction {
            val art = dao.getArticle(cleanSku) ?: throw IllegalArgumentException("Artikel s šifro $cleanSku ne obstaja.")
            val stocks = dao.getStocksForSku(cleanSku)
            val fromLoc = stocks.firstOrNull { it.boxNum == bNum && it.shelf.equals(fromS, ignoreCase = true) && it.boxId == null }
                ?: throw IllegalStateException("Lokacija \"$fromS\" za škatlo $bNum ne obstaja.")

            if (fromLoc.count < moveQty) {
                throw IllegalStateException("Na lokaciji \"$fromS\" ni dovolj zaloge (na voljo: ${fromLoc.count}, zahtevano: $moveQty).")
            }

            dao.upsertStock(fromLoc.copy(count = fromLoc.count - moveQty, lastUpdated = now))

            val toId = WarehouseBusinessLogic.buildStockId(cleanSku, bNum, toS)
            val existingDest = dao.getStockById(toId)
            val toWh = existingDest?.warehouse ?: fromLoc.warehouse
            dao.upsertStock(
                StockLocationEntity(
                    id = toId,
                    sku = cleanSku,
                    boxNum = bNum,
                    shelf = toS,
                    warehouse = toWh,
                    count = (existingDest?.count ?: 0) + moveQty,
                    isPrimary = existingDest?.isPrimary ?: false,
                    lastUpdated = now
                )
            )

            val baseNote = note.ifBlank { "Premestitev med regali" }
            dao.insertLog(
                MovementLogEntity(
                    sku = cleanSku,
                    itemName = art.name,
                    boxNum = bNum,
                    shelf = "$fromS ➔ $toS",
                    changeType = "adjust",
                    changeAmount = 0,
                    resultingQty = 0,
                    note = "$baseNote (${moveQty}x Šk.$bNum iz $fromS na $toS)",
                    deviceId = prefs.getDeviceId(),
                    timestamp = now
                )
            )
        }
        notifyMutated()
    }

    suspend fun consolidateItemToShelf(sku: String, targetShelf: String) {
        val item = getItemBySku(sku) ?: return
        val scattered = WarehouseBusinessLogic.findScatteredItems(listOf(item)).firstOrNull() ?: return
        for (loc in scattered.boxesToMove) {
            if (loc.count > 0 && !loc.shelf.equals(targetShelf, ignoreCase = true)) {
                moveStock(
                    sku = sku,
                    boxNum = loc.boxNum,
                    fromShelf = loc.shelf,
                    toShelf = targetShelf,
                    amount = loc.count,
                    note = "Konsolidacija na en regal"
                )
            }
        }
    }

    suspend fun applyAuditResults(entries: List<AuditEntry>): Int {
        val countedChanged = entries.filter { it.isCounted && it.diff != 0 }
        if (countedChanged.isEmpty()) return 0
        val now = WarehouseBusinessLogic.isoNow()

        dao.runInTransaction {
            for (entry in countedChanged) {
                val stockId = WarehouseBusinessLogic.buildStockId(entry.sku, entry.boxNum, entry.shelf)
                val existing = dao.getStockById(stockId)
                dao.upsertStock(
                    StockLocationEntity(
                        id = stockId,
                        sku = entry.sku,
                        boxNum = entry.boxNum,
                        shelf = entry.shelf,
                        warehouse = existing?.warehouse ?: entry.warehouse,
                        count = max(0, entry.countedQty),
                        isPrimary = existing?.isPrimary ?: true,
                        lastUpdated = now
                    )
                )
                val sign = if (entry.diff > 0) "+${entry.diff}" else "${entry.diff}"
                dao.insertLog(
                    MovementLogEntity(
                        sku = entry.sku,
                        itemName = entry.name,
                        boxNum = entry.boxNum,
                        shelf = entry.shelf,
                        changeType = "adjust",
                        changeAmount = entry.diff,
                        resultingQty = max(0, entry.countedQty),
                        note = "Inventurna izravnava (knjižno: ${entry.bookQty}, prešteto: ${entry.countedQty}, razlika: $sign)",
                        deviceId = prefs.getDeviceId(),
                        timestamp = now
                    )
                )
            }
        }
        notifyMutated()
        return countedChanged.size
    }

    // --- MEŠANE ŠKATLE ---
    suspend fun nextMixedBoxNumber(): String {
        val existing = dao.getAllMixedBoxes()
        val maxNum = existing.mapNotNull { it.boxNumber.toIntOrNull() }.maxOrNull() ?: 100
        return (maxNum + 1).toString()
    }

    suspend fun saveMixedBox(
        boxNumber: String,
        warehouse: String,
        shelf: String,
        notes: String,
        items: List<MixedBoxItem>
    ): String {
        val cleanNum = boxNumber.trim()
        require(cleanNum.isNotEmpty()) { "Številka škatle je obvezna." }
        require(items.isNotEmpty()) { "Dodajte vsaj en artikel v škatlo." }

        val boxId = cleanNum
        val cleanShelf = shelf.trim().ifEmpty { "Brez lokacije" }
        val cleanWh = warehouse.ifBlank { "zgornje" }
        val now = WarehouseBusinessLogic.isoNow()

        dao.runInTransaction {
            val prevBox = dao.getMixedBox(boxId)
            dao.deleteStocksForMixedBox(boxId)

            val aggregated = items.groupBy { it.sku.trim() }.map { (s, list) ->
                MixedBoxItem(
                    sku = s,
                    name = list.first().name.ifBlank { "Artikel $s" },
                    qty = list.sumOf { max(1, it.qty) }
                )
            }

            for (it in aggregated) {
                val stockId = WarehouseBusinessLogic.buildStockId(it.sku, 1, cleanShelf, boxId)
                dao.upsertStock(
                    StockLocationEntity(
                        id = stockId,
                        sku = it.sku,
                        boxNum = 1,
                        shelf = cleanShelf,
                        warehouse = cleanWh,
                        boxId = boxId,
                        count = it.qty,
                        isPrimary = false,
                        lastUpdated = now
                    )
                )

                if (dao.getArticle(it.sku) == null) {
                    dao.upsertArticle(
                        ArticleEntity(
                            sku = it.sku,
                            name = it.name.ifBlank { "Artikel ${it.sku}" },
                            category = "Tekstil / Soft",
                            boxTotal = 1,
                            categoryType = "soft",
                            primaryWarehouse = cleanWh,
                            unit = "kos",
                            notes = "Mešana škatla #$boxId",
                            lastUpdated = now
                        )
                    )
                }
            }

            val totalPcs = aggregated.sumOf { it.qty }
            dao.upsertMixedBox(
                MixedBoxEntity(
                    boxId = boxId,
                    boxNumber = cleanNum,
                    warehouse = cleanWh,
                    shelf = cleanShelf,
                    notes = notes.trim(),
                    totalItems = totalPcs,
                    itemsJson = gson.toJson(aggregated),
                    createdAt = prevBox?.createdAt ?: now,
                    updatedAt = now
                )
            )

            val whName = when (cleanWh) {
                "zgornje" -> "Zgornje skladišče"
                "spodnje" -> "Spodnje skladišče"
                else -> cleanWh
            }
            dao.insertLog(
                MovementLogEntity(
                    sku = "BOX-$boxId",
                    itemName = "Mešana škatla #$boxId (${aggregated.size} artiklov, $totalPcs kos)",
                    boxNum = 1,
                    shelf = "$whName - $cleanShelf",
                    changeType = "pack_mixed_box",
                    changeAmount = totalPcs,
                    resultingQty = totalPcs,
                    note = "Pakiranje mešane škatle #$boxId",
                    deviceId = prefs.getDeviceId(),
                    timestamp = now
                )
            )
        }
        notifyMutated()
        return boxId
    }

    suspend fun unpackAndDeleteMixedBox(boxId: String) {
        val cleanId = boxId.trim()
        val now = WarehouseBusinessLogic.isoNow()
        dao.runInTransaction {
            val box = dao.getMixedBox(cleanId)
            val boxStocks = dao.getStocksForMixedBox(cleanId)
            // Razpakiraj nazaj na posamične kose na isti regal (kot navaja NAVODILA.md), nato zbriši mixed_box zapis
            for (st in boxStocks) {
                dao.deleteStockById(st.id)
                val looseId = WarehouseBusinessLogic.buildStockId(st.sku, 1, st.shelf, null)
                val existingLoose = dao.getStockById(looseId)
                dao.upsertStock(
                    StockLocationEntity(
                        id = looseId,
                        sku = st.sku,
                        boxNum = 1,
                        shelf = st.shelf,
                        warehouse = st.warehouse,
                        boxId = null,
                        count = (existingLoose?.count ?: 0) + st.count,
                        isPrimary = existingLoose?.isPrimary ?: true,
                        lastUpdated = now
                    )
                )
            }
            dao.deleteMixedBox(cleanId)
            dao.insertLog(
                MovementLogEntity(
                    sku = "BOX-$cleanId",
                    itemName = "Razpakirana mešana škatla #${box?.boxNumber ?: cleanId}",
                    boxNum = 1,
                    shelf = box?.shelf ?: "Razpakirano",
                    changeType = "delete_mixed_box",
                    changeAmount = 0,
                    resultingQty = 0,
                    note = "Razpakiranje mešane škatle #$cleanId na posamične kose",
                    deviceId = prefs.getDeviceId(),
                    timestamp = now
                )
            )
        }
        notifyMutated()
    }

    // --- NABIRANJE ZA POLICE ---
    suspend fun addToReplenishment(
        sku: String,
        shelfCapacity: Int,
        currentShelfQty: Int,
        targetShelf: String
    ) {
        val cleanSku = sku.trim()
        require(cleanSku.isNotEmpty()) { "Šifra artikla (SKU) je obvezna." }
        val cap = max(1, shelfCapacity)
        val cur = max(0, currentShelfQty)
        val needed = max(1, cap - cur)
        val now = WarehouseBusinessLogic.isoNow()

        dao.runInTransaction {
            val art = dao.getArticle(cleanSku)
            if (art != null) {
                dao.upsertArticle(
                    art.copy(
                        shelfCapacity = cap,
                        shelfLocation = targetShelf.trim().ifBlank { art.shelfLocation },
                        lastUpdated = now
                    )
                )
            }

            val stocks = dao.getStocksForSku(cleanSku)
            val whStocks = stocks.filter { it.warehouse != "trgovina" && it.count > 0 }
            val best = whStocks.firstOrNull { it.warehouse == "zgornje" } ?: whStocks.firstOrNull()

            val effectiveTarget = targetShelf.trim()
                .ifBlank { art?.shelfLocation.orEmpty() }
                .ifBlank { "Prodajne police" }

            dao.upsertReplenishment(
                ReplenishmentEntity(
                    sku = cleanSku,
                    name = art?.name ?: "Artikel $cleanSku",
                    neededQty = needed,
                    currentShelfQty = cur,
                    targetShelf = effectiveTarget,
                    warehouse = best?.warehouse ?: "zgornje",
                    sourceLocation = best?.shelf ?: "Ni zaloge v skladišču",
                    boxId = best?.boxId,
                    status = "pending",
                    createdAt = now
                )
            )
        }
        notifyMutated()
    }

    suspend fun completeReplenishmentItem(repId: Long, transferQty: Int? = null) {
        val now = WarehouseBusinessLogic.isoNow()
        dao.runInTransaction {
            val item = dao.getReplenishmentById(repId) ?: throw IllegalArgumentException("Postavka ne obstaja.")
            val qtyToTransfer = transferQty?.let { max(1, it) } ?: max(1, item.neededQty)

            val stocks = dao.getStocksForSku(item.sku)
            var remaining = qtyToTransfer
            for (st in stocks) {
                if (st.warehouse != "trgovina" && st.count > 0 && remaining > 0) {
                    val deduct = min(st.count, remaining)
                    remaining -= deduct
                    dao.upsertStock(st.copy(count = st.count - deduct, lastUpdated = now))
                }
            }

            val targetShelf = item.targetShelf.ifBlank { "Prodajne police" }
            val storeLocId = "${item.sku}_b1_${targetShelf}_trgovina"
            val storeLoc = dao.getStockById(storeLocId)
            dao.upsertStock(
                StockLocationEntity(
                    id = storeLocId,
                    sku = item.sku,
                    boxNum = 1,
                    shelf = targetShelf,
                    warehouse = "trgovina",
                    boxId = null,
                    count = (storeLoc?.count ?: 0) + qtyToTransfer,
                    isPrimary = true,
                    lastUpdated = now
                )
            )

            dao.upsertReplenishment(
                item.copy(
                    status = "completed",
                    transferredQty = qtyToTransfer,
                    completedAt = now
                )
            )

            val srcLabel = if (item.warehouse == "zgornje") "Zgornje skl." else "Spodnje skl."
            dao.insertLog(
                MovementLogEntity(
                    sku = item.sku,
                    itemName = item.name,
                    boxNum = 1,
                    shelf = "Prenos: $srcLabel (${item.sourceLocation}) ➔ $targetShelf",
                    changeType = "replenish_shelf",
                    changeAmount = qtyToTransfer,
                    resultingQty = (storeLoc?.count ?: 0) + qtyToTransfer,
                    note = "Dopolnitev police v prodajalni (+$qtyToTransfer kos)",
                    deviceId = prefs.getDeviceId(),
                    timestamp = now
                )
            )
        }
        notifyMutated()
    }

    suspend fun removeReplenishmentItem(repId: Long) {
        dao.deleteReplenishment(repId)
        notifyMutated()
    }

    suspend fun clearCompletedReplenishments() {
        dao.clearCompletedReplenishments()
        notifyMutated()
    }

    // --- PREVZEMNA SEJA TOVORNJAKA ---
    suspend fun startReceivingSession(customName: String = "", warehouse: String = "all"): ReceivingSessionModel {
        val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale("sl", "SI")).format(Date())
        val now = WarehouseBusinessLogic.isoNow()
        val session = ReceivingSessionEntity(
            id = "rec_${System.currentTimeMillis()}",
            name = customName.trim().ifEmpty { "Prevzem $dateStr" },
            warehouse = warehouse.ifBlank { "all" },
            active = true,
            startedAt = now
        )
        dao.runInTransaction {
            dao.deactivateAllReceivingSessions()
            dao.upsertReceivingSession(session)
        }
        if (warehouse.isNotBlank() && warehouse != "all") {
            prefs.activeScannerWarehouse = warehouse
        }
        notifyMutated()
        return toReceivingModel(session)
    }

    suspend fun updateReceivingWarehouse(warehouse: String) {
        val active = dao.getActiveReceivingSession() ?: return
        dao.upsertReceivingSession(active.copy(warehouse = warehouse))
        if (warehouse != "all") prefs.activeScannerWarehouse = warehouse
    }

    suspend fun finishReceivingSession(): ReceivingSessionModel? {
        val active = dao.getActiveReceivingSession() ?: return null
        val model = toReceivingModel(active)
        val totalBoxes = model.entries.sumOf { it.count }
        val uniqueSkus = model.entries.map { it.sku }.distinct()

        var totalCompleted = 0
        var missingCount = 0
        for (sku in uniqueSkus) {
            val firstEntry = model.entries.first { it.sku == sku }
            val comp = WarehouseBusinessLogic.calculateSkuCompletion(
                sku = sku,
                boxTotal = firstEntry.boxTotal,
                initialBoxCounts = model.initialStock[sku].orEmpty(),
                entries = model.entries
            )
            totalCompleted += comp.totalNewSets
            if (comp.missingInBatch.isNotEmpty()) missingCount++
        }

        val finished = active.copy(
            active = false,
            finishedAt = WarehouseBusinessLogic.isoNow(),
            totalBoxes = totalBoxes,
            uniqueSkusCount = uniqueSkus.size,
            totalCompletedSets = totalCompleted,
            incompleteItemsCount = missingCount
        )
        dao.upsertReceivingSession(finished)
        notifyMutated()
        return toReceivingModel(finished)
    }

    suspend fun deleteArchivedReceivingSession(id: String) {
        dao.deleteReceivingSession(id)
    }

    // --- SKLADIŠČA & ORODJA ---
    suspend fun saveWarehouse(name: String, isStore: Boolean = false) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Naziv skladišča je obvezen." }
        dao.upsertWarehouse(
            WarehouseEntity(
                id = "wh_${System.currentTimeMillis()}",
                name = cleanName,
                isStore = isStore,
                isDefault = false,
                colorHex = if (isStore) "#38bdf8" else "#a78bfa"
            )
        )
    }

    suspend fun deleteWarehouse(id: String) {
        if (id in listOf("trgovina", "zgornje", "spodnje")) {
            throw IllegalArgumentException("Privzetih skladišč ni mogoče izbrisati.")
        }
        dao.deleteWarehouse(id)
    }

    suspend fun seedDemoData() {
        DemoCatalogSeeder.seedDemoData(dao, prefs.getDeviceId())
        prefs.isInitialSeedDone = true
        notifyMutated()
    }

    suspend fun clearLogs() {
        dao.clearLogs()
    }

    suspend fun clearAllItems() {
        val now = WarehouseBusinessLogic.isoNow()
        dao.runInTransaction {
            dao.clearArticles()
            dao.clearStocks()
            dao.insertLog(
                MovementLogEntity(
                    sku = "SYS-CLEAR",
                    itemName = "Baza artiklov izpraznjena",
                    changeType = "delete",
                    changeAmount = 0,
                    resultingQty = 0,
                    note = "Izbris vseh artiklov in lokacij iz skladišča",
                    deviceId = prefs.getDeviceId(),
                    timestamp = now
                )
            )
        }
        notifyMutated()
    }

    suspend fun deduplicateItems(): Int {
        // Združi podvojene zaloge za isti SKU + škatlo + normalizirano ime regala (case-insensitive)
        val allStocks = dao.getAllStocks()
        val now = WarehouseBusinessLogic.isoNow()
        var mergedCount = 0
        val groups = allStocks.groupBy {
            "${it.sku.trim()}|${it.boxNum}|${it.shelf.trim().lowercase(Locale.ROOT)}|${it.boxId.orEmpty()}"
        }
        dao.runInTransaction {
            for ((_, group) in groups) {
                if (group.size > 1) {
                    mergedCount += group.size - 1
                    val primary = group.firstOrNull { it.isPrimary } ?: group.first()
                    val sum = group.sumOf { max(0, it.count) }
                    for (g in group) dao.deleteStockById(g.id)
                    dao.upsertStock(primary.copy(count = sum, lastUpdated = now))
                }
            }
        }
        if (mergedCount > 0) notifyMutated()
        return mergedCount
    }

    // --- BACKUP & BLE MESH SINHRONIZACIJA (100% združljivo z v2 JSON shemo iz db.js) ---
    suspend fun exportBackupJson(): String {
        val header = prefs.getSyncHeaderInfo()
        val root = buildSyncJsonObject(header, includeAllLogs = true, includeLegacyItems = true)
        return prettyGson.toJson(root)
    }

    suspend fun getSyncPayloadJson(): String {
        val header = prefs.getSyncHeaderInfo()
        val root = buildSyncJsonObject(header, includeAllLogs = false, includeLegacyItems = false)
        return gson.toJson(root)
    }

    private suspend fun buildSyncJsonObject(
        header: SyncHeaderInfo,
        includeAllLogs: Boolean,
        includeLegacyItems: Boolean
    ): JsonObject {
        val articles = dao.getAllArticles()
        val stocks = dao.getAllStocks()
        val mixed = dao.getAllMixedBoxes()
        val reps = dao.getAllReplenishments()
        val allLogs = dao.getAllLogs()
        val logsToInclude = if (includeAllLogs) allLogs else allLogs.take(300)

        val root = JsonObject()
        root.addProperty("version", 2)
        root.addProperty("deviceId", header.deviceId)
        root.addProperty("dbVersion", header.dbVersion)
        root.addProperty("stateHash", header.stateHash)
        root.addProperty("exportedAt", WarehouseBusinessLogic.isoNow())
        root.addProperty("createdAt", WarehouseBusinessLogic.isoNow())
        root.add("articles", gson.toJsonTree(articles))
        root.add("stockLocations", gson.toJsonTree(stocks))

        val mixedJsonArr = JsonArray()
        for (m in mixed) {
            val obj = JsonObject()
            obj.addProperty("boxId", m.boxId)
            obj.addProperty("boxNumber", m.boxNumber)
            obj.addProperty("warehouse", m.warehouse)
            obj.addProperty("shelf", m.shelf)
            obj.addProperty("notes", m.notes)
            obj.addProperty("totalItems", m.totalItems)
            obj.add("items", JsonParser.parseString(m.itemsJson))
            obj.addProperty("createdAt", m.createdAt)
            obj.addProperty("updatedAt", m.updatedAt)
            mixedJsonArr.add(obj)
        }
        root.add("mixedBoxes", mixedJsonArr)

        val repArr = JsonArray()
        for (r in reps) {
            val obj = JsonObject()
            obj.addProperty("id", r.id)
            obj.addProperty("sku", r.sku)
            obj.addProperty("name", r.name)
            obj.addProperty("neededQty", r.neededQty)
            obj.addProperty("currentShelfQty", r.currentShelfQty)
            obj.addProperty("targetShelf", r.targetShelf)
            obj.addProperty("sourceWarehouse", r.warehouse)
            obj.addProperty("sourceLocation", r.sourceLocation)
            if (r.boxId != null) obj.addProperty("boxId", r.boxId)
            obj.addProperty("status", r.status)
            obj.addProperty("transferredQty", r.transferredQty)
            obj.addProperty("createdAt", r.createdAt)
            if (r.completedAt != null) obj.addProperty("completedAt", r.completedAt)
            repArr.add(obj)
        }
        root.add("replenishmentList", repArr)
        root.add("logs", gson.toJsonTree(logsToInclude))

        if (includeLegacyItems) {
            val bySku = stocks.groupBy { it.sku }
            val legacyItems = articles.map { WarehouseBusinessLogic.buildInventoryItem(it, bySku[it.sku].orEmpty()) }
            root.add("items", gson.toJsonTree(legacyItems))
        }

        return root
    }

    suspend fun mergeSyncPayloadJson(jsonStr: String): SyncMergeResult {
        val rootEl = JsonParser.parseString(jsonStr)
        if (!rootEl.isJsonObject) throw IllegalArgumentException("Neveljavna JSON datoteka za sinhronizacijo.")
        val root = rootEl.asJsonObject

        var addedArticles = 0
        var updatedRecords = 0
        var addedLogs = 0
        var mixedCount = 0
        var repCount = 0

        val incArticles = mutableListOf<ArticleEntity>()
        val incStocks = mutableListOf<StockLocationEntity>()

        root.getAsJsonArray("articles")?.forEach { el ->
            val o = el.asJsonObject
            val sku = o.str("sku").trim()
            if (sku.isNotEmpty()) {
                incArticles.add(
                    ArticleEntity(
                        sku = sku,
                        name = o.str("name").ifBlank { "Artikel $sku" },
                        category = o.str("category").ifBlank { "Splošno" },
                        boxTotal = max(1, o.int("boxTotal", 1)),
                        minQuantity = o.int("minQuantity", 0),
                        shelfCapacity = o.int("shelfCapacity", 0),
                        shelfLocation = o.str("shelfLocation"),
                        categoryType = o.str("categoryType").ifBlank { "furniture" },
                        primaryWarehouse = o.str("primaryWarehouse"),
                        unit = o.str("unit").ifBlank { "kos" },
                        notes = o.str("notes"),
                        lastUpdated = o.str("lastUpdated").ifBlank { WarehouseBusinessLogic.isoNow() }
                    )
                )
            }
        }

        root.getAsJsonArray("stockLocations")?.forEach { el ->
            val o = el.asJsonObject
            val id = o.str("id").trim()
            val sku = o.str("sku").trim()
            if (id.isNotEmpty() && sku.isNotEmpty()) {
                val shelf = o.str("shelf").ifBlank { "Brez lokacije" }
                incStocks.add(
                    StockLocationEntity(
                        id = id,
                        sku = sku,
                        boxNum = max(1, o.int("boxNum", 1)),
                        shelf = shelf,
                        warehouse = o.str("warehouse").ifBlank { WarehouseBusinessLogic.inferWarehouseFromShelf(shelf) },
                        boxId = o.strOrNull("boxId"),
                        count = max(0, o.int("count", 0)),
                        isPrimary = o.bool("isPrimary", false),
                        lastUpdated = o.str("lastUpdated").ifBlank { WarehouseBusinessLogic.isoNow() }
                    )
                )
            }
        }

        // Fallback za legacy backup format ("items")
        if (incArticles.isEmpty()) {
            root.getAsJsonArray("items")?.forEach { el ->
                val o = el.asJsonObject
                val sku = o.str("sku").trim()
                if (sku.isNotEmpty()) {
                    val boxTotal = max(1, o.int("boxTotal", 1))
                    val ts = o.str("lastUpdated").ifBlank { WarehouseBusinessLogic.isoNow() }
                    incArticles.add(
                        ArticleEntity(
                            sku = sku,
                            name = o.str("name").ifBlank { "Artikel $sku" },
                            category = o.str("category").ifBlank { "Splošno" },
                            boxTotal = boxTotal,
                            minQuantity = o.int("minQuantity", 0),
                            shelfCapacity = o.int("shelfCapacity", 0),
                            shelfLocation = o.str("shelfLocation"),
                            categoryType = o.str("categoryType").ifBlank { "furniture" },
                            primaryWarehouse = o.str("primaryWarehouse"),
                            unit = o.str("unit").ifBlank { if (boxTotal > 1) "garnitura" else "kos" },
                            notes = o.str("notes"),
                            lastUpdated = ts
                        )
                    )
                    val boxesObj = o.getAsJsonObject("boxes")
                    if (boxesObj != null) {
                        for (b in 1..boxTotal) {
                            val bObj = boxesObj.getAsJsonObject(b.toString()) ?: continue
                            val locs = bObj.getAsJsonArray("locations")
                            if (locs != null && locs.size() > 0) {
                                locs.forEach { locEl ->
                                    val lo = locEl.asJsonObject
                                    val shelfName = lo.str("name").ifBlank { lo.str("location").ifBlank { "Brez lokacije" } }
                                    val boxId = lo.strOrNull("boxId")
                                    incStocks.add(
                                        StockLocationEntity(
                                            id = WarehouseBusinessLogic.buildStockId(sku, b, shelfName, boxId),
                                            sku = sku,
                                            boxNum = b,
                                            shelf = shelfName,
                                            warehouse = lo.str("warehouse").ifBlank { WarehouseBusinessLogic.inferWarehouseFromShelf(shelfName) },
                                            boxId = boxId,
                                            count = max(0, lo.int("count", 0)),
                                            isPrimary = lo.bool("isPrimary", false),
                                            lastUpdated = ts
                                        )
                                    )
                                }
                            } else {
                                val shelfName = bObj.str("location").ifBlank { o.str("location").ifBlank { "Brez lokacije" } }
                                incStocks.add(
                                    StockLocationEntity(
                                        id = WarehouseBusinessLogic.buildStockId(sku, b, shelfName),
                                        sku = sku,
                                        boxNum = b,
                                        shelf = shelfName,
                                        warehouse = WarehouseBusinessLogic.inferWarehouseFromShelf(shelfName),
                                        count = max(0, bObj.int("count", 0)),
                                        isPrimary = b == 1,
                                        lastUpdated = ts
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        dao.runInTransaction {
            // 1. Articles LWW
            for (art in incArticles) {
                val existing = dao.getArticle(art.sku)
                if (existing == null) {
                    dao.upsertArticle(art)
                    addedArticles++
                } else if (parseIsoMillis(art.lastUpdated) >= parseIsoMillis(existing.lastUpdated)) {
                    dao.upsertArticle(art)
                    updatedRecords++
                }
            }

            // 2. Stock locations LWW
            for (st in incStocks) {
                val existing = dao.getStockById(st.id)
                if (existing == null) {
                    dao.upsertStock(st)
                    updatedRecords++
                } else if (parseIsoMillis(st.lastUpdated) >= parseIsoMillis(existing.lastUpdated)) {
                    dao.upsertStock(st)
                    updatedRecords++
                }
            }

            // 3. Mixed boxes LWW
            root.getAsJsonArray("mixedBoxes")?.forEach { el ->
                val o = el.asJsonObject
                val boxId = o.str("boxId").ifBlank { o.str("id") }.trim()
                if (boxId.isNotEmpty()) {
                    val itemsEl = o.get("items") ?: JsonArray()
                    val createdAt = o.str("createdAt").ifBlank { WarehouseBusinessLogic.isoNow() }
                    val updatedAt = o.str("updatedAt").ifBlank { createdAt }
                    val entity = MixedBoxEntity(
                        boxId = boxId,
                        boxNumber = o.str("boxNumber").ifBlank { boxId },
                        warehouse = o.str("warehouse").ifBlank { "zgornje" },
                        shelf = o.str("shelf").ifBlank { "Brez lokacije" },
                        notes = o.str("notes"),
                        totalItems = o.int("totalItems", 0),
                        itemsJson = gson.toJson(itemsEl),
                        createdAt = createdAt,
                        updatedAt = updatedAt
                    )
                    val existing = dao.getMixedBox(boxId)
                    if (existing == null || parseIsoMillis(updatedAt) >= parseIsoMillis(existing.updatedAt)) {
                        dao.upsertMixedBox(entity)
                        mixedCount++
                    }
                }
            }

            // 4. Replenishment list merge
            root.getAsJsonArray("replenishmentList")?.forEach { el ->
                val o = el.asJsonObject
                val id = o.long("id", 0L)
                val sku = o.str("sku").trim()
                if (sku.isNotEmpty()) {
                    val incStatus = o.str("status").ifBlank { "pending" }
                    val incCreated = o.str("createdAt").ifBlank { WarehouseBusinessLogic.isoNow() }
                    val entity = ReplenishmentEntity(
                        id = if (id > 0L) id else 0L,
                        sku = sku,
                        name = o.str("name").ifBlank { "Artikel $sku" },
                        neededQty = max(1, o.int("neededQty", 1)),
                        currentShelfQty = max(0, o.int("currentShelfQty", 0)),
                        targetShelf = o.str("targetShelf").ifBlank { "Prodajne police" },
                        warehouse = o.str("sourceWarehouse").ifBlank { o.str("warehouse").ifBlank { "zgornje" } },
                        sourceLocation = o.str("sourceLocation").ifBlank { "Ni zaloge v skladišču" },
                        boxId = o.strOrNull("boxId"),
                        status = incStatus,
                        transferredQty = o.int("transferredQty", 0),
                        createdAt = incCreated,
                        completedAt = o.strOrNull("completedAt")
                    )
                    val existing = if (id > 0L) dao.getReplenishmentById(id) else null
                    if (existing == null) {
                        dao.upsertReplenishment(entity)
                        repCount++
                    } else if (incStatus == "completed" && existing.status != "completed") {
                        dao.upsertReplenishment(entity)
                        repCount++
                    } else if (existing.status != "completed" && parseIsoMillis(incCreated) >= parseIsoMillis(existing.createdAt)) {
                        dao.upsertReplenishment(entity)
                    }
                }
            }

            // 5. Logs deduplication + CRDT delta replay
            val existingLogs = dao.getAllLogs()
            val existingKeys = existingLogs.mapTo(HashSet()) { "${it.timestamp}_${it.sku}_${it.changeType}" }
            root.getAsJsonArray("logs")?.forEach { el ->
                val o = el.asJsonObject
                val ts = o.str("timestamp")
                if (ts.isNotEmpty()) {
                    val sku = o.str("sku").trim()
                    val changeType = o.str("changeType").ifBlank { o.str("action").ifBlank { "adjust" } }
                    val key = "${ts}_${sku}_$changeType"
                    if (!existingKeys.contains(key)) {
                        existingKeys.add(key)
                        val delta = if (o.has("delta") && !o.get("delta").isJsonNull) {
                            o.int("delta", 0)
                        } else {
                            o.int("changeAmount", 0)
                        }
                        val bNum = max(1, o.int("boxNum", 1))
                        val shelf = o.str("shelf").ifBlank {
                            val loc = o.str("location")
                            if (loc.contains("➔")) "" else loc
                        }
                        dao.insertLog(
                            MovementLogEntity(
                                sku = sku,
                                itemName = o.str("itemName").ifBlank { sku },
                                boxNum = bNum,
                                shelf = shelf,
                                changeType = changeType,
                                changeAmount = delta,
                                resultingQty = o.int("resultingQty", 0),
                                note = o.str("note"),
                                deviceId = o.str("deviceId"),
                                timestamp = ts
                            )
                        )
                        addedLogs++

                        if (delta != 0 && sku.isNotEmpty() && shelf.isNotEmpty() &&
                            changeType in listOf("in", "out", "adjust")
                        ) {
                            val stockId = WarehouseBusinessLogic.buildStockId(sku, bNum, shelf)
                            val curr = dao.getStockById(stockId)
                            if (curr != null) {
                                dao.upsertStock(
                                    curr.copy(
                                        count = max(0, curr.count + delta),
                                        lastUpdated = WarehouseBusinessLogic.isoNow()
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Ne prožimo ponovnega BLE push-a kot pri lokalni mutaciji, posodobimo pa hash na vrhu
        _syncHeader.value = prefs.bumpMutationVersion()
        return SyncMergeResult(
            addedArticles = addedArticles,
            updatedRecords = updatedRecords,
            addedLogs = addedLogs,
            mixedBoxes = mixedCount,
            replenishments = repCount
        )
    }

    private fun toMixedBoxModel(e: MixedBoxEntity): MixedBoxWithItems {
        val listType = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val rawItems: List<Map<String, Any?>> =
            runCatching { gson.fromJson<List<Map<String, Any?>>>(e.itemsJson, listType) }.getOrNull().orEmpty()
        val parsedItems = rawItems.mapNotNull { m ->
            val sku = m["sku"]?.toString()?.trim().orEmpty()
            if (sku.isEmpty()) return@mapNotNull null
            val name = m["name"]?.toString()?.takeIf { it.isNotBlank() } ?: "Artikel $sku"
            val qtyNum = (m["qty"] as? Number)?.toInt()
                ?: (m["quantity"] as? Number)?.toInt()
                ?: (m["count"] as? Number)?.toInt()
                ?: 1
            MixedBoxItem(sku = sku, name = name, qty = max(1, qtyNum))
        }
        return MixedBoxWithItems(
            boxId = e.boxId,
            boxNumber = e.boxNumber,
            warehouse = e.warehouse,
            shelf = e.shelf,
            notes = e.notes,
            totalItems = if (e.totalItems > 0) e.totalItems else parsedItems.sumOf { it.qty },
            items = parsedItems,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt
        )
    }

    private fun toReceivingModel(e: ReceivingSessionEntity): ReceivingSessionModel {
        val initType = object : TypeToken<Map<String, Map<Int, Int>>>() {}.type
        val entriesType = object : TypeToken<List<ReceivingEntry>>() {}.type
        val initMap: Map<String, Map<Int, Int>> =
            runCatching { gson.fromJson<Map<String, Map<Int, Int>>>(e.initialStockJson, initType) }.getOrNull().orEmpty()
        val entries: List<ReceivingEntry> =
            runCatching { gson.fromJson<List<ReceivingEntry>>(e.entriesJson, entriesType) }.getOrNull().orEmpty()
        return ReceivingSessionModel(
            id = e.id,
            name = e.name,
            warehouse = e.warehouse,
            active = e.active,
            startedAt = e.startedAt,
            finishedAt = e.finishedAt,
            initialStock = initMap,
            entries = entries,
            totalBoxes = e.totalBoxes,
            uniqueSkusCount = e.uniqueSkusCount,
            totalCompletedSets = e.totalCompletedSets,
            incompleteItemsCount = e.incompleteItemsCount
        )
    }

    private fun notifyMutated() {
        val next = prefs.bumpMutationVersion()
        _syncHeader.value = next
        onDatabaseMutated?.invoke(next)
    }

    private fun parseIsoMillis(iso: String): Long {
        if (iso.isBlank()) return 0L
        return try {
            val clean = iso.replace("Z", "")
            val parser = SimpleDateFormat(
                if (clean.contains(".")) "yyyy-MM-dd'T'HH:mm:ss.SSS" else "yyyy-MM-dd'T'HH:mm:ss",
                Locale.US
            )
            parser.timeZone = TimeZone.getTimeZone("UTC")
            parser.parse(clean)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun JsonObject.str(key: String): String {
        val el: JsonElement? = get(key)
        return if (el == null || el.isJsonNull) "" else runCatching { el.asString }.getOrDefault("")
    }

    private fun JsonObject.strOrNull(key: String): String? {
        val el = get(key)
        return if (el == null || el.isJsonNull) null else runCatching { el.asString }.getOrNull()
    }

    private fun JsonObject.int(key: String, default: Int): Int {
        val el = get(key)
        return if (el == null || el.isJsonNull) default else runCatching { el.asInt }.getOrDefault(default)
    }

    private fun JsonObject.long(key: String, default: Long): Long {
        val el = get(key)
        return if (el == null || el.isJsonNull) default else runCatching { el.asLong }.getOrDefault(default)
    }

    private fun JsonObject.bool(key: String, default: Boolean): Boolean {
        val el = get(key)
        return if (el == null || el.isJsonNull) default else runCatching { el.asBoolean }.getOrDefault(default)
    }
}
