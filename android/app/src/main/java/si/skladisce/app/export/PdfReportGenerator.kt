package si.skladisce.app.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import si.skladisce.app.data.db.ReplenishmentEntity
import si.skladisce.app.data.model.AuditEntry
import si.skladisce.app.data.model.InventoryItem
import si.skladisce.app.data.model.MixedBoxWithItems
import si.skladisce.app.data.model.ReceivingSessionModel
import si.skladisce.app.data.model.ScatteredItem
import si.skladisce.app.data.model.WarehouseBusinessLogic
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportedFileResult(
    val fileName: String,
    val shareUri: Uri,
    val mimeType: String
)

object PdfReportGenerator {
    private const val A4_W = 595
    private const val A4_H = 842
    private const val MM_TO_PT = 2.8346457f

    private fun dateStamp(): String = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
    private fun displayNow(): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("sl", "SI")).format(Date())

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569")
        textSize = 10f
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        textSize = 11f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        textSize = 9.5f
    }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textSize = 8.5f
    }
    private val linePaint = Paint().apply {
        color = Color.parseColor("#CBD5E1")
        strokeWidth = 1f
    }
    private val fillHeaderPaint = Paint().apply {
        color = Color.parseColor("#F1F5F9")
        style = Paint.Style.FILL
    }

    private fun beginA4Page(doc: PdfDocument, pageNum: Int, title: String, subtitle: String): Pair<PdfDocument.Page, Float> {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, pageNum).create())
        val c = page.canvas
        c.drawColor(Color.WHITE)
        c.drawText(title, 36f, 44f, titlePaint)
        c.drawText(subtitle, 36f, 60f, subtitlePaint)
        c.drawText("Datum izpisa: ${displayNow()} • Stran $pageNum", A4_W - 210f, 60f, mutedPaint)
        c.drawLine(36f, 70f, A4_W - 36f, 70f, linePaint)
        return page to 92f
    }

    fun exportCompleteSetsPdf(context: Context, items: List<InventoryItem>): ExportedFileResult {
        val eligible = items.filter { it.completeSets > 0 && !it.hasIncomplete }.sortedBy { it.name }
        val doc = PdfDocument()
        var pageNum = 1
        var (page, y) = beginA4Page(doc, pageNum, "POPOLNI KOMPLETI V SKLADIŠČU", "Artikli z vsemi škatlami (${eligible.size} pozicij)")

        for (item in eligible) {
            if (y > A4_H - 60f) {
                doc.finishPage(page)
                pageNum++
                val next = beginA4Page(doc, pageNum, "POPOLNI KOMPLETI V SKLADIŠČU", "Nadaljevanje")
                page = next.first
                y = next.second
            }
            val c = page.canvas
            c.drawText(item.sku, 36f, y, headerPaint)
            c.drawText(item.name.take(44), 105f, y, bodyPaint)
            c.drawText("${item.completeSets} ${item.unit}", 415f, y, headerPaint)
            c.drawText(item.primaryShelf.take(18), 485f, y, mutedPaint)
            y += 16f
            c.drawLine(36f, y - 4f, A4_W - 36f, y - 4f, linePaint)
            y += 6f
        }
        if (eligible.isEmpty()) {
            page.canvas.drawText("Ni najdenih popolnih kompletov.", 36f, y, bodyPaint)
        }
        doc.finishPage(page)
        return writePdfAndShare(context, doc, "Popolni_kompleti_${dateStamp()}.pdf")
    }

    fun exportIncompleteBoxesPdf(context: Context, items: List<InventoryItem>): ExportedFileResult {
        val incomplete = items.filter { it.hasIncomplete || (it.boxTotal > 1 && it.breakdown.any { b -> b.count == 0 } && it.breakdown.any { b -> b.count > 0 }) }
        val doc = PdfDocument()
        var pageNum = 1
        var (page, y) = beginA4Page(doc, pageNum, "NEPOPOLNE ŠKATLE / PRIMANJKLJAJI", "Artikli z neusklajenim številom škatel (${incomplete.size})")

        for (item in incomplete) {
            if (y > A4_H - 80f) {
                doc.finishPage(page)
                pageNum++
                val next = beginA4Page(doc, pageNum, "NEPOPOLNE ŠKATLE / PRIMANJKLJAJI", "Nadaljevanje")
                page = next.first
                y = next.second
            }
            val c = page.canvas
            c.drawText("${item.sku} — ${item.name.take(48)}", 36f, y, headerPaint)
            y += 14f
            for (b in item.breakdown) {
                c.drawText("  Škatla ${b.boxNum}/${b.boxTotal}: ${b.count} kos (${b.location.take(36)})", 44f, y, bodyPaint)
                y += 13f
            }
            if (item.missingSummary.isNotBlank()) {
                val warnPaint = Paint(bodyPaint).apply { color = Color.parseColor("#B45309"); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
                c.drawText("  ⚠ ${item.missingSummary}", 44f, y, warnPaint)
                y += 14f
            }
            c.drawLine(36f, y, A4_W - 36f, y, linePaint)
            y += 10f
        }
        if (incomplete.isEmpty()) {
            page.canvas.drawText("Vse večdelne garniture imajo popolne škatle!", 36f, y, bodyPaint)
        }
        doc.finishPage(page)
        return writePdfAndShare(context, doc, "Nepopolne_skatle_${dateStamp()}.pdf")
    }

    fun exportReceivingReportPdf(context: Context, session: ReceivingSessionModel): ExportedFileResult {
        val doc = PdfDocument()
        var pageNum = 1
        var (page, y) = beginA4Page(
            doc,
            pageNum,
            "ZAPISNIK PREVZEMA POŠILJKE: ${session.name}",
            "Skupaj razloženih škatel: ${session.entries.sumOf { it.count }} • Zapisov: ${session.entries.size}"
        )

        val uniqueSkus = session.entries.map { it.sku }.distinct()
        val completedLines = mutableListOf<String>()
        val missingLines = mutableListOf<String>()

        for (sku in uniqueSkus) {
            val first = session.entries.first { it.sku == sku }
            val comp = WarehouseBusinessLogic.calculateSkuCompletion(
                sku = sku,
                boxTotal = first.boxTotal,
                initialBoxCounts = session.initialStock[sku].orEmpty(),
                entries = session.entries
            )
            if (comp.completedFromOld > 0) {
                completedLines.add("${first.sku} (${first.name.take(36)}): +${comp.completedFromOld} kompletov iz predhodne zaloge")
            }
            for (miss in comp.missingInBatch) {
                missingLines.add("${first.sku} (${first.name.take(36)}): manjka ${miss.missingCount}x Škatla ${miss.boxNum}/${miss.boxTotal}")
            }
        }

        val greenPaint = Paint(headerPaint).apply { color = Color.parseColor("#047857") }
        val redPaint = Paint(headerPaint).apply { color = Color.parseColor("#B91C1C") }

        page.canvas.drawText("KOMPLETIRANO S PREDHODNO ZALOGO (${completedLines.size}):", 36f, y, greenPaint)
        y += 16f
        if (completedLines.isEmpty()) {
            page.canvas.drawText("• Brez dopolnitev stare zaloge v tej pošiljki.", 44f, y, mutedPaint)
            y += 14f
        } else {
            for (l in completedLines) {
                page.canvas.drawText("• $l", 44f, y, bodyPaint)
                y += 14f
            }
        }
        y += 8f

        page.canvas.drawText("PRIMANJKLJAJI / NEUSKLAJENE ŠKATLE (${missingLines.size}):", 36f, y, redPaint)
        y += 16f
        if (missingLines.isEmpty()) {
            page.canvas.drawText("• Ni manjkajočih parnih škatel.", 44f, y, mutedPaint)
            y += 14f
        } else {
            for (l in missingLines) {
                page.canvas.drawText("• $l", 44f, y, bodyPaint)
                y += 14f
            }
        }
        y += 12f
        page.canvas.drawText("SEZNAM VSEH RAZLOŽENIH ŠKATEL:", 36f, y, headerPaint)
        y += 16f

        for (e in session.entries) {
            if (y > A4_H - 50f) {
                doc.finishPage(page)
                pageNum++
                val next = beginA4Page(doc, pageNum, "ZAPISNIK PREVZEMA: ${session.name}", "Nadaljevanje seznama")
                page = next.first
                y = next.second
            }
            val c = page.canvas
            c.drawText("${e.sku} | ${e.name.take(30)} | Šk.${e.boxNum}/${e.boxTotal} | ${e.shelf.take(16)} | +${e.count}", 36f, y, bodyPaint)
            y += 14f
        }
        doc.finishPage(page)
        return writePdfAndShare(context, doc, "Prevzem_${session.id}_${dateStamp()}.pdf")
    }

    fun exportConsolidationPdf(context: Context, scattered: List<ScatteredItem>): ExportedFileResult {
        val doc = PdfDocument()
        var pageNum = 1
        var (page, y) = beginA4Page(doc, pageNum, "DELOVNI NALOG: KONSOLIDACIJA REGALOV", "Razpršeni artikli na več regalih (${scattered.size})")

        for (s in scattered) {
            if (y > A4_H - 90f) {
                doc.finishPage(page)
                pageNum++
                val next = beginA4Page(doc, pageNum, "DELOVNI NALOG: KONSOLIDACIJA", "Nadaljevanje")
                page = next.first
                y = next.second
            }
            val c = page.canvas
            c.drawText("${s.sku} — ${s.name.take(45)}", 36f, y, headerPaint)
            y += 14f
            c.drawText("  Ciljni glavni regal: ${s.targetShelf}", 44f, y, bodyPaint)
            y += 13f
            for (m in s.boxesToMove) {
                c.drawText("  ➔ Premakni ${m.count}x Škatla ${m.boxNum}/${m.boxTotal} iz [${m.shelf}] na [${s.targetShelf}]", 44f, y, mutedPaint)
                y += 13f
            }
            c.drawLine(36f, y + 2f, A4_W - 36f, y + 2f, linePaint)
            y += 12f
        }
        if (scattered.isEmpty()) {
            page.canvas.drawText("Noben artikel ni razpršen čez več regalov.", 36f, y, bodyPaint)
        }
        doc.finishPage(page)
        return writePdfAndShare(context, doc, "Konsolidacija_${dateStamp()}.pdf")
    }

    fun exportReplenishmentPdf(context: Context, list: List<ReplenishmentEntity>): ExportedFileResult {
        val pending = list.filter { it.status == "pending" }.sortedWith(compareBy({ it.warehouse }, { it.sourceLocation }))
        val doc = PdfDocument()
        var pageNum = 1
        var (page, y) = beginA4Page(doc, pageNum, "NABIRALNI NALOG ZA POLICE TRGOVINE", "Aktivnih postavk za prenos: ${pending.size}")

        for (r in pending) {
            if (y > A4_H - 60f) {
                doc.finishPage(page)
                pageNum++
                val next = beginA4Page(doc, pageNum, "NABIRALNI NALOG ZA POLICE", "Nadaljevanje")
                page = next.first
                y = next.second
            }
            val c = page.canvas
            val whLabel = when (r.warehouse) {
                "zgornje" -> "Zgornje skl."
                "spodnje" -> "Spodnje skl."
                else -> r.warehouse
            }
            c.drawText("[ ] ${r.sku} — ${r.name.take(36)}", 36f, y, headerPaint)
            c.drawText("${r.neededQty} kos", 495f, y, headerPaint)
            y += 13f
            c.drawText("     Vzemi iz: $whLabel (${r.sourceLocation})  ➔  Polica: ${r.targetShelf}", 36f, y, mutedPaint)
            y += 15f
            c.drawLine(36f, y - 3f, A4_W - 36f, y - 3f, linePaint)
            y += 5f
        }
        if (pending.isEmpty()) {
            page.canvas.drawText("Vse police so dopolnjene (ni čakajočih nalog).", 36f, y, bodyPaint)
        }
        doc.finishPage(page)
        return writePdfAndShare(context, doc, "Nabiralni_nalog_${dateStamp()}.pdf")
    }

    fun exportAuditReportPdf(context: Context, entries: List<AuditEntry>, shelfFilter: String): ExportedFileResult {
        val doc = PdfDocument()
        var pageNum = 1
        var (page, y) = beginA4Page(
            doc,
            pageNum,
            "INVENTURNI ELABORAT",
            "Filter regala: ${shelfFilter.ifBlank { "Vsi regali" }} • Pozicij: ${entries.size}"
        )

        val diffEntries = entries.filter { !it.isCounted || it.diff != 0 }
        for (e in entries) {
            if (y > A4_H - 55f) {
                doc.finishPage(page)
                pageNum++
                val next = beginA4Page(doc, pageNum, "INVENTURNI ELABORAT", "Nadaljevanje")
                page = next.first
                y = next.second
            }
            val c = page.canvas
            val statusStr = if (!e.isCounted) "NEPREŠTETO" else if (e.diff == 0) "OK (0)" else if (e.diff > 0) "+${e.diff}" else "${e.diff}"
            c.drawText("${e.sku} | ${e.name.take(26)} | ${e.shelf.take(15)}", 36f, y, bodyPaint)
            c.drawText("Knjiž: ${e.bookQty} | Prešt: ${if (e.isCounted) e.countedQty else "-"} | $statusStr", 355f, y, if (e.diff != 0) headerPaint else mutedPaint)
            y += 15f
        }
        y += 8f
        page.canvas.drawText("Pozicij z odstopanji ali nepreštetih: ${diffEntries.size}", 36f, y, headerPaint)
        doc.finishPage(page)
        return writePdfAndShare(context, doc, "Inventura_${dateStamp()}.pdf")
    }

    /**
     * Generira PDF s 48 QR nalepkami za regale in stene skladišča (identično razdelitvi R1-R6 + SZ/SS/SL1/SL2)
     */
    fun exportShelfQrLabelsPdf(context: Context): ExportedFileResult {
        data class ShelfLocDef(val code: String, val rack: String, val side: String, val shelf: String)

        val locations = mutableListOf<ShelfLocDef>()
        for (r in 1..6) {
            for (side in listOf("A", "B")) {
                val sideName = if (side == "A") "Stran A (Sever / Zgoraj)" else "Stran B (Jug / Spodaj)"
                for (s in 1..3) {
                    val shelfName = when (s) {
                        1 -> "Spodnja polica (P1)"
                        2 -> "Srednja polica (P2)"
                        else -> "Zgornja polica (P3)"
                    }
                    locations.add(ShelfLocDef("R$r-$side-P$s", "Regal $r", sideName, shelfName))
                }
            }
        }
        val walls = listOf(
            "SZ" to "Stena Zgoraj",
            "SS" to "Stena Spodaj",
            "SL1" to "Stena Levo (Nad vrati)",
            "SL2" to "Stena Levo (Pod vrati)"
        )
        for ((id, name) in walls) {
            for (s in 1..3) {
                val shelfName = when (s) {
                    1 -> "Spodnja polica (P1)"
                    2 -> "Srednja polica (P2)"
                    else -> "Zgornja polica (P3)"
                }
                locations.add(ShelfLocDef("$id-P$s", name, "Enojni regal ob kletki", shelfName))
            }
        }

        val doc = PdfDocument()
        val cardW = 90f * MM_TO_PT
        val cardH = 82f * MM_TO_PT
        val marginX = 11f * MM_TO_PT
        val marginY = 16f * MM_TO_PT
        val gapX = 8f * MM_TO_PT
        val gapY = 8f * MM_TO_PT
        val cardsPerPage = 6

        val borderPaint = Paint().apply {
            color = Color.rgb(180, 185, 195)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val rackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#334155")
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 9.5f
            textAlign = Paint.Align.CENTER
        }

        var currentPage: PdfDocument.Page? = null
        var canvas: Canvas? = null

        locations.forEachIndexed { index, loc ->
            val posInPage = index % cardsPerPage
            if (posInPage == 0) {
                currentPage?.let { doc.finishPage(it) }
                val pNum = (index / cardsPerPage) + 1
                currentPage = doc.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, pNum).create())
                canvas = currentPage!!.canvas.apply { drawColor(Color.WHITE) }
            }
            val c = canvas ?: return@forEachIndexed
            val col = posInPage % 2
            val row = posInPage / 2
            val x = marginX + col * (cardW + gapX)
            val y = marginY + row * (cardH + gapY)

            c.drawRoundRect(RectF(x, y, x + cardW, y + cardH), 8f, 8f, borderPaint)

            val cx = x + cardW / 2f
            c.drawText(loc.rack, cx, y + 22f, rackPaint)
            c.drawText("${loc.side} • ${loc.shelf}", cx, y + 36f, detailPaint)

            val qrBmp = QrBarcodeGenerator.generateQrBitmap("REGAL:${loc.code}", 220)
            if (qrBmp != null) {
                val qrSize = 42f * MM_TO_PT
                val qrLeft = cx - qrSize / 2f
                val qrTop = y + 44f
                c.drawBitmap(qrBmp, null, RectF(qrLeft, qrTop, qrLeft + qrSize, qrTop + qrSize), null)
                qrBmp.recycle()
            }
            c.drawText(loc.code, cx, y + cardH - 18f, codePaint)
        }
        currentPage?.let { doc.finishPage(it) }

        return writePdfAndShare(context, doc, "QR_Nalepke_48_Polic_${dateStamp()}.pdf")
    }

    /**
     * Citizen 60 x 60 mm (6x6 cm) termalna nalepka za mešano škatlo (soft blago)
     */
    fun exportCitizenMixedBoxLabel6x6Pdf(context: Context, box: MixedBoxWithItems): ExportedFileResult {
        val sidePt = (60f * MM_TO_PT).toInt() // 170 pt
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(sidePt, sidePt, 1).create())
        val c = page.canvas
        c.drawColor(Color.WHITE)

        val whName = when (box.warehouse) {
            "zgornje" -> "ZGORNJE SKLADIŠČE"
            "spodnje" -> "SPODNJE SKLADIŠČE"
            else -> box.warehouse.uppercase(Locale.ROOT)
        }
        val shelfText = box.shelf.ifBlank { "BREZ POLICE" }.uppercase(Locale.ROOT)

        val outerPaint = Paint().apply {
            color = Color.parseColor("#1E293B")
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
        }
        c.drawRoundRect(RectF(6f, 6f, sidePt - 6f, sidePt - 6f), 6f, 6f, outerPaint)

        // Temna glava
        val headBg = Paint().apply { color = Color.parseColor("#0F172A"); style = Paint.Style.FILL }
        c.drawRoundRect(RectF(7f, 7f, sidePt - 7f, 25f), 4f, 4f, headBg)
        val headTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 8f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        c.drawText("MEŠANA ŠKATLA (SOFT)", sidePt / 2f, 19f, headTxt)

        // Številka škatle (#104)
        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        c.drawText("#${box.boxNumber}", sidePt / 2f, 46f, numPaint)

        val locPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#475569")
            textSize = 6.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        c.drawText("$whName • $shelfText", sidePt / 2f, 57f, locPaint)

        // Code-128 črtna koda
        val barcodeBmp = QrBarcodeGenerator.generateCode128Bitmap("BOX-${box.boxNumber}", 280, 56)
        if (barcodeBmp != null) {
            c.drawBitmap(barcodeBmp, null, RectF(28f, 61f, sidePt - 28f, 82f), null)
            barcodeBmp.recycle()
        }

        // Vsebina škatle (tabela)
        val sepPaint = Paint().apply { color = Color.parseColor("#CBD5E1"); strokeWidth = 0.6f }
        c.drawLine(11f, 86f, sidePt - 11f, 86f, sepPaint)

        val tblHead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 5.8f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val tblHeadRight = Paint(tblHead).apply { textAlign = Paint.Align.RIGHT }
        c.drawText("ARTIKEL", 14f, 94f, tblHead)
        c.drawText("KOS", sidePt - 14f, 94f, tblHeadRight)

        val skuP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 6.2f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val nameP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 5.8f
        }
        val qtyP = Paint(skuP).apply { textAlign = Paint.Align.RIGHT }

        var curY = 103f
        val maxShow = minOf(box.items.size, 5)
        for (i in 0 until maxShow) {
            val it = box.items[i]
            c.drawText(it.sku, 14f, curY, skuP)
            c.drawText(it.name.take(18), 46f, curY, nameP)
            c.drawText("${it.qty}x", sidePt - 14f, curY, qtyP)
            curY += 8.8f
        }
        if (box.items.size > 5) {
            val moreP = Paint(tblHead).apply { textSize = 5.2f }
            c.drawText("+ še ${box.items.size - 5} drugih artiklov...", 14f, curY, moreP)
        }

        // Spodnji povzetek
        c.drawLine(11f, 149f, sidePt - 11f, 149f, sepPaint)
        val footBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 6.6f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val footRight = Paint(tblHead).apply { textAlign = Paint.Align.RIGHT; textSize = 5.5f }
        c.drawText("Skupaj v škatli: ${box.totalItems} kos", 14f, 158f, footBold)
        c.drawText(WarehouseBusinessLogic.formatShortDate(box.createdAt), sidePt - 14f, 158f, footRight)

        doc.finishPage(page)
        return writePdfAndShare(context, doc, "Citizen_Nalepka_6x6_Skatla_${box.boxNumber}.pdf")
    }

    fun exportInventoryCsv(context: Context, items: List<InventoryItem>): ExportedFileResult {
        val sb = StringBuilder()
        sb.append('\uFEFF') // UTF-8 BOM za Excel
        sb.append("SKU;Naziv;Kategorija;Skatle;Kompleti;Polica;Trgovina;ZgornjeSkl;SpodnjeSkl;MinZaloga;Enota;Opombe\n")
        for (i in items) {
            val escName = i.name.replace(";", ",")
            val escNotes = i.notes.replace(";", ",").replace("\n", " ")
            sb.append("${i.sku};$escName;${i.category};${i.boxTotal};${i.completeSets};${i.primaryShelf};${i.storeQuantity};${i.zgornjeQuantity};${i.spodnjeQuantity};${i.minQuantity};${i.unit};$escNotes\n")
        }
        return saveBytesAndBuildResult(
            context = context,
            fileName = "Zaloga_Skladisce_${dateStamp()}.csv",
            mimeType = "text/csv",
            bytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
        )
    }

    fun exportBackupJson(context: Context, jsonStr: String): ExportedFileResult {
        return saveBytesAndBuildResult(
            context = context,
            fileName = "Skladisce_VarnostnaKopija_${dateStamp()}.json",
            mimeType = "application/json",
            bytes = jsonStr.toByteArray(StandardCharsets.UTF_8)
        )
    }

    fun launchShareOrViewIntent(context: Context, exported: ExportedFileResult) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(exported.shareUri, exported.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = exported.mimeType
            putExtra(Intent.EXTRA_STREAM, exported.shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(viewIntent, "Odpri ali deli: ${exported.fileName}").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(shareIntent))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun writePdfAndShare(context: Context, doc: PdfDocument, fileName: String): ExportedFileResult {
        val baos = ByteArrayOutputStream()
        doc.writeTo(baos)
        doc.close()
        return saveBytesAndBuildResult(context, fileName, "application/pdf", baos.toByteArray())
    }

    private fun saveBytesAndBuildResult(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): ExportedFileResult {
        // 1. Zapiši v javno mapo Downloads (Android Q+ MediaStore ali Legacy)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Skladisce")
                }
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Skladisce")
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, fileName)
                FileOutputStream(f).use { it.write(bytes) }
                MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf(mimeType), null)
            }
        }

        // 2. Zapiši v cache za zanesljiv FileProvider URI pri deljenju / ogledu
        val cacheDir = File(context.cacheDir, "exports")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val cacheFile = File(cacheDir, fileName)
        FileOutputStream(cacheFile).use { it.write(bytes) }
        val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)

        return ExportedFileResult(fileName = fileName, shareUri = shareUri, mimeType = mimeType)
    }
}
