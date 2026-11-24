package si.skladisce.app.data.model

import si.skladisce.app.data.db.ArticleEntity
import si.skladisce.app.data.db.StockLocationEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

data class ShelfLocEntry(
    val name: String,
    val count: Int,
    val isPrimary: Boolean = false,
    val warehouse: String = "zgornje",
    val boxId: String? = null
)

data class BoxStockSummary(
    val boxNum: Int,
    val boxTotal: Int,
    val count: Int,
    val location: String,
    val locations: List<ShelfLocEntry>
)

data class InventoryItem(
    val sku: String,
    val name: String,
    val category: String,
    val boxTotal: Int,
    val boxInfo: String,
    val minQuantity: Int,
    val unit: String,
    val notes: String,
    val lastUpdated: String,
    val primaryShelf: String,
    val completeSets: Int,
    val boxes: Map<Int, BoxStockSummary>,
    val breakdown: List<BoxStockSummary>,
    val hasIncomplete: Boolean,
    val missingSummary: String,
    val shelfCapacity: Int,
    val shelfLocation: String,
    val categoryType: String,
    val primaryWarehouse: String,
    val storeQuantity: Int,
    val warehouseQuantity: Int,
    val zgornjeQuantity: Int,
    val spodnjeQuantity: Int,
    val neededForShelf: Int,
    val mixedBoxIds: List<String>
) {
    val isLowStock: Boolean
        get() = completeSets <= minQuantity && minQuantity > 0
}

data class ScatteredBoxLoc(
    val boxNum: Int,
    val boxTotal: Int,
    val shelf: String,
    val warehouse: String,
    val count: Int,
    val isPrimary: Boolean
)

data class ScatteredItem(
    val sku: String,
    val name: String,
    val category: String,
    val boxTotal: Int,
    val completeSets: Int,
    val locations: List<ScatteredBoxLoc>,
    val distinctShelves: List<String>,
    val targetShelf: String,
    val boxesToMove: List<ScatteredBoxLoc>
)

data class MixedBoxItem(
    val sku: String,
    val name: String,
    val qty: Int
)

data class MixedBoxWithItems(
    val boxId: String,
    val boxNumber: String,
    val warehouse: String,
    val shelf: String,
    val notes: String,
    val totalItems: Int,
    val items: List<MixedBoxItem>,
    val createdAt: String,
    val updatedAt: String
)

data class ReceivingEntry(
    val id: String,
    val timestamp: String,
    val sku: String,
    val name: String,
    val boxNum: Int,
    val boxTotal: Int,
    val shelf: String,
    val warehouse: String,
    val count: Int
)

data class MissingBatchBox(
    val boxNum: Int,
    val boxTotal: Int,
    val missingCount: Int,
    val receivedCount: Int
)

data class SkuCompletionResult(
    val sku: String,
    val boxTotal: Int,
    val initCounts: Map<Int, Int>,
    val batchCounts: Map<Int, Int>,
    val currentCounts: Map<Int, Int>,
    val initMin: Int,
    val currentMin: Int,
    val batchMin: Int,
    val totalNewSets: Int,
    val completedFromOld: Int,
    val missingInBatch: List<MissingBatchBox>
)

data class ReceivingSessionModel(
    val id: String,
    val name: String,
    val warehouse: String,
    val active: Boolean,
    val startedAt: String,
    val finishedAt: String?,
    val initialStock: Map<String, Map<Int, Int>>,
    val entries: List<ReceivingEntry>,
    val totalBoxes: Int,
    val uniqueSkusCount: Int,
    val totalCompletedSets: Int,
    val incompleteItemsCount: Int
)

data class AuditEntry(
    val key: String, // "${sku}_b${boxNum}_${shelf}"
    val sku: String,
    val name: String,
    val category: String,
    val boxNum: Int,
    val boxTotal: Int,
    val shelf: String,
    val warehouse: String,
    val bookQty: Int,
    val countedQty: Int,
    val isCounted: Boolean
) {
    val diff: Int get() = if (isCounted) countedQty - bookQty else 0
}

data class DispatchBoxPick(
    val boxNum: Int,
    val boxTotal: Int,
    val shelf: String,
    val warehouse: String,
    val stockAvailable: Int,
    val picked: Boolean = false
)

data class DispatchItemState(
    val sku: String,
    val name: String,
    val qty: Int,
    val price: String,
    val existsInDb: Boolean,
    val boxPicks: List<DispatchBoxPick>
) {
    val isAllPicked: Boolean get() = boxPicks.isNotEmpty() && boxPicks.all { it.picked }
}

data class SyncMergeResult(
    val addedArticles: Int = 0,
    val updatedRecords: Int = 0,
    val addedLogs: Int = 0,
    val mixedBoxes: Int = 0,
    val replenishments: Int = 0
)

object WarehouseBusinessLogic {
    private val softRegex = Regex(
        "tekstil|blazin|odej|brisa|postelj|rjuhe|vzglavnik|soft|copat|zaves|preprog|rug|cushion|pillow",
        RegexOption.IGNORE_CASE
    )

    fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    fun formatDisplayDateTime(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            val clean = iso.replace("Z", "")
            val parser = SimpleDateFormat(
                if (clean.contains(".")) "yyyy-MM-dd'T'HH:mm:ss.SSS" else "yyyy-MM-dd'T'HH:mm:ss",
                Locale.US
            )
            parser.timeZone = TimeZone.getTimeZone("UTC")
            val date = parser.parse(clean) ?: return iso.take(16)
            val out = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("sl", "SI"))
            out.timeZone = TimeZone.getDefault()
            out.format(date)
        } catch (_: Exception) {
            iso.take(16).replace("T", " ")
        }
    }

    fun formatShortDate(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            val clean = iso.replace("Z", "")
            val parser = SimpleDateFormat(
                if (clean.contains(".")) "yyyy-MM-dd'T'HH:mm:ss.SSS" else "yyyy-MM-dd'T'HH:mm:ss",
                Locale.US
            )
            parser.timeZone = TimeZone.getTimeZone("UTC")
            val date = parser.parse(clean) ?: return iso.take(10)
            val out = SimpleDateFormat("dd.MM.yyyy", Locale("sl", "SI"))
            out.timeZone = TimeZone.getDefault()
            out.format(date)
        } catch (_: Exception) {
            iso.take(10)
        }
    }

    fun inferWarehouseFromShelf(shelf: String): String {
        val trimmed = shelf.trim()
        return if (trimmed.startsWith("TRG", ignoreCase = true) ||
            trimmed.startsWith("Polica", ignoreCase = true) ||
            trimmed.startsWith("Prodajna", ignoreCase = true)
        ) {
            "trgovina"
        } else {
            "zgornje"
        }
    }

    fun buildStockId(sku: String, boxNum: Int, shelf: String, boxId: String? = null): String {
        val cleanSku = sku.trim()
        val cleanShelf = shelf.trim().ifEmpty { "Brez lokacije" }
        return if (!boxId.isNullOrBlank()) {
            "${cleanSku}_b${boxNum}_${cleanShelf}_box_${boxId.trim()}"
        } else {
            "${cleanSku}_b${boxNum}_${cleanShelf}"
        }
    }

    fun buildInventoryItem(art: ArticleEntity, stocks: List<StockLocationEntity>): InventoryItem {
        val sku = art.sku.trim()
        val boxTotal = max(1, art.boxTotal)
        val byBox = mutableMapOf<Int, MutableList<StockLocationEntity>>()
        for (b in 1..boxTotal) byBox[b] = mutableListOf()

        for (s in stocks) {
            val bNum = min(boxTotal, max(1, s.boxNum))
            byBox.getOrPut(bNum) { mutableListOf() }.add(s)
        }

        var primaryShelf = "Brez lokacije"
        var storeQty = 0
        var zgornjeQty = 0
        var spodnjeQty = 0
        val mixedIds = linkedSetOf<String>()
        val boxesMap = linkedMapOf<Int, BoxStockSummary>()
        val breakdown = mutableListOf<BoxStockSummary>()
        val boxCounts = mutableListOf<Int>()

        for (b in 1..boxTotal) {
            val locRows = byBox[b].orEmpty()
            val formattedLocs = if (locRows.isEmpty()) {
                listOf(ShelfLocEntry(name = "Brez lokacije", count = 0, isPrimary = true, warehouse = "zgornje", boxId = null))
            } else {
                locRows.map { row ->
                    val cnt = max(0, row.count)
                    val wh = row.warehouse.ifBlank { inferWarehouseFromShelf(row.shelf) }
                    when (wh) {
                        "trgovina" -> storeQty += cnt
                        "spodnje" -> spodnjeQty += cnt
                        else -> zgornjeQty += cnt
                    }
                    if (!row.boxId.isNullOrBlank()) {
                        mixedIds.add(row.boxId)
                    }
                    ShelfLocEntry(
                        name = row.shelf.ifBlank { "Brez lokacije" },
                        count = cnt,
                        isPrimary = row.isPrimary,
                        warehouse = wh,
                        boxId = row.boxId
                    )
                }
            }

            val totalCount = formattedLocs.sumOf { it.count }
            boxCounts.add(totalCount)

            val activePrimary = formattedLocs.firstOrNull { it.isPrimary } ?: formattedLocs.first()
            if (b == 1 && activePrimary.name != "Brez lokacije") {
                primaryShelf = activePrimary.name
            }

            val summary = if (formattedLocs.size <= 1) {
                formattedLocs.firstOrNull()?.name ?: "Brez lokacije"
            } else {
                formattedLocs.joinToString(" / ") { l ->
                    val boxTag = if (!l.boxId.isNullOrBlank()) " (Škatla #${l.boxId})" else ""
                    "${l.name}$boxTag (${l.count} kos)"
                }
            }

            val boxSummary = BoxStockSummary(
                boxNum = b,
                boxTotal = boxTotal,
                count = totalCount,
                location = summary,
                locations = formattedLocs
            )
            boxesMap[b] = boxSummary
            breakdown.add(boxSummary)
        }

        val minCount = boxCounts.minOrNull() ?: 0
        val maxCount = boxCounts.maxOrNull() ?: 0
        val completeSets = if (boxTotal > 1) minCount else (boxCounts.firstOrNull() ?: 0)

        val missing = mutableListOf<String>()
        if (boxTotal > 1) {
            for (b in breakdown) {
                if (b.count < maxCount) {
                    missing.add("Manjka ${maxCount - b.count}x Škatla ${b.boxNum}/$boxTotal")
                }
            }
        }
        val hasIncomplete = (boxTotal > 1) && (maxCount > 0) && (minCount < maxCount || missing.isNotEmpty())

        val shelfCap = max(0, art.shelfCapacity)
        val neededForShelf = if (shelfCap > 0) max(0, shelfCap - storeQty) else 0
        val catType = art.categoryType.ifBlank {
            if (softRegex.containsMatchIn("${art.category} ${art.name}")) "soft" else "furniture"
        }

        return InventoryItem(
            sku = sku,
            name = art.name.ifBlank { "Artikel $sku" },
            category = art.category.ifBlank { "Splošno" },
            boxTotal = boxTotal,
            boxInfo = "Box 1/$boxTotal",
            minQuantity = max(0, art.minQuantity),
            unit = art.unit.ifBlank { if (boxTotal > 1) "garnitura" else "kos" },
            notes = art.notes,
            lastUpdated = art.lastUpdated,
            primaryShelf = primaryShelf,
            completeSets = completeSets,
            boxes = boxesMap,
            breakdown = breakdown,
            hasIncomplete = hasIncomplete,
            missingSummary = missing.joinToString(", "),
            shelfCapacity = shelfCap,
            shelfLocation = art.shelfLocation,
            categoryType = catType,
            primaryWarehouse = art.primaryWarehouse,
            storeQuantity = storeQty,
            warehouseQuantity = zgornjeQty + spodnjeQty,
            zgornjeQuantity = zgornjeQty,
            spodnjeQuantity = spodnjeQty,
            neededForShelf = neededForShelf,
            mixedBoxIds = mixedIds.toList()
        )
    }

    fun findScatteredItems(items: List<InventoryItem>): List<ScatteredItem> {
        val result = mutableListOf<ScatteredItem>()
        for (item in items) {
            val locEntries = mutableListOf<ScatteredBoxLoc>()
            val distinctShelves = linkedSetOf<String>()
            for (b in item.breakdown) {
                for (loc in b.locations) {
                    val locName = loc.name.trim()
                    if (loc.count > 0 && locName.isNotEmpty() && locName != "Brez lokacije" && loc.boxId == null) {
                        distinctShelves.add(locName)
                        locEntries.add(
                            ScatteredBoxLoc(
                                boxNum = b.boxNum,
                                boxTotal = item.boxTotal,
                                shelf = locName,
                                warehouse = loc.warehouse,
                                count = loc.count,
                                isPrimary = loc.isPrimary
                            )
                        )
                    }
                }
            }
            if (distinctShelves.size > 1) {
                val primaryLoc = locEntries.firstOrNull { it.isPrimary }
                val targetShelf = primaryLoc?.shelf ?: distinctShelves.first()
                val toMove = locEntries.filter { it.shelf != targetShelf }
                result.add(
                    ScatteredItem(
                        sku = item.sku,
                        name = item.name,
                        category = item.category,
                        boxTotal = item.boxTotal,
                        completeSets = item.completeSets,
                        locations = locEntries,
                        distinctShelves = distinctShelves.toList(),
                        targetShelf = targetShelf,
                        boxesToMove = toMove
                    )
                )
            }
        }
        return result
    }

    /**
     * Izračuna kompletiranje garnitur v prevzemni pošiljki (identično pravilo kot v app.js):
     * - Samo škatle, ki dejansko dopolnijo manjkajoče škatle iz predhodne zaloge, štejejo kot kompletirano iz stare zaloge.
     */
    fun calculateSkuCompletion(
        sku: String,
        boxTotal: Int,
        initialBoxCounts: Map<Int, Int> = emptyMap(),
        entries: List<ReceivingEntry> = emptyList()
    ): SkuCompletionResult {
        val bTotal = max(1, boxTotal)
        val skuEntries = entries.filter { it.sku.trim() == sku.trim() }

        val batchCounts = mutableMapOf<Int, Int>()
        val initCounts = mutableMapOf<Int, Int>()
        val currentCounts = mutableMapOf<Int, Int>()

        for (b in 1..bTotal) {
            batchCounts[b] = 0
            initCounts[b] = initialBoxCounts[b] ?: 0
        }
        for (entry in skuEntries) {
            val b = min(bTotal, max(1, entry.boxNum))
            batchCounts[b] = (batchCounts[b] ?: 0) + max(0, entry.count)
        }
        for (b in 1..bTotal) {
            currentCounts[b] = (initCounts[b] ?: 0) + (batchCounts[b] ?: 0)
        }

        val initMin = initCounts.values.minOrNull() ?: 0
        val batchMin = batchCounts.values.minOrNull() ?: 0
        val currentMin = currentCounts.values.minOrNull() ?: 0

        val totalNewSets = max(0, currentMin - initMin)
        val completedFromOld = if (bTotal > 1) max(0, totalNewSets - batchMin) else 0

        val batchMax = batchCounts.values.maxOrNull() ?: 0
        val missingInBatch = mutableListOf<MissingBatchBox>()
        if (bTotal > 1 && batchMax > 0) {
            for (b in 1..bTotal) {
                val rec = batchCounts[b] ?: 0
                if (rec < batchMax) {
                    missingInBatch.add(
                        MissingBatchBox(
                            boxNum = b,
                            boxTotal = bTotal,
                            missingCount = batchMax - rec,
                            receivedCount = rec
                        )
                    )
                }
            }
        }

        return SkuCompletionResult(
            sku = sku,
            boxTotal = bTotal,
            initCounts = initCounts,
            batchCounts = batchCounts,
            currentCounts = currentCounts,
            initMin = initMin,
            currentMin = currentMin,
            batchMin = batchMin,
            totalNewSets = totalNewSets,
            completedFromOld = completedFromOld,
            missingInBatch = missingInBatch
        )
    }
}
