package si.skladisce.app.data

import si.skladisce.app.data.db.ArticleEntity
import si.skladisce.app.data.db.MovementLogEntity
import si.skladisce.app.data.db.StockLocationEntity
import si.skladisce.app.data.db.WarehouseDao
import si.skladisce.app.data.db.WarehouseEntity
import si.skladisce.app.data.model.WarehouseBusinessLogic

object DemoCatalogSeeder {
    fun defaultWarehouses(): List<WarehouseEntity> = listOf(
        WarehouseEntity("trgovina", "Prodajalna (Police)", isStore = true, isDefault = true, colorHex = "#38bdf8"),
        WarehouseEntity("zgornje", "Zgornje skladišče (Soft)", isStore = false, isDefault = true, colorHex = "#10b981"),
        WarehouseEntity("spodnje", "Spodnje skladišče (Pohištvo)", isStore = false, isDefault = true, colorHex = "#f59e0b")
    )

    suspend fun ensureDefaultWarehouses(dao: WarehouseDao) {
        if (dao.getAllWarehouses().isEmpty()) {
            dao.upsertWarehouses(defaultWarehouses())
        }
    }

    private data class DemoBoxDef(
        val boxNum: Int,
        val shelf: String,
        val count: Int,
        val warehouse: String = "spodnje"
    )

    private data class DemoArticleDef(
        val sku: String,
        val name: String,
        val category: String,
        val boxTotal: Int = 1,
        val minQuantity: Int = 1,
        val unit: String = "kos",
        val categoryType: String = "furniture",
        val primaryWarehouse: String = "spodnje",
        val shelf: String = "Regal A-01",
        val count: Int = 1,
        val notes: String = "",
        val boxes: List<DemoBoxDef>? = null
    )

    suspend fun seedDemoData(dao: WarehouseDao, deviceId: String) {
        ensureDefaultWarehouses(dao)
        val now = WarehouseBusinessLogic.isoNow()

        val defs = listOf(
            DemoArticleDef("3600123", "Vrtni stol MELLBY jeklo/tekstilen črna", "Vrtno pohištvo", 1, 4, "kos", "furniture", "spodnje", "Regal A-01", 12, "Enodelni stol, zložljiv"),
            DemoArticleDef("3600456", "Vrtna miza SANDVIG Š82xD140 siva", "Vrtno pohištvo", 1, 2, "kos", "furniture", "spodnje", "Regal A-03", 1, "Zadnji kos na zalogi"),
            DemoArticleDef(
                sku = "3651003",
                name = "Garderobna omara TARP 3 vrata bela",
                category = "Pohištvo",
                boxTotal = 3,
                minQuantity = 1,
                unit = "garnitura",
                notes = "Velika omara 150x200 cm, 3 škatle",
                boxes = listOf(
                    DemoBoxDef(1, "Regal G-01 (ogrodje)", 3, "spodnje"),
                    DemoBoxDef(2, "Regal G-02 (police)", 3, "spodnje"),
                    DemoBoxDef(3, "Regal G-03 (vrata z ogledalom)", 3, "spodnje")
                )
            ),
            DemoArticleDef(
                sku = "3698710",
                name = "Posteljni okvir ABILD 160x200 hrast",
                category = "Vzmetnice & Nadvložki",
                boxTotal = 2,
                minQuantity = 2,
                unit = "garnitura",
                notes = "2 cela kompleta, 3x Box 1/2 je viška (manjka 3x Box 2/2)",
                boxes = listOf(
                    DemoBoxDef(1, "Regal P-01 (vzglavje)", 5, "spodnje"),
                    DemoBoxDef(2, "Regal P-02 (okvir & stranice)", 2, "spodnje")
                )
            ),
            DemoArticleDef(
                sku = "3618930",
                name = "Predalnik LIMFJORDEN 4 predali bela",
                category = "Pohištvo",
                boxTotal = 2,
                minQuantity = 2,
                unit = "garnitura",
                notes = "JYSK Predalnik LIMFJORDEN (Škatla 1/2 razdeljena na P-04 in P-09 rezervo)",
                boxes = listOf(
                    DemoBoxDef(1, "Regal P-04 (ogrodje)", 4, "spodnje"),
                    DemoBoxDef(1, "Regal P-09 (rezerva)", 2, "spodnje"),
                    DemoBoxDef(2, "Regal P-05 (predali)", 6, "spodnje")
                )
            ),
            DemoArticleDef("3670619", "DIVERSES", "Splošno", 1, 2, "kos", "soft", "zgornje", "Regal D-01", 8, "Pomožni artikel za račune"),
            DemoArticleDef("3601370", "Zložljiv stol VOEL žamet temno siva", "Stoli & Mize", 1, 2, "kos", "soft", "zgornje", "Regal S-01", 6, "Zložljivi stoli (Zgornje skladišče)"),
            DemoArticleDef("3600634", "Računalniška miza HALSTED 60x120 črna", "Pohištvo", 1, 1, "kos", "furniture", "spodnje", "Regal M-02", 4, "Pisarniške in računalniške mize"),
            DemoArticleDef("3680079", "Gaming stol RANDRUP črno umetno usnje/mr", "Pohištvo", 1, 1, "kos", "furniture", "spodnje", "Regal S-04", 3, "Gaming stoli v škatlah"),
            DemoArticleDef("3600628", "Klubska mizica RANDERUP Ø47 črna", "Pohištvo", 1, 2, "kos", "furniture", "spodnje", "Regal M-05", 5, "Klubske mizice"),
            DemoArticleDef("3650044", "Pisarniška miza ASTRUP 60x120 črna", "Mize & Pisarna", 1, 2, "kos", "furniture", "spodnje", "Regal M-01", 4, "Desk ASTRUP 60x120 black"),
            DemoArticleDef("3640447", "Miza MARSTRAND 50x100 hrast", "Mize & Pisarna", 1, 2, "kos", "furniture", "spodnje", "Regal M-03", 3, "Desk MARSTRAND 50x100 n.oak"),
            DemoArticleDef("3690176", "Gaming miza LINDHOLM z LED črna", "Mize & Pisarna", 1, 2, "kos", "furniture", "spodnje", "Regal M-04", 2, "Gaming desk LINDHOLM w/LED black"),
            DemoArticleDef(
                sku = "3630132",
                name = "Pisarniška miza LIMFJORDEN 60x120 beljen hrast",
                category = "Mize & Pisarna",
                boxTotal = 2,
                minQuantity = 2,
                unit = "garnitura",
                notes = "Desk LIMFJORDEN 60x120 4 draw. w.oak",
                boxes = listOf(
                    DemoBoxDef(1, "Regal M-06 (plošča)", 2, "spodnje"),
                    DemoBoxDef(2, "Regal M-06 (predalnik)", 2, "spodnje")
                )
            ),
            DemoArticleDef("3601257", "Pisarniški stol TAMDRUP črna", "Pohištvo", 1, 2, "kos", "furniture", "spodnje", "Regal S-02", 3, "Office chair TAMDRUP black"),
            DemoArticleDef("3699065", "Gaming stol ABILDAA antracit siva", "Pohištvo", 1, 1, "kos", "furniture", "spodnje", "Regal S-03", 2, "Gaming chair ABILDAA anthracite grey"),
            DemoArticleDef("3601323", "Pisarniški stol BILLUM črna", "Pohištvo", 1, 2, "kos", "furniture", "spodnje", "Regal S-05", 5, "Office chair BILLUM black"),
            DemoArticleDef("3650203", "Jedilna miza ALSTED Ø120 hrast", "Pohištvo", 1, 2, "kos", "furniture", "spodnje", "Regal J-01", 2, "Dining table ALSTED Ø120 n.oak"),
            DemoArticleDef("3620225", "Jedilna garnitura BREDSTRUP", "Pohištvo", 1, 1, "garnitura", "furniture", "spodnje", "Regal J-02", 1, "BREDSTRUP L92 + 2 klopi"),
            DemoArticleDef("3690498", "Jedilni stol KASTRUP črna/hrast", "Pohištvo", 1, 4, "kos", "furniture", "spodnje", "Regal J-03", 6, "Dining chair KASTRUP black/oak"),
            DemoArticleDef(
                sku = "3670522",
                name = "Predalnik TANGE 6 predalov bela",
                category = "Pohištvo",
                boxTotal = 2,
                minQuantity = 2,
                unit = "garnitura",
                notes = "6 drawer chest TANGE white",
                boxes = listOf(
                    DemoBoxDef(1, "Regal P-06 (okvir)", 3, "spodnje"),
                    DemoBoxDef(2, "Regal P-06 (predali)", 3, "spodnje")
                )
            ),
            DemoArticleDef(
                sku = "3601026",
                name = "Komoda JENSLEV 2 vrata 4 predali hrast",
                category = "Pohištvo",
                boxTotal = 2,
                minQuantity = 1,
                unit = "garnitura",
                notes = "Sideboard JENSLEV 2 doors 4 drawers oak",
                boxes = listOf(
                    DemoBoxDef(1, "Regal P-07 (korpus)", 2, "spodnje"),
                    DemoBoxDef(2, "Regal P-07 (vrata/predali)", 2, "spodnje")
                )
            ),
            DemoArticleDef(
                sku = "3659498",
                name = "Komoda TAPDRUP hrast",
                category = "Pohištvo",
                boxTotal = 2,
                minQuantity = 2,
                unit = "garnitura",
                notes = "TAPDRUP oak",
                boxes = listOf(
                    DemoBoxDef(1, "Regal P-08 (ogrodje)", 4, "spodnje"),
                    DemoBoxDef(2, "Regal P-08 (predali)", 4, "spodnje")
                )
            ),
            DemoArticleDef(
                sku = "3659497",
                name = "Komoda TAPDRUP bela",
                category = "Pohištvo",
                boxTotal = 2,
                minQuantity = 2,
                unit = "garnitura",
                notes = "TAPDRUP white",
                boxes = listOf(
                    DemoBoxDef(1, "Regal P-08 (ogrodje)", 4, "spodnje"),
                    DemoBoxDef(2, "Regal P-08 (predali)", 4, "spodnje")
                )
            ),
            DemoArticleDef("3670644", "Predalnik KORUP 1 predal 2 vrata bela", "Pohištvo", 1, 2, "kos", "furniture", "spodnje", "Regal P-10", 2, "COD KORUP 1 drawer 2 doors white"),
            DemoArticleDef("3601401", "Predalnik JENSLEV 4 predali 1 vrata bela", "Pohištvo", 1, 2, "kos", "furniture", "spodnje", "Regal P-11", 2, "4 drawer 1 door chest JENSLEV white"),
            DemoArticleDef(
                sku = "3618939",
                name = "Predalnik LIMFJORDEN 4 predali bela",
                category = "Pohištvo",
                boxTotal = 2,
                minQuantity = 2,
                unit = "garnitura",
                notes = "COD LIMFJORDEN 4 drawers white",
                boxes = listOf(
                    DemoBoxDef(1, "Regal P-05 (ogrodje)", 3, "spodnje"),
                    DemoBoxDef(2, "Regal P-05 (predali)", 3, "spodnje")
                )
            ),
            DemoArticleDef("3670318", "Zložljiv stol VIUF siva", "Stoli & Mize", 1, 4, "kos", "soft", "zgornje", "Regal S-06", 8, "Folding chair VIUF grey"),
            DemoArticleDef("3670064", "Omarica za nakit MALLING z ogledalom bela", "Pohištvo", 1, 2, "kos", "furniture", "spodnje", "Regal O-01", 3, "Jewellery cabinet MALLING w/mirror white"),
            DemoArticleDef(
                sku = "3601337",
                name = "Pisarniška miza STAUNING 80x160 bela",
                category = "Mize & Pisarna",
                boxTotal = 2,
                minQuantity = 1,
                unit = "garnitura",
                notes = "Desk STAUNING 80x160 white",
                boxes = listOf(
                    DemoBoxDef(1, "Regal M-07 (mizna plošča)", 2, "spodnje"),
                    DemoBoxDef(2, "Regal M-07 (noge/ogrodje)", 2, "spodnje")
                )
            ),
            DemoArticleDef(
                sku = "3681951",
                name = "Posteljni okvir BILLUND 90x200 bela/beton",
                category = "Vzmetnice & Nadvložki",
                boxTotal = 2,
                minQuantity = 1,
                unit = "garnitura",
                notes = "Bed frame BILLUND 90x200 white/concrete",
                boxes = listOf(
                    DemoBoxDef(1, "Regal P-03 (vzglavje)", 2, "spodnje"),
                    DemoBoxDef(2, "Regal P-03 (stranice)", 2, "spodnje")
                )
            ),
            DemoArticleDef(
                sku = "3670214",
                name = "Garderobna omara LIMFJORDEN 120x200 bela",
                category = "Pohištvo",
                boxTotal = 4,
                minQuantity = 1,
                unit = "garnitura",
                notes = "Wardrobe LIMFJORDEN combi 120x200 white",
                boxes = listOf(
                    DemoBoxDef(1, "Regal G-04 (ogrodje)", 2, "spodnje"),
                    DemoBoxDef(2, "Regal G-04 (police)", 2, "spodnje"),
                    DemoBoxDef(3, "Regal G-04 (vrata)", 2, "spodnje"),
                    DemoBoxDef(4, "Regal G-04 (predali)", 2, "spodnje")
                )
            ),
            DemoArticleDef("3670545", "Ogledalo ENGLERUP z 12 LED bela", "Pohištvo", 1, 2, "kos", "furniture", "spodnje", "Regal O-02", 4, "Mirror ENGLERUP w/12 LED light bulbs white"),
            DemoArticleDef(
                sku = "3601174",
                name = "Klubska mizica AGERBY 60x100 steklo/hrast",
                category = "Pohištvo",
                boxTotal = 2,
                minQuantity = 1,
                unit = "garnitura",
                notes = "Coffee table AGERBY 60x100 glass/oak",
                boxes = listOf(
                    DemoBoxDef(1, "Regal M-08 (steklo)", 2, "spodnje"),
                    DemoBoxDef(2, "Regal M-08 (leseni podstavek)", 2, "spodnje")
                )
            ),
            DemoArticleDef("3610137", "Klubska mizica LYNGS Ø59 hrast", "Pohištvo", 1, 2, "kos", "furniture", "spodnje", "Regal M-09", 3, "Coffee table LYNGS Ø59 w.oak"),
            DemoArticleDef("3650192", "Vložek za omaro BARKHOLT 4 žične košare", "Pohištvo", 1, 2, "kos", "soft", "zgornje", "Regal G-05", 5, "Wardrobe insert BARKHOLT W42xD45 4 wire"),
            DemoArticleDef("3640200", "Fotelj HVILSTED z naslonom za noge umazano bela", "Pohištvo", 1, 1, "garnitura", "furniture", "spodnje", "Regal F-01", 2, "Armchair/footstool HVILSTED off-white")
        )

        dao.runInTransaction {
            val articles = mutableListOf<ArticleEntity>()
            val stocks = mutableListOf<StockLocationEntity>()
            val logs = mutableListOf<MovementLogEntity>()

            for (item in defs) {
                articles.add(
                    ArticleEntity(
                        sku = item.sku,
                        name = item.name,
                        category = item.category,
                        boxTotal = item.boxTotal,
                        minQuantity = item.minQuantity,
                        categoryType = item.categoryType,
                        primaryWarehouse = item.primaryWarehouse,
                        unit = item.unit,
                        notes = item.notes,
                        lastUpdated = now
                    )
                )

                val boxDefs = item.boxes ?: listOf(DemoBoxDef(1, item.shelf, item.count, item.primaryWarehouse))
                boxDefs.forEachIndexed { idx, b ->
                    stocks.add(
                        StockLocationEntity(
                            id = WarehouseBusinessLogic.buildStockId(item.sku, b.boxNum, b.shelf),
                            sku = item.sku,
                            boxNum = b.boxNum,
                            shelf = b.shelf,
                            warehouse = b.warehouse,
                            count = b.count,
                            isPrimary = (idx == 0),
                            lastUpdated = now
                        )
                    )
                }

                val totalQty = item.boxes?.minOfOrNull { it.count } ?: item.count
                logs.add(
                    MovementLogEntity(
                        sku = item.sku,
                        itemName = item.name,
                        boxNum = 1,
                        shelf = item.shelf,
                        changeType = "create",
                        changeAmount = totalQty,
                        resultingQty = totalQty,
                        note = "Začetni vzorčni vnos",
                        deviceId = deviceId,
                        timestamp = now
                    )
                )
            }

            dao.upsertArticles(articles)
            dao.upsertStocks(stocks)
            dao.insertLogs(logs)
        }
    }
}
