package si.skladisce.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.skladisce.app.scanner.NormRect
import si.skladisce.app.scanner.OcrTextBlock
import si.skladisce.app.scanner.WarehouseOcrParser

class WarehouseOcrParserTest {

    @Test
    fun `parse dual sku, explicit box fraction and direct name without catalog`() {
        val raw = """
            JYSK Nordic
            Product no.: 3601257 / 78480001
            Office chair TAMDRUP black
            Colli 1/2
            Gross weight: 14.5 kg
        """.trimIndent()

        val res = WarehouseOcrParser.parseWarehouseText(raw)
        assertNotNull(res)
        assertEquals("3601257", res!!.primarySku)
        assertEquals("78480001", res.secondarySku)
        assertEquals(1, res.boxCurrent)
        assertEquals(2, res.boxTotal)
        assertEquals("Office chair TAMDRUP black", res.name)
    }

    @Test
    fun `parse fused sku and standalone fraction`() {
        val raw = """
            3650044 - Desk ASTRUP 60x120 black
            45514002 1/2
            W60 x L120 x H75 cm
            Quantity: 1
        """.trimIndent()

        val res = WarehouseOcrParser.parseWarehouseText(raw)
        assertNotNull(res)
        assertEquals("3650044", res!!.primarySku)
        assertEquals(1, res.boxCurrent)
        assertEquals(2, res.boxTotal)
        assertEquals("Desk ASTRUP 60x120 black", res.name)
    }

    @Test
    fun `parse clipped 6 digit dash sku and explicit quantity pcs per pack`() {
        val raw = """
            650203-
            Dining table ALSTED Ø120 n.oak
            3 pcs/pk
            10.2 kg
        """.trimIndent()

        val res = WarehouseOcrParser.parseWarehouseText(raw)
        assertNotNull(res)
        assertEquals("3650203", res!!.primarySku)
        assertEquals("Dining table ALSTED Ø120 n.oak", res.name)
        assertEquals(3, res.quantityInBox)
    }

    @Test
    fun `ignore PO 455 prefix as primary SKU`() {
        assertFalse(WarehouseOcrParser.isPrimaryValid("4551400"))
        assertTrue(WarehouseOcrParser.isPrimaryValid("3650044"))
        assertTrue(WarehouseOcrParser.isValidEanChecksum("5713370040013"))
        assertFalse(WarehouseOcrParser.isValidEanChecksum("5713370040019"))
    }

    @Test
    fun `spatial clustering isolates two side-by-side boxes and picks centered box`() {
        val imgW = 1000
        val imgH = 1000
        val blocks = listOf(
            // Škatla na levem robu (daleč od centra)
            OcrTextBlock("3640447 - Desk MARSTRAND 50x100 n.oak", 20, 420, 260, 470),
            OcrTextBlock("Box 1/1", 20, 480, 120, 510),
            // Škatla na sredini (centrirana v vizirju)
            OcrTextBlock("3650044 - Desk ASTRUP 60x120 black", 420, 430, 620, 480),
            OcrTextBlock("Box 1/2", 420, 490, 540, 525)
        )

        val best = WarehouseOcrParser.findBestBoxCandidate(blocks, imgW, imgH, blocks.joinToString("\n") { it.text })
        assertNotNull(best)
        assertEquals("3650044", best!!.primarySku)
        assertEquals(2, best.boxTotal)
        assertEquals(1, best.otherBoxes.size)
        assertEquals("3640447", best.otherBoxes[0].primarySku)
        assertNotNull(best.boxRect)
        assertTrue(best.boxRect!!.centerX in 0.40f..0.65f)
    }

    @Test
    fun `unrotate normalized coordinates for 180 and 90 degrees`() {
        val r180 = WarehouseOcrParser.unrotateNormRect(NormRect(0.2f, 0.1f, 0.4f, 0.3f), 180)
        assertEquals(0.6f, r180.left, 0.001f)
        assertEquals(0.7f, r180.top, 0.001f)
        assertEquals(0.8f, r180.right, 0.001f)
        assertEquals(0.9f, r180.bottom, 0.001f)

        val r90 = WarehouseOcrParser.unrotateNormRect(NormRect(0.8f, 0.1f, 0.9f, 0.3f), 90)
        assertEquals(0.1f, r90.left, 0.001f)
        assertEquals(0.1f, r90.top, 0.001f)
        assertEquals(0.3f, r90.right, 0.001f)
        assertEquals(0.2f, r90.bottom, 0.001f)
    }

    @Test
    fun `parse JYSK receipt and shelf QR and mixed box code`() {
        val receipt = """
            JYSK d.o.o.
            Št. računa: 0519-101-264866
            Datum: 09.10.26 11:04
            Predalnik LIMFJORDEN 4 predali bela
            Artikel 3618930   1x 95.00 EUR
            DIVERSES
            Artikel 3670619   2x 15.00 EUR
            Skupaj: 125.00 EUR
        """.trimIndent()
        val parsedReceipt = WarehouseOcrParser.parseReceipt(receipt)
        assertNotNull(parsedReceipt)
        assertEquals("0519-101-264866", parsedReceipt!!.receiptNo)
        assertEquals(2, parsedReceipt.items.size)
        assertEquals("3618930", parsedReceipt.items[0].sku)
        assertEquals("3670619", parsedReceipt.items[1].sku)
        assertEquals(2, parsedReceipt.items[1].qty)

        assertTrue(WarehouseOcrParser.isShelfQRCode("REGAL:R1-A-P1"))
        val sh = WarehouseOcrParser.parseShelfQR("REGAL:R1-A-P1")
        assertEquals("R1-A-P1", sh.code)
        assertTrue(sh.displayName.contains("Regal 1"))

        assertEquals("104", WarehouseOcrParser.parseMixedBoxBarcode("BOX-104"))
        assertNull(WarehouseOcrParser.parseMixedBoxBarcode("5713370040018"))
    }
}
