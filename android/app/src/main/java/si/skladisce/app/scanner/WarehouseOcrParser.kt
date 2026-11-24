package si.skladisce.app.scanner

import java.util.LinkedList
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class OcrTextBlock(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

data class WarehouseScanResult(
    val primarySku: String = "",
    val secondarySku: String = "",
    val barcode: String = "",
    val name: String = "",
    val boxCurrent: Int = 1,
    val boxTotal: Int = 1,
    val quantityInBox: Int = 1,
    val boxRect: NormRect? = null,
    val otherBoxes: List<WarehouseScanResult> = emptyList(),
    val score: Float = 0f,
    val mode: String = "ocr_0",
    val rawText: String = ""
) {
    val bestSku: String
        get() = primarySku.ifEmpty { secondarySku.ifEmpty { barcode } }
}

data class ReceiptItem(
    val sku: String,
    val name: String,
    val qty: Int,
    val price: String
)

data class ReceiptScanResult(
    val receiptNo: String,
    val receiptDate: String,
    val barcode: String,
    val items: List<ReceiptItem>,
    val rawText: String
)

data class ShelfQrInfo(
    val code: String,
    val displayName: String
)

object WarehouseOcrParser {
    private val boxPattern = Regex(
        "(?:^|[^a-zA-Z0-9čžšČŽŠ])(?:box|colli|coli|karton|package|pkg|del|part|[sš]katla)[\\s.:]*([0-9]{1,2})\\s*(?:[/|\\\\]|\\b(?:of|von)\\b)\\s*([0-9]{1,2})(?:[\\s.:]+([0-9]+)\\s*(?:pcs\\.?|kom|kos)?)?",
        RegexOption.IGNORE_CASE
    )
    private val fracPattern = Regex("\\b([1-9])\\s*[/|\\\\]\\s*([1-9])\\b")
    private val dualPattern = Regex(
        "(?:product\\s*no[\\/.:\\s]*)?([0-9OTIl]{7})\\s*[/|\\\\I]\\s*([0-9OTIl]{8})",
        RegexOption.IGNORE_CASE
    )
    private val fusedPattern = Regex("\\b([3-6][0-9]{6})([5-9][0-9]{5}001|[5-9][0-9]{7})\\b")
    private val explicitSkuPattern = Regex(
        "(?:[jJ]?ysk\\s*)?(?:product\\s*(?:no\\.?|number)?|article(?:\\s*no\\.?)?|artikel|art\\.?\\s*(?:st\\.?|št\\.?|no\\.?)?|item(?:\\s*no\\.?)?|sku|vare[\\s.:]*nr|[sš]ifra|koda)[\\s.:]*([0-9OTIl]{7})\\b",
        RegexOption.IGNORE_CASE
    )
    private val explicitSkuLabelOnly = Regex(
        ".*(?:product\\s*(?:no\\.?|number)?|article|artikel|item|sku|[sš]ifra|koda)[\\s.:]*$",
        RegexOption.IGNORE_CASE
    )
    private val dash7Pattern = Regex("\\b([0-9OTIl]{7})\\s*[-–—]\\s*[a-zA-Z]")
    private val dash6Pattern = Regex("\\b([1-6][0-9]{5})\\s*[-–—]")
    private val secondarySkuPattern = Regex("\\b([5-9][0-9]{5}001|[5-9][0-9]{7})\\b")
    private val generic7Pattern = Regex("\\b([346][0-9]{6})\\b")

    private val furnitureKeywordPattern = Regex(
        "\\b(?:desk|chair|table|miza|mizica|stol|omara|wardrobe|bed|postelja|posteljni|shelf|regal|polica|cabinet|omarica|drawer|predalnik|komoda|sideboard|mirror|ogledalo|sofa|couch|bench|klop|cushion|blazina|pillow|vzglavnik|rug|carpet|preproga|vzmetnica|mattress|duvet|pouffe|pedestal|armchair|fotelj|bookcase|nightstand|frame|okvir|insert|vložek|astrup|marstrand|lindholm|halsted|limfjorden|tamholt|tamdrup|abildaa|billum|alsted|bredstrup|kastrup|tange|jenslev|tapdrup|korup|viuf|malling|stauning|billund|englerup|agerby|lyngs|barkholt|hvilsted|sandager|adslev|balslev|fjellerup|larvik|ulvik|vanse|aars|vingan|lyngvig|gedsted|randrup|randerup|voel|tarp|abild|mellby|sandvig|diverses)\\b",
        RegexOption.IGNORE_CASE
    )

    fun sanitizeDigits(s: String?): String {
        if (s == null) return ""
        return s.trim().uppercase(Locale.US)
            .replace("O", "0")
            .replace("I", "1")
            .replace("L", "1")
            .replace("T", "7")
            .replace("S", "5")
            .replace("B", "8")
    }

    fun isPrimaryValid(s: String?): Boolean {
        if (s == null || s.length != 7) return false
        if (!s.matches(Regex("^[1-6][0-9]{6}$"))) return false
        return !s.startsWith("455") // Naročilo / PO prefix
    }

    fun isSecondaryValid(s: String?): Boolean {
        if (s == null || s.length != 8) return false
        if (!s.matches(Regex("^[5-9][0-9]{7}$"))) return false
        if (s == "89397500" || s == "99397500") return false
        return s.endsWith("001") || s.endsWith("700") ||
            s.startsWith("56379") || s.startsWith("70663") ||
            s.startsWith("78480") || s.startsWith("81006") ||
            s.startsWith("91437") || s.startsWith("91493") ||
            s.startsWith("92947") || s.startsWith("93291") ||
            s.startsWith("92012") || s.startsWith("73055") ||
            s.startsWith("78565")
    }

    fun isValidEanChecksum(code: String?): Boolean {
        if (code == null) return false
        val clean = code.filter { it.isDigit() }
        if (clean.length != 13 && clean.length != 8) return false
        var sum = 0
        val len = clean.length
        for (i in 0 until len - 1) {
            val d = clean[i] - '0'
            val weight = if (len == 13) (if (i % 2 == 0) 1 else 3) else (if (i % 2 == 0) 3 else 1)
            sum += d * weight
        }
        val check = (10 - (sum % 10)) % 10
        return check == (clean[len - 1] - '0')
    }

    fun unrotateNormRect(rect: NormRect, extraRotationDeg: Int): NormRect {
        return when (((extraRotationDeg % 360) + 360) % 360) {
            90 -> NormRect(
                left = rect.top.coerceIn(0f, 1f),
                top = (1f - rect.right).coerceIn(0f, 1f),
                right = rect.bottom.coerceIn(0f, 1f),
                bottom = (1f - rect.left).coerceIn(0f, 1f)
            )
            180 -> NormRect(
                left = (1f - rect.right).coerceIn(0f, 1f),
                top = (1f - rect.bottom).coerceIn(0f, 1f),
                right = (1f - rect.left).coerceIn(0f, 1f),
                bottom = (1f - rect.top).coerceIn(0f, 1f)
            )
            270 -> NormRect(
                left = (1f - rect.bottom).coerceIn(0f, 1f),
                top = rect.left.coerceIn(0f, 1f),
                right = (1f - rect.top).coerceIn(0f, 1f),
                bottom = rect.right.coerceIn(0f, 1f)
            )
            else -> rect
        }
    }

    fun analyzeBoxClusters(
        blocks: List<OcrTextBlock>,
        imgW: Int,
        imgH: Int,
        mode: String = "ocr_0"
    ): List<WarehouseScanResult> {
        if (blocks.isEmpty() || imgW <= 0 || imgH <= 0) return emptyList()
        val validBlocks = blocks.filter { it.text.isNotBlank() }
        if (validBlocks.isEmpty()) return emptyList()

        val maxHorizGap = max(60f, imgW * 0.10f)
        val maxVertGap = max(90f, imgH * 0.12f)
        val n = validBlocks.size
        val visited = BooleanArray(n)
        val clusters = mutableListOf<List<OcrTextBlock>>()

        for (i in 0 until n) {
            if (visited[i]) continue
            visited[i] = true
            val cluster = mutableListOf(validBlocks[i])
            val queue = LinkedList<Int>()
            queue.add(i)

            while (queue.isNotEmpty()) {
                val curr = validBlocks[queue.poll()!!]
                for (j in 0 until n) {
                    if (!visited[j]) {
                        val cand = validBlocks[j]
                        val dx = max(0, max(curr.left, cand.left) - min(curr.right, cand.right))
                        val dy = max(0, max(curr.top, cand.top) - min(curr.bottom, cand.bottom))
                        val normDist = hypot((dx / maxHorizGap).toDouble(), (dy / maxVertGap).toDouble())
                        if (normDist <= 1.0) {
                            visited[j] = true
                            cluster.add(cand)
                            queue.add(j)
                        }
                    }
                }
            }
            clusters.add(cluster)
        }

        val screenCenterX = imgW / 2f
        val screenCenterY = imgH / 2f
        val maxScreenDist = hypot(screenCenterX.toDouble(), screenCenterY.toDouble()).toFloat().coerceAtLeast(1f)

        val evaluated = mutableListOf<WarehouseScanResult>()
        for (cluster in clusters) {
            val sorted = cluster.sortedWith(compareBy<OcrTextBlock> { it.top }.thenBy { it.left })
            val clusterText = sorted.joinToString("\n") { it.text }
            val parsed = parseWarehouseText(clusterText)
            if (parsed != null && (parsed.primarySku.isNotEmpty() || parsed.secondarySku.isNotEmpty() || parsed.barcode.isNotEmpty())) {
                val minX = sorted.minOf { it.left }.coerceIn(0, imgW)
                val minY = sorted.minOf { it.top }.coerceIn(0, imgH)
                val maxX = sorted.maxOf { it.right }.coerceIn(0, imgW)
                val maxY = sorted.maxOf { it.bottom }.coerceIn(0, imgH)

                val normRect = NormRect(
                    left = minX / imgW.toFloat(),
                    top = minY / imgH.toFloat(),
                    right = maxX / imgW.toFloat(),
                    bottom = maxY / imgH.toFloat()
                )

                var score = 0f
                if (parsed.primarySku.isNotEmpty()) score += 100f
                if (parsed.secondarySku.isNotEmpty()) score += 50f
                if (parsed.barcode.isNotEmpty()) score += 40f
                if (parsed.boxTotal > 1) score += 20f
                if (parsed.name.isNotBlank() && !parsed.name.startsWith("Artikel ")) score += 10f

                val cx = (minX + maxX) / 2f
                val cy = (minY + maxY) / 2f
                val distToCenter = hypot((cx - screenCenterX).toDouble(), (cy - screenCenterY).toDouble()).toFloat()
                val normDist = distToCenter / maxScreenDist
                score += (1f - normDist) * 35f

                evaluated.add(
                    parsed.copy(
                        boxRect = normRect,
                        score = score,
                        mode = mode
                    )
                )
            }
        }

        evaluated.sortByDescending { it.score }
        return evaluated
    }

    fun findBestBoxCandidate(
        blocks: List<OcrTextBlock>?,
        imgW: Int,
        imgH: Int,
        fullText: String,
        mode: String = "ocr_0"
    ): WarehouseScanResult? {
        if (!blocks.isNullOrEmpty() && imgW > 0 && imgH > 0) {
            val candidates = analyzeBoxClusters(blocks, imgW, imgH, mode)
            if (candidates.isNotEmpty()) {
                val best = candidates.first()
                return if (candidates.size > 1) {
                    best.copy(otherBoxes = candidates.drop(1))
                } else {
                    best
                }
            }
        }
        return parseWarehouseText(fullText, mode)
    }

    fun parseWarehouseText(text: String?, mode: String = "ocr_0"): WarehouseScanResult? {
        if (text.isNullOrBlank()) return null
        val lines = text.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        var boxCurrent = 1
        var boxTotal = 1
        var qtyFromBoxLine = 0

        // 1. Podatki o škatli
        for (l in lines) {
            val bm = boxPattern.find(l)
            if (bm != null) {
                val c = bm.groupValues[1].toIntOrNull() ?: 1
                val t = bm.groupValues[2].toIntOrNull() ?: 1
                if (c in 1..t && t <= 15) {
                    boxCurrent = c
                    boxTotal = t
                    val qStr = bm.groupValues.getOrNull(3).orEmpty()
                    if (qStr.isNotEmpty()) {
                        qtyFromBoxLine = qStr.toIntOrNull() ?: 0
                    }
                    break
                }
            }
        }
        if (boxTotal == 1) {
            for (l in lines) {
                if (Regex("(?:weight|gross|net|kg|cm|mm|date|datum)", RegexOption.IGNORE_CASE).containsMatchIn(l)) continue
                val fm = fracPattern.find(l)
                if (fm != null) {
                    val c = fm.groupValues[1].toIntOrNull() ?: 1
                    val t = fm.groupValues[2].toIntOrNull() ?: 1
                    if (c in 1..t && t in 2..6) {
                        boxCurrent = c
                        boxTotal = t
                        break
                    }
                }
            }
        }

        // Količina v škatli
        val qtyInBox = parseQuantityInBox(lines, qtyFromBoxLine)

        var primarySku = ""
        var secondarySku = ""
        var skuLineIdx = -1

        // 2. Dvojna ali združena šifra
        for (i in lines.indices) {
            val l = lines[i]
            val dm = dualPattern.find(l)
            if (dm != null) {
                val p = sanitizeDigits(dm.groupValues[1])
                val s = sanitizeDigits(dm.groupValues[2])
                if (isPrimaryValid(p)) {
                    primarySku = p
                    skuLineIdx = i
                }
                if (isSecondaryValid(s)) {
                    secondarySku = s
                }
                if (primarySku.isNotEmpty() || secondarySku.isNotEmpty()) break
            }
            val fm = fusedPattern.find(l)
            if (fm != null) {
                val p = fm.groupValues[1]
                val s = fm.groupValues[2]
                if (isPrimaryValid(p)) {
                    primarySku = p
                    skuLineIdx = i
                }
                if (isSecondaryValid(s)) {
                    secondarySku = s
                }
                break
            }
        }

        // 3. Izrecna oznaka ("Product no.: 3699065", "Artikel: 3650044")
        if (primarySku.isEmpty()) {
            for (i in lines.indices) {
                val em = explicitSkuPattern.find(lines[i])
                if (em != null) {
                    val c = sanitizeDigits(em.groupValues[1])
                    if (isPrimaryValid(c)) {
                        primarySku = c
                        skuLineIdx = i
                        break
                    }
                }
                if (explicitSkuLabelOnly.matches(lines[i]) && i + 1 < lines.size) {
                    val nm = Regex("\\b([0-9OTIl]{7})\\b").find(lines[i + 1])
                    if (nm != null) {
                        val c = sanitizeDigits(nm.groupValues[1])
                        if (isPrimaryValid(c)) {
                            primarySku = c
                            skuLineIdx = i + 1
                            break
                        }
                    }
                }
            }
        }

        // 4. Vzorec s pomišljajem ("3650044 - Desk ASTRUP", odrezan "650203-")
        if (primarySku.isEmpty()) {
            for (i in lines.indices) {
                val l = lines[i]
                val dm = dash7Pattern.find(l)
                if (dm != null) {
                    var c = sanitizeDigits(dm.groupValues[1])
                    if (c.startsWith("38")) c = "36" + c.substring(2)
                    if (isPrimaryValid(c)) {
                        primarySku = c
                        skuLineIdx = i
                        break
                    }
                }
                val dm6 = dash6Pattern.find(l)
                if (dm6 != null) {
                    val cand7 = "3" + dm6.groupValues[1]
                    if (isPrimaryValid(cand7)) {
                        primarySku = cand7
                        skuLineIdx = i
                        break
                    }
                }
            }
        }

        // 5. 8-mestna dobaviteljeva koda
        if (secondarySku.isEmpty()) {
            for (i in lines.indices) {
                val l = lines[i]
                if (Regex("(?:tel|phone|fax|order|batch|date)", RegexOption.IGNORE_CASE).containsMatchIn(l)) continue
                val sm = secondarySkuPattern.find(l)
                if (sm != null) {
                    val c = sm.groupValues[1]
                    if (isSecondaryValid(c)) {
                        secondarySku = c
                        if (skuLineIdx < 0) skuLineIdx = i
                        break
                    }
                }
            }
        }

        // 6. Splošna 7-mestna šifra
        if (primarySku.isEmpty()) {
            for (i in lines.indices) {
                val l = lines[i]
                if (Regex("(?:order|batch|po|tel|phone|weight|gross|ean|barcode)", RegexOption.IGNORE_CASE).containsMatchIn(l)) continue
                val am = generic7Pattern.find(l)
                if (am != null && isPrimaryValid(am.groupValues[1])) {
                    primarySku = am.groupValues[1]
                    skuLineIdx = i
                    break
                }
            }
        }

        // 7. EAN-13 / EAN-8 iz teksta
        var barcode = ""
        for (l in lines) {
            val digits = l.filter { it.isDigit() }
            if ((digits.length == 13 || digits.length == 8) && isValidEanChecksum(digits)) {
                barcode = digits
                break
            }
        }

        if (primarySku.isEmpty() && secondarySku.isEmpty() && barcode.isEmpty()) return null

        val activeSku = primarySku.ifEmpty { secondarySku }
        val extractedName = extractProductName(lines, activeSku, skuLineIdx)

        return WarehouseScanResult(
            primarySku = primarySku,
            secondarySku = secondarySku,
            barcode = barcode,
            name = extractedName,
            boxCurrent = boxCurrent,
            boxTotal = boxTotal,
            quantityInBox = qtyInBox,
            mode = mode,
            rawText = text
        )
    }

    fun parseQuantityInBox(lines: List<String>, explicitFromBoxLine: Int = 0): Int {
        if (explicitFromBoxLine > 0) return explicitFromBoxLine
        for (l in lines) {
            Regex("(?:quantity|menge|koli[cč]ina|qty|content|vsebina)[\\s.:]+([0-9]+)", RegexOption.IGNORE_CASE)
                .find(l)?.let { return max(1, it.groupValues[1].toIntOrNull() ?: 1) }
        }
        for (l in lines) {
            Regex("\\b([0-9]+)\\s*(?:pcs|kom|kos)\\s*[/|\\\\]\\s*(?:pk|pack|karton|ctn|box|komplet)\\b", RegexOption.IGNORE_CASE)
                .find(l)?.let { return max(1, it.groupValues[1].toIntOrNull() ?: 1) }
        }
        for (l in lines) {
            Regex("^([0-9]+)\\s*(?:pcs\\.?|kom|kos)\\b", RegexOption.IGNORE_CASE)
                .find(l)?.let { return max(1, it.groupValues[1].toIntOrNull() ?: 1) }
        }
        return 1
    }

    fun isTechnicalOrBoxLine(line: String?): Boolean {
        if (line.isNullOrBlank()) return true
        val lower = line.trim().lowercase(Locale.ROOT)
        if (lower.matches(Regex("^[0-9\\s.,\\-/:xX+*#%~_()]+$"))) return true
        if (Regex("(?:gross|net|brutto|netto)?\\s*(?:weight|teza|teža)?\\s*[:.]?\\s*\\d+(?:[.,]\\d+)?\\s*(?:kg|lbs|g)\\b")
                .containsMatchIn(lower) && !furnitureKeywordPattern.containsMatchIn(lower)
        ) return true
        if (lower.matches(Regex("^(?:[wlhø]\\s*\\d+\\s*[x*]\\s*)+.*(?:cm|mm|inch)?$"))) return true
        if (Regex("(?:^|\\b)(?:box|colli|del|part|[sš]katla)\\s*\\d+").containsMatchIn(lower)) return true
        if (lower.matches(Regex("^\\s*\\d+\\s*[/\\\\]\\s*\\d+\\s*$"))) return true
        if (Regex("(?:barcode|ean|order|batch|po\\s*no|supplier|made in|jysk nordic)").containsMatchIn(lower)) return true
        if (Regex("(?:qty|quantity|kolicina|količina)\\s*[:.]?\\s*\\d+").containsMatchIn(lower)) return true
        if (Regex("^\\d+\\s*(?:pcs\\.?|kom|kos)(?:\\s*[/|\\\\]\\s*(?:pk|pack|karton|ctn|box|komplet))?\\b").containsMatchIn(lower)) return true
        return false
    }

    fun cleanProductName(s: String?): String {
        if (s == null) return ""
        return s
            .replace(
                Regex("^(?:jysk\\s*)?(?:product\\s*(?:no\\.?|number|text|desc|name)?|article(?:\\s*(?:name|text))?|art\\.?\\s*(?:st\\.?|št\\.?|no\\.?)?|artikel|item(?:\\s*(?:no\\.?|name))?|sku|vare[\\s.:]*nr|naziv|opis)[\\s.:\\-]*", RegexOption.IGNORE_CASE),
                ""
            )
            .replace(Regex("^[\\s\\-–—:/]+"), "")
            .replace(Regex("[\\s\\-–—:/]+$"), "")
            .trim()
    }

    private fun appendOverflow(lines: List<String>, startIdx: Int, initial: String): String {
        var name = initial.trim()
        if (name.isEmpty()) return ""
        val endIdx = min(lines.lastIndex, startIdx + 2)
        for (j in (startIdx + 1)..endIdx) {
            val next = lines[j].trim()
            if (next.isEmpty()) break
            if (isTechnicalOrBoxLine(next)) break
            if (next.matches(Regex("^[0-9OIl]{6,10}$"))) break
            if (Regex("[a-zA-ZčžšČŽŠ]{2,}").containsMatchIn(next) && next.length <= 65) {
                name = "$name $next"
            } else {
                break
            }
        }
        return name.replace(Regex("\\s{2,}"), " ").trim()
    }

    fun extractProductName(lines: List<String>, sku: String, skuLineIdx: Int): String {
        if (lines.isEmpty()) return ""

        // A) Po poševnici ali v isti vrstici kot SKU ("3650044 - Desk ASTRUP 60x120 black")
        if (sku.isNotEmpty() && skuLineIdx in lines.indices) {
            val line = lines[skuLineIdx]
            val after = cleanProductName(
                line.replaceFirst(Regex("^.*?\\b${Regex.escape(sku)}\\b[\\s\\-–—:/]*", RegexOption.IGNORE_CASE), "")
            )
            if (after.length >= 3 && Regex("[a-zA-Z]{2,}").containsMatchIn(after) && !isTechnicalOrBoxLine(after)) {
                return appendOverflow(lines, skuLineIdx, after)
            }
            val before = cleanProductName(
                line.replaceFirst(Regex("[\\s\\-–—:/]*\\b${Regex.escape(sku)}\\b.*$", RegexOption.IGNORE_CASE), "")
            )
            if (before.length >= 3 && Regex("[a-zA-Z]{2,}").containsMatchIn(before) && !isTechnicalOrBoxLine(before)) {
                return appendOverflow(lines, skuLineIdx, before)
            }
        }

        // B) Izrecna oznaka ("Product name: ...", "Item: ...", "Naziv: ...")
        val expNameRegex = Regex(
            "(?:jysk\\s*)?(?:product\\s*(?:text|name|desc)|article\\s*(?:name|text)|item\\s*name|naziv|opis)[\\s.:]+(.+)",
            RegexOption.IGNORE_CASE
        )
        for (i in lines.indices) {
            val m = expNameRegex.find(lines[i])
            if (m != null) {
                val cand = cleanProductName(m.groupValues[1])
                if (cand.length >= 3 && Regex("[a-zA-Z]{2,}").containsMatchIn(cand) && !isTechnicalOrBoxLine(cand)) {
                    return appendOverflow(lines, i, cand)
                }
            }
        }

        // C) Vrstica takoj po ali pred šifro
        if (skuLineIdx >= 0) {
            if (skuLineIdx + 1 < lines.size) {
                val next = cleanProductName(lines[skuLineIdx + 1])
                if (next.length >= 3 && Regex("[a-zA-Z]{2,}").containsMatchIn(next) && !isTechnicalOrBoxLine(next)) {
                    return appendOverflow(lines, skuLineIdx + 1, next)
                }
            }
            if (skuLineIdx - 1 >= 0) {
                val prev = cleanProductName(lines[skuLineIdx - 1])
                if (prev.length >= 3 && Regex("[a-zA-Z]{2,}").containsMatchIn(prev) && !isTechnicalOrBoxLine(prev)) {
                    return appendOverflow(lines, skuLineIdx - 1, prev)
                }
            }
        }

        // D) Iskanje po ključnih besedah pohištva / artiklov
        for (i in lines.indices) {
            val l = lines[i]
            if (furnitureKeywordPattern.containsMatchIn(l) && !isTechnicalOrBoxLine(l)) {
                val cand = cleanProductName(l)
                if (cand.length >= 3) return appendOverflow(lines, i, cand)
            }
        }

        // E) Katerakoli veljavna opisna vrstica z besedami
        for (l in lines) {
            if (!isTechnicalOrBoxLine(l)) {
                val cand = cleanProductName(l)
                if (cand.length >= 4 && Regex("[a-zA-Z]{2,}").containsMatchIn(cand) && cand.contains(" ")) {
                    return cand
                }
            }
        }
        return ""
    }

    /**
     * Razčleni trgovski račun kupca (JYSK / splošni računi z več artikli)
     */
    fun parseReceipt(rawText: String?): ReceiptScanResult? {
        if (rawText.isNullOrBlank()) return null
        val lines = rawText.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val receiptKeywords = Regex(
            "\\b(?:ra[cč]un(?:a|om|u)?|st\\.?\\s*ra[cč]|št\\.?\\s*ra[cč]|blagajn[aei]?|skupaj|ddv|tovarni[sš]ka|bre[zž]ice|celov[sš]ka|prevzem|eft|trgovina\\s*d\\.?o\\.?o)\\b",
            RegexOption.IGNORE_CASE
        )
        val hasKeyword = receiptKeywords.containsMatchIn(rawText)
        val receiptNo = Regex("(?:št\\.?|st\\.?)\\s*ra[cč](?:un[aeiuo]?)?(?:/storno)?[\\s.:]+([0-9a-zA-Z\\-/]+)", RegexOption.IGNORE_CASE)
            .find(rawText)?.groupValues?.getOrNull(1).orEmpty()
        val receiptDate = Regex("datum[\\s.:]*([0-9./]+(?:\\s+[0-9:]+)?)", RegexOption.IGNORE_CASE)
            .find(rawText)?.groupValues?.getOrNull(1).orEmpty()
        val barcode = Regex("\\b([0-9]{16,24})\\b").find(rawText)?.groupValues?.getOrNull(1).orEmpty()

        val itemsMap = linkedMapOf<String, ReceiptItem>()
        for (i in lines.indices) {
            val line = lines[i]
            val artMatch = Regex("(?:artikel|art\\.?|šifra|sifra|koda)?[\\s.:]*\\b([0-9]{7,8})\\b", RegexOption.IGNORE_CASE).find(line)
            val hasArtWord = Regex("(?:artikel|art\\.?|šifra|sifra|koda)", RegexOption.IGNORE_CASE).containsMatchIn(line)
            val priceMatch = Regex("([0-9]+[.,][0-9]{2})\\s*(?:€|e|eur)?", RegexOption.IGNORE_CASE).find(line)

            if (artMatch != null && (hasArtWord || (priceMatch != null && hasKeyword))) {
                val sku = artMatch.groupValues[1]
                var qty = Regex("\\b([0-9]+)\\s*(?:x|kom|kos|pcs)\\b", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                var unitPrice = ""
                val totalPrice = priceMatch?.groupValues?.getOrNull(1).orEmpty()
                var name = ""

                for (back in 1..3) {
                    val idx = i - back
                    if (idx < 0) break
                    val cand = lines[idx]
                    val qp = Regex("^([0-9]+)\\s*[xX*]\\s*([0-9]+[.,][0-9]{2})").find(cand)
                    if (qp != null) {
                        qty = qp.groupValues[1].toIntOrNull() ?: 1
                        unitPrice = qp.groupValues[2].replace(',', '.')
                        continue
                    }
                    if (Regex("(?:artikel|art\\.?|šifra|sifra|koda)\\s*[0-9]{7,8}", RegexOption.IGNORE_CASE).containsMatchIn(cand)) break
                    if (Regex("^(?:skupaj|ddv|neto|popust|znesek|cena|vrnjen|plačano|eft)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cand)) break
                    if (receiptKeywords.containsMatchIn(cand) && cand.length < 35) continue
                    if (cand.length > 2 && !Regex("^[0-9.,\\s€e\\-*/]+$", RegexOption.IGNORE_CASE).matches(cand)) {
                        name = cand.replace(Regex("\\?(\\d+)"), "Ø$1").replace(Regex("^\\?\\s*"), "Ø")
                        break
                    }
                }

                if (name.isEmpty()) {
                    val cleaned = line
                        .replace(Regex("(?:artikel|art\\.?|šifra|sifra|koda)", RegexOption.IGNORE_CASE), "")
                        .replace(sku, "")
                        .replace(Regex("[0-9]+[.,][0-9]{2}\\s*(?:€|e|eur)?", RegexOption.IGNORE_CASE), "")
                        .trim()
                    if (cleaned.length > 3) name = cleaned
                }
                if (name.isEmpty()) name = "Artikel $sku"
                val finalPrice = unitPrice.ifEmpty { totalPrice }

                val existing = itemsMap[sku]
                if (existing != null) {
                    itemsMap[sku] = existing.copy(qty = existing.qty + qty)
                } else {
                    itemsMap[sku] = ReceiptItem(sku = sku, name = name, qty = qty, price = finalPrice)
                }
            }
        }

        val items = itemsMap.values.toList()
        val isReceipt = (hasKeyword && items.isNotEmpty()) || items.size >= 2
        if (!isReceipt) return null

        return ReceiptScanResult(
            receiptNo = receiptNo.ifEmpty { barcode.take(10).ifEmpty { "RAČUN-${System.currentTimeMillis() % 10000}" } },
            receiptDate = receiptDate.ifEmpty { "" },
            barcode = barcode,
            items = items,
            rawText = rawText
        )
    }

    // --- QR KODE REGALOV & MEŠANIH ŠKATEL ---
    fun isShelfQRCode(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val clean = raw.trim()
        if (Regex("^(?:REGAL|LOC|SHELF|SKLADISCE:LOC):", RegexOption.IGNORE_CASE).containsMatchIn(clean)) return true
        if (Regex("^(?:R[1-6]-[AB]|S[ZSLB][12]?)-P[1-3]$", RegexOption.IGNORE_CASE).matches(clean)) return true
        if (Regex("^Regal\\s+[A-Za-z0-9-]+", RegexOption.IGNORE_CASE).containsMatchIn(clean)) return true
        return false
    }

    fun parseShelfQR(raw: String): ShelfQrInfo {
        val clean = raw.trim().replaceFirst(Regex("^(?:REGAL|LOC|SHELF|SKLADISCE:LOC):", RegexOption.IGNORE_CASE), "").trim()
        var friendly = clean
        val rackMatch = Regex("^R([1-6])-([AB])-P([1-3])$", RegexOption.IGNORE_CASE).find(clean)
        if (rackMatch != null) {
            val row = rackMatch.groupValues[1]
            val side = if (rackMatch.groupValues[2].uppercase(Locale.US) == "A") "Stran A (Zgoraj)" else "Stran B (Spodaj)"
            val shelfLabel = when (rackMatch.groupValues[3]) {
                "1" -> "Spodnja polica"
                "2" -> "Srednja polica"
                else -> "Zgornja polica"
            }
            friendly = "Regal $row • $side • $shelfLabel"
        } else {
            val wallMatch = Regex("^(SZ|SS|SL1|SL2)-P([1-3])$", RegexOption.IGNORE_CASE).find(clean)
            if (wallMatch != null) {
                val wallName = when (wallMatch.groupValues[1].uppercase(Locale.US)) {
                    "SZ" -> "Stena Zgoraj"
                    "SS" -> "Stena Spodaj"
                    "SL1" -> "Stena Levo (Nad vrati)"
                    else -> "Stena Levo (Pod vrati)"
                }
                val shelfLabel = when (wallMatch.groupValues[2]) {
                    "1" -> "Spodnja polica"
                    "2" -> "Srednja polica"
                    else -> "Zgornja polica"
                }
                friendly = "$wallName • $shelfLabel"
            }
        }
        return ShelfQrInfo(code = clean, displayName = friendly)
    }

    fun parseMixedBoxBarcode(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("^(?:BOX|SKATLA|MIXED)[-:#\\s]*([A-Za-z0-9]+)$", RegexOption.IGNORE_CASE).find(raw.trim())
        return m?.groupValues?.getOrNull(1)
    }
}
