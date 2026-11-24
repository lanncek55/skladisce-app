package si.skladisce.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "articles",
    indices = [
        Index(value = ["name"]),
        Index(value = ["category"])
    ]
)
data class ArticleEntity(
    @PrimaryKey val sku: String,
    val name: String,
    val category: String = "Splošno",
    val boxTotal: Int = 1,
    val minQuantity: Int = 0,
    val shelfCapacity: Int = 0,
    val shelfLocation: String = "",
    val categoryType: String = "furniture", // "soft" ali "furniture"
    val primaryWarehouse: String = "",      // "", "trgovina", "zgornje", "spodnje", ali custom
    val unit: String = "kos",
    val notes: String = "",
    val lastUpdated: String
)

@Entity(
    tableName = "stock_locations",
    indices = [
        Index(value = ["sku"]),
        Index(value = ["warehouse"]),
        Index(value = ["boxId"]),
        Index(value = ["shelf"])
    ]
)
data class StockLocationEntity(
    @PrimaryKey val id: String, // "${sku}_b${boxNum}_${shelf}${boxId?.let { "_box_$it" } ?: ""}"
    val sku: String,
    val boxNum: Int = 1,
    val shelf: String = "Brez lokacije",
    val warehouse: String = "zgornje",
    val boxId: String? = null,
    val count: Int = 0,
    val isPrimary: Boolean = false,
    val lastUpdated: String
)

@Entity(
    tableName = "logs",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["sku"])
    ]
)
data class MovementLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sku: String,
    val itemName: String,
    val boxNum: Int = 1,
    val shelf: String = "",
    val changeType: String, // "create", "in", "out", "adjust", "delete", "pack_mixed_box", "delete_mixed_box", "replenish_shelf", "transfer_stock"
    val changeAmount: Int = 0,
    val resultingQty: Int = 0,
    val note: String = "",
    val deviceId: String = "",
    val timestamp: String
)

@Entity(tableName = "warehouses")
data class WarehouseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isStore: Boolean = false,
    val isDefault: Boolean = false,
    val colorHex: String = "#38bdf8"
)

@Entity(tableName = "mixed_boxes")
data class MixedBoxEntity(
    @PrimaryKey val boxId: String,
    val boxNumber: String,
    val warehouse: String = "zgornje",
    val shelf: String = "Brez lokacije",
    val notes: String = "",
    val totalItems: Int = 0,
    val itemsJson: String = "[]",
    val createdAt: String,
    val updatedAt: String
)

@Entity(
    tableName = "replenishment_list",
    indices = [
        Index(value = ["sku"]),
        Index(value = ["status"]),
        Index(value = ["warehouse"])
    ]
)
data class ReplenishmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sku: String,
    val name: String,
    val neededQty: Int = 1,
    val currentShelfQty: Int = 0,
    val targetShelf: String = "Prodajne police",
    val warehouse: String = "zgornje", // sourceWarehouse
    val sourceLocation: String = "Ni zaloge v skladišču",
    val boxId: String? = null,
    val status: String = "pending", // "pending" ali "completed"
    val transferredQty: Int = 0,
    val createdAt: String,
    val completedAt: String? = null
)

@Entity(tableName = "receiving_sessions")
data class ReceivingSessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val warehouse: String = "all",
    val active: Boolean = true,
    val startedAt: String,
    val finishedAt: String? = null,
    val initialStockJson: String = "{}",
    val entriesJson: String = "[]",
    val totalBoxes: Int = 0,
    val uniqueSkusCount: Int = 0,
    val totalCompletedSets: Int = 0,
    val incompleteItemsCount: Int = 0
)
