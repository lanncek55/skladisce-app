package si.skladisce.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface WarehouseDao {
    // Flows za reaktivni UI
    @Query("SELECT * FROM articles ORDER BY name COLLATE NOCASE ASC")
    fun observeArticles(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM stock_locations")
    fun observeAllStocks(): Flow<List<StockLocationEntity>>

    @Query("SELECT * FROM warehouses ORDER BY isDefault DESC, name COLLATE NOCASE ASC")
    fun observeWarehouses(): Flow<List<WarehouseEntity>>

    @Query("SELECT * FROM mixed_boxes ORDER BY updatedAt DESC")
    fun observeMixedBoxes(): Flow<List<MixedBoxEntity>>

    @Query("SELECT * FROM replenishment_list ORDER BY createdAt DESC")
    fun observeReplenishments(): Flow<List<ReplenishmentEntity>>

    @Query("SELECT * FROM logs ORDER BY timestamp DESC LIMIT 500")
    fun observeLogs(): Flow<List<MovementLogEntity>>

    @Query("SELECT * FROM receiving_sessions WHERE active = 1 LIMIT 1")
    fun observeActiveReceivingSession(): Flow<ReceivingSessionEntity?>

    @Query("SELECT * FROM receiving_sessions WHERE active = 0 ORDER BY finishedAt DESC")
    fun observeArchivedReceivingSessions(): Flow<List<ReceivingSessionEntity>>

    // Direct suspend queries
    @Query("SELECT * FROM articles")
    suspend fun getAllArticles(): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE sku = :sku LIMIT 1")
    suspend fun getArticle(sku: String): ArticleEntity?

    @Query("SELECT COUNT(*) FROM articles")
    suspend fun getArticleCount(): Int

    @Query("SELECT * FROM stock_locations")
    suspend fun getAllStocks(): List<StockLocationEntity>

    @Query("SELECT * FROM stock_locations WHERE id = :id LIMIT 1")
    suspend fun getStockById(id: String): StockLocationEntity?

    @Query("SELECT * FROM stock_locations WHERE sku = :sku")
    suspend fun getStocksForSku(sku: String): List<StockLocationEntity>

    @Query("SELECT * FROM stock_locations WHERE boxId = :boxId")
    suspend fun getStocksForMixedBox(boxId: String): List<StockLocationEntity>

    @Query("SELECT * FROM warehouses")
    suspend fun getAllWarehouses(): List<WarehouseEntity>

    @Query("SELECT * FROM mixed_boxes")
    suspend fun getAllMixedBoxes(): List<MixedBoxEntity>

    @Query("SELECT * FROM mixed_boxes WHERE boxId = :boxId LIMIT 1")
    suspend fun getMixedBox(boxId: String): MixedBoxEntity?

    @Query("SELECT * FROM replenishment_list")
    suspend fun getAllReplenishments(): List<ReplenishmentEntity>

    @Query("SELECT * FROM replenishment_list WHERE id = :id LIMIT 1")
    suspend fun getReplenishmentById(id: Long): ReplenishmentEntity?

    @Query("SELECT * FROM logs ORDER BY timestamp DESC")
    suspend fun getAllLogs(): List<MovementLogEntity>

    @Query("SELECT * FROM receiving_sessions WHERE active = 1 LIMIT 1")
    suspend fun getActiveReceivingSession(): ReceivingSessionEntity?

    // Mutations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArticle(article: ArticleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArticles(articles: List<ArticleEntity>)

    @Query("DELETE FROM articles WHERE sku = :sku")
    suspend fun deleteArticle(sku: String)

    @Query("DELETE FROM articles")
    suspend fun clearArticles()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStock(stock: StockLocationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStocks(stocks: List<StockLocationEntity>)

    @Query("DELETE FROM stock_locations WHERE id = :id")
    suspend fun deleteStockById(id: String)

    @Query("DELETE FROM stock_locations WHERE sku = :sku")
    suspend fun deleteStocksForSku(sku: String)

    @Query("DELETE FROM stock_locations WHERE boxId = :boxId")
    suspend fun deleteStocksForMixedBox(boxId: String)

    @Query("DELETE FROM stock_locations")
    suspend fun clearStocks()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: MovementLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<MovementLogEntity>)

    @Query("DELETE FROM logs")
    suspend fun clearLogs()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWarehouse(warehouse: WarehouseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWarehouses(warehouses: List<WarehouseEntity>)

    @Query("DELETE FROM warehouses WHERE id = :id")
    suspend fun deleteWarehouse(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMixedBox(box: MixedBoxEntity)

    @Query("DELETE FROM mixed_boxes WHERE boxId = :boxId")
    suspend fun deleteMixedBox(boxId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReplenishment(rep: ReplenishmentEntity): Long

    @Query("DELETE FROM replenishment_list WHERE id = :id")
    suspend fun deleteReplenishment(id: Long)

    @Query("DELETE FROM replenishment_list WHERE status = 'completed'")
    suspend fun clearCompletedReplenishments()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReceivingSession(session: ReceivingSessionEntity)

    @Query("DELETE FROM receiving_sessions WHERE id = :id")
    suspend fun deleteReceivingSession(id: String)

    @Query("UPDATE receiving_sessions SET active = 0 WHERE active = 1")
    suspend fun deactivateAllReceivingSessions()

    @Transaction
    suspend fun runInTransaction(block: suspend () -> Unit) {
        block()
    }
}
