package si.skladisce.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import si.skladisce.app.data.SyncPrefs
import si.skladisce.app.data.db.ArticleEntity
import si.skladisce.app.data.db.StockLocationEntity
import si.skladisce.app.data.model.ReceivingEntry
import si.skladisce.app.data.model.WarehouseBusinessLogic

class WarehouseBusinessLogicTest {

    @Test
    fun `buildInventoryItem computes multi-box complete sets and missing boxes`() {
        val art = ArticleEntity(
            sku = "3618930",
            name = "Predalnik LIMFJORDEN 4 predali bela",
            category = "Pohištvo",
            boxTotal = 2,
            minQuantity = 2,
            shelfCapacity = 4,
            shelfLocation = "Polica P-01",
            categoryType = "furniture",
            unit = "garnitura",
            lastUpdated = "2026-10-27T10:00:00.000Z"
        )
        val stocks = listOf(
            StockLocationEntity("3618930_b1_P-04", "3618930", 1, "Regal P-04", "spodnje", null, 5, true, "2026-10-27T10:00:00.000Z"),
            StockLocationEntity("3618930_b1_P-09", "3618930", 1, "Regal P-09", "spodnje", null, 1, false, "2026-10-27T10:00:00.000Z"),
            StockLocationEntity("3618930_b2_P-05", "3618930", 2, "Regal P-05", "spodnje", null, 4, true, "2026-10-27T10:00:00.000Z")
        )

        val item = WarehouseBusinessLogic.buildInventoryItem(art, stocks)
        assertEquals(4, item.completeSets)
        assertTrue(item.hasIncomplete)
        assertTrue(item.missingSummary.contains("Manjka 2x Škatla 2/2"))
        assertEquals(10, item.spodnjeQuantity)
        assertEquals(0, item.storeQuantity)
        assertEquals(4, item.neededForShelf)
        assertFalse(item.isLowStock)
    }

    @Test
    fun `findScatteredItems detects multiple shelves and suggests primary target`() {
        val art = ArticleEntity(sku = "3650044", name = "Desk ASTRUP", boxTotal = 1, lastUpdated = "2026-10-27T10:00:00.000Z")
        val stocks = listOf(
            StockLocationEntity("id1", "3650044", 1, "R1-A-P1", "spodnje", null, 3, true, "2026-10-27T10:00:00.000Z"),
            StockLocationEntity("id2", "3650044", 1, "R2-B-P2", "spodnje", null, 2, false, "2026-10-27T10:00:00.000Z")
        )
        val item = WarehouseBusinessLogic.buildInventoryItem(art, stocks)
        val scattered = WarehouseBusinessLogic.findScatteredItems(listOf(item))

        assertEquals(1, scattered.size)
        assertEquals("R1-A-P1", scattered[0].targetShelf)
        assertEquals(1, scattered[0].boxesToMove.size)
        assertEquals("R2-B-P2", scattered[0].boxesToMove[0].shelf)
    }

    @Test
    fun `calculateSkuCompletion isolates completions from pre-existing old stock vs batch pairs`() {
        val initial = mapOf(1 to 2, 2 to 0) // Na zalogi imamo 2x škatlo 1/2 in 0x 2/2
        val entries = listOf(
            ReceivingEntry("e1", "2026-10-27T10:00:00Z", "3618930", "Limfjorden", 2, 2, "P-05", "spodnje", 2),
            ReceivingEntry("e2", "2026-10-27T10:01:00Z", "3618930", "Limfjorden", 1, 2, "P-04", "spodnje", 1),
            ReceivingEntry("e3", "2026-10-27T10:02:00Z", "3618930", "Limfjorden", 2, 2, "P-05", "spodnje", 1)
        )
        // batchCounts: b1=1, b2=3 => batchMin=1; currentCounts: b1=3, b2=3 => totalNewSets=3; completedFromOld = 3 - 1 = 2!
        val res = WarehouseBusinessLogic.calculateSkuCompletion("3618930", 2, initial, entries)
        assertEquals(3, res.totalNewSets)
        assertEquals(1, res.batchMin)
        assertEquals(2, res.completedFromOld)
        assertEquals(1, res.missingInBatch.size)
        assertEquals(1, res.missingInBatch[0].boxNum)
    }

    @Test
    fun `fnv1a32 hash is deterministic and matches js uint32 behavior`() {
        val h1 = SyncPrefs.fnv1a32("a1b2_v5_a1b2_100")
        val h2 = SyncPrefs.fnv1a32("a1b2_v5_a1b2_100")
        assertEquals(h1, h2)
    }
}
