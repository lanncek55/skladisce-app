/**
 * ocr-parser.js - Specializiran razčlenjevalnik etiket in napisov na skladiščnih škatlah (JYSK in splošna skladišča)
 */

(function(global) {
  const WarehouseLabelParser = {
    knownCatalog: {
      '3651003': { name: 'Mirror SANDAGER 3 pcs/pk oak', pieces: 3, box: 'Box 1/1' },
      '3690514': { name: 'Dining chair ADSLEV beige/n.oak', pieces: 2, box: 'Box 1/1' },
      '3690565': { name: 'Mirror BALSLEV 35x127 w.oak', pieces: 4, box: 'Box 1/1' },
      '3725150': { name: 'Garden chair FJELLERUP', pieces: 4, box: 'Box 1/1' },
      '37251550': { name: 'Garden chair FJELLERUP', pieces: 4, box: 'Box 1/1' },
      '3725155': { name: 'Garden chair FJELLERUP', pieces: 4, box: 'Box 1/1' },
      '3759803': { name: 'Stacking chair LARVIK grey', pieces: 24, box: 'Box 1/1' },
      '4334604': { name: 'ULVIK 50x70 cm', pieces: 12, box: 'Box 1/1' },
      '4318500': { name: 'PIL VANSE foam 30x47', pieces: 8, box: 'Box 1/1' },
      '3650189': { name: 'Pouffe AARS Dark Sand', pieces: 3, box: 'Box 1/1' },
      '3446278': { name: 'TM VINGAN 160x200cm', pieces: 1, box: 'Box 1/1' },
      '3610115': { name: 'Pedestal LYNGVIG Ø35 d.oak', pieces: 1, box: 'Box 1/1' },
      '3726157': { name: 'JYSK Artikel 3726157', pieces: 1, box: 'Box 1/1' },
      '3618930': { name: 'Predalnik LIMFJORDEN 4 predali bela', pieces: 1, box: 'Box 1/1' },
      '3670619': { name: 'DIVERSES', pieces: 1, box: 'Box 1/1' },
      '3601370': { name: 'Zložljiv stol VOEL žamet temno siva', pieces: 1, box: 'Box 1/1' },
      '3600634': { name: 'Računalniška miza HALSTED 60x120 črna', pieces: 1, box: 'Box 1/1' },
      '3680079': { name: 'Gaming stol RANDRUP črno umetno usnje/mr', pieces: 1, box: 'Box 1/1' },
      '3600628': { name: 'Klubska mizica RANDERUP Ø47 črna', pieces: 1, box: 'Box 1/1' }
    },

    /**
     * Zazna in razčleni trgovski račun (npr. JYSK račun z več artikli)
     * @param {string} rawText
     * @returns {Object|null}
     */
    parseReceipt(rawText) {
      if (!rawText || typeof rawText !== 'string') return null;
      const lines = rawText.split(/\r?\n/).map(l => l.trim()).filter(Boolean);

      const receiptKeywords = /\b(?:ra[cč]un(?:a|om|u)?|st\.?\s*ra[cč]|št\.?\s*ra[cč]|blagajn[aei]?|skupaj|ddv|tovarni[sš]ka|bre[zž]ice|celov[sš]ka|prevzem|eft|trgovina\s*d\.?o\.?o)\b/i;
      const hasKeyword = receiptKeywords.test(rawText);

      const receiptNoMatch = rawText.match(/(?:št\.?|st\.?)\s*ra[cč](?:un)?(?:\/storno)?[\s.:]*([0-9a-zA-Z\-]+)/i);
      const receiptNo = receiptNoMatch ? receiptNoMatch[1] : '';

      const dateMatch = rawText.match(/datum[\s.:]*([0-9\.\/]+(?:\s+[0-9:]+)?)/i);
      const receiptDate = dateMatch ? dateMatch[1] : '';

      const barcodeMatch = rawText.match(/\b([0-9]{16,24})\b/);
      const barcode = barcodeMatch ? barcodeMatch[1] : '';

      const itemsMap = new Map();

      for (let i = 0; i < lines.length; i++) {
        const line = lines[i];

        const artMatch = line.match(/(?:artikel|art\.?|šifra|sifra|koda)?[\s.:]*\b([0-9]{7,8})\b/i);
        const hasArtWord = /(?:artikel|art\.?|šifra|sifra|koda)/i.test(line);
        const priceMatch = line.match(/([0-9]+[.,][0-9]{2})\s*(?:€|e|eur)?/i);

        if (artMatch && (hasArtWord || (priceMatch && hasKeyword))) {
          const sku = artMatch[1];
          let qty = 1;
          let unitPrice = '';
          let totalPrice = priceMatch ? priceMatch[1] : '';

          // Preveri količino v isti vrstici (npr. "Artikel 3601370 2x 27.00")
          const inlineQtyMatch = line.match(/\b([0-9]+)\s*(?:x|kom|kos|pcs)\b/i);
          if (inlineQtyMatch) {
            qty = parseInt(inlineQtyMatch[1], 10) || 1;
          }

          // Iščemo naziv artikla in ločeno vrstico s količino ter ceno (npr. "2 x 13.50") nad vrstico artikla
          let name = '';
          for (let back = 1; back <= 3 && (i - back) >= 0; back++) {
            const candidate = lines[i - back];

            const qtyPriceLine = candidate.match(/^([0-9]+)\s*[xX*]\s*([0-9]+[.,][0-9]{2})/);
            if (qtyPriceLine) {
              qty = parseInt(qtyPriceLine[1], 10) || 1;
              unitPrice = qtyPriceLine[2].replace(',', '.');
              continue;
            }

            if (/(?:artikel|art\.?|šifra|sifra|koda)\s*[0-9]{7,8}/i.test(candidate)) break;
            if (/^(?:skupaj|ddv|neto|popust|znesek|cena|vrnjen|plačano|eft)\b/i.test(candidate)) break;
            if (receiptKeywords.test(candidate) && candidate.length < 35) continue;

            if (candidate.length > 2 && !/^[0-9.,\s€e\-\*\/]+$/i.test(candidate)) {
              name = candidate.replace(/\?(\d+)/g, 'Ø$1').replace(/^\?\s*/, 'Ø');
              break;
            }
          }

          if (!name) {
            const cleanedLine = line.replace(/(?:artikel|art\.?|šifra|sifra|koda)/gi, '').replace(sku, '').replace(/[0-9]+[.,][0-9]{2}\s*(?:€|e|eur)?/gi, '').trim();
            if (cleanedLine.length > 3) name = cleanedLine;
          }
          if (!name || name === 'Artikel ' + sku) {
            if (WarehouseLabelParser.knownCatalog && WarehouseLabelParser.knownCatalog[sku]) {
              name = WarehouseLabelParser.knownCatalog[sku].name;
            }
          }
          if (!name) name = 'Artikel ' + sku;

          const finalPrice = unitPrice || totalPrice;

          if (itemsMap.has(sku)) {
            const existing = itemsMap.get(sku);
            existing.qty += qty;
          } else {
            itemsMap.set(sku, { 
              sku, 
              name, 
              qty, 
              price: finalPrice, 
              totalPrice: totalPrice || finalPrice 
            });
          }
        }
      }

      const items = Array.from(itemsMap.values());
      const isReceipt = (hasKeyword && items.length > 0) || items.length >= 2;

      if (!isReceipt) return null;

      return {
        isReceipt: true,
        receiptNo: receiptNo || (barcode ? barcode.substring(0, 10) : 'RAČUN-' + Date.now().toString().slice(-4)),
        receiptDate: receiptDate || new Date().toLocaleDateString('sl-SI'),
        barcode: barcode,
        items: items,
        rawText: rawText
      };
    },

    /**
     * Pomožna funkcija: popravek pogostih OCR zamenjav pri številkah
     */
    sanitizeNum(s) {
      if (!s) return '';
      return s.replace(/O/gi, '0').replace(/[Il]/g, '1');
    },

    /**
     * Pomožna funkcija: odstranitev tipskih predpon etiket pred nazivom
     */
    cleanLabelPrefix(s) {
      if (!s) return '';
      return s.replace(/^(?:jysk\s*)?(?:product\s*(?:no\.?|number|text|desc)?|article\s*(?:name|text)?|art\.?\s*(?:no\.?|st\.?|št\.?|nr\.?)?|artikel|item(?:\s*no\.?)?|sku|vare[\s.:]*nr|koda|šifra|batch(?:\s*no\.?)?|supplier(?:\s*no\.?)?|order(?:\s*no\.?)?|date|datum|quantity|menge|koli[cč]ina)[\s.:]*/i, '').trim();
    },

    /**
     * Pomožna funkcija: preveri, ali je vrstica tehnični podatek (teža, mere, črtna koda, datum)
     */
    isPureTechnicalSpecs(str) {
      if (!str || str.length < 2) return true;
      if (/^(?:gross\s*weight|net\s*weight|weight|size(?:\/model)?|color(?:\/material)?|barcode|ean|batch(?:\s*no\.?)?|order(?:\s*no\.?)?|supplier(?:\s*no\.?)?|shipment(?:\s*id)?|date|datum|made\s*in|quantity|menge|koli[cč]ina|tel|phone)[\s.:]/i.test(str)) return true;
      // npr. "W56 x H93 x D66 cm" ali "Ø45/55/70 x D2.75/2.75/2.75 cm"
      if (/^[WwHhDdLlØ]\s*\d+.*x.*[WwHhDdLlØ\d]/i.test(str)) return true;
      // Izključno številke, mere in ločila
      if (/^[0-9\s.,\-\/:xX+*#%~_]+$/.test(str)) return true;
      return false;
    },

    /**
     * Preveri, če je vrstica povezana s škatlo ali količino (da je ne vključimo v naziv artikla)
     */
    isBoxOrQtyLine(str) {
      if (!str) return false;
      if (/(?:^|[^a-zA-Z0-9čžšČŽŠ])(?:box|colli|karton|package|pkg|[sš]katla|del|part)[\s.:]*[0-9]/i.test(str)) return true;
      if (/^\s*[1-9]\s*[\/|\\]\s*[1-9]\s*$/.test(str)) return true;
      if (/(?:quantity|menge|koli[cč]ina|colli\s*size|qty)[\s.:]+[0-9]/i.test(str)) return true;
      if (/^[0-9]+\s*(?:pcs\.?|kom|kos)\b/i.test(str)) return true;
      return false;
    },

    /**
     * Preveri, ali se naziv artikla prelije v naslednjo vrstico in ga po potrebi spoji
     * (npr. "Wall unit module GEDSTED" v 1. vrstici in "cabinet d beige" v 2. vrstici)
     */
    appendNameOverflow(lines, startIdx, initialName) {
      let name = (initialName || '').trim();
      if (!name) return '';

      for (let j = startIdx + 1; j < lines.length && j <= startIdx + 2; j++) {
        const nextLine = (lines[j] || '').trim();
        if (!nextLine) break;
        if (WarehouseLabelParser.isPureTechnicalSpecs(nextLine)) break;
        if (WarehouseLabelParser.isBoxOrQtyLine(nextLine)) break;
        if (/^[0-9OIl]{6,10}$/.test(nextLine)) break;
        if (/\b(?:barcode|ean|order|batch|date|datum|supplier)\b/i.test(nextLine)) break;

        // Če vsebuje veljavne besede in ni tehnična oznaka, gre za preliv imena
        if (/[a-zA-Z]{2,}/.test(nextLine) && nextLine.length <= 65) {
          name += ' ' + nextLine;
        } else {
          break;
        }
      }

      return name.replace(/\s{2,}/g, ' ').trim();
    },

    /**
     * Pomožna funkcija: razčleni podatke o škatli (npr. Box 1/1, Škatla 1/2, Colli 2/2)
     */
    parseBoxInfo(lines) {
      let bCurrent = 1;
      let bTotal = 1;
      let qInBox = null;

      // 1. Z izrecno besedo za škatlo (vključno s šumniki v slovenščini)
      const explicitBoxRegex = /(?:^|[^a-zA-Z0-9čžšČŽŠ])(?:box|colli|karton|package|pkg|[sš]katla|del|part)[\s.:]*([0-9]{1,2})\s*(?:[\/|\\]|\b(?:of|von)\b)\s*([0-9]{1,2})(?:[\s.:]+([0-9]+)\s*(?:pcs\.?|kom|kos)?)?/i;
      for (const line of lines) {
        const m = line.match(explicitBoxRegex);
        if (m) {
          bCurrent = parseInt(m[1], 10) || 1;
          bTotal = parseInt(m[2], 10) || 1;
          if (m[3]) qInBox = parseInt(m[3], 10);
          return { bCurrent, bTotal, qInBox };
        }
      }

      // 2. Samostojna vrstica z majhnima številkama "1/2" ali "2/2"
      const soloBoxRegex = /^\s*([1-9])\s*[\/|\\]\s*([1-9])\s*$/;
      for (const line of lines) {
        const m = line.match(soloBoxRegex);
        if (m) {
          bCurrent = parseInt(m[1], 10) || 1;
          bTotal = parseInt(m[2], 10) || 1;
          return { bCurrent, bTotal, qInBox };
        }
      }

      return { bCurrent: 1, bTotal: 1, qInBox: null };
    },

    /**
     * Pomožna funkcija: razčleni število kosov v paketu/škatli
     */
    parseQuantity(lines, boxQty) {
      if (boxQty && boxQty > 0) return boxQty;

      // A) Tablični format (npr. "24.07.2025  3759803  24  3604185")
      for (const line of lines) {
        const tm = line.match(/\b(?:[0-9]{2}[\.\-\/][0-9]{2}[\.\-\/][0-9]{4}|[0-9]{4}[\.\-\/][0-9]{2}[\.\-\/][0-9]{2})\s+[0-9OIl]{7,8}\s+([0-9]{1,3})\s+[0-9]{6,10}\b/);
        if (tm) return parseInt(tm[1], 10);
      }

      // B) Izrecna oznaka Quantity / Menge / Količina / Colli Size
      for (const line of lines) {
        const qm = line.match(/(?:quantity|koli[cč]ina|menge|colli\s*size|qty)[\s.:]+([0-9]+)\b/i);
        if (qm) return parseInt(qm[1], 10);
      }

      // C) X pcs/pk ali pcs/box (npr. "3 pcs/pk oak")
      for (const line of lines) {
        const pkm = line.match(/\b([0-9]+)\s*(?:pcs|kom|kos)\s*[\/|\\]\s*(?:pk|pack|karton|ctn|box|komplet)\b/i);
        if (pkm) return parseInt(pkm[1], 10);
      }

      // D) Začetek vrstice z X pcs (npr. "4 pcs")
      for (const line of lines) {
        const sm = line.match(/^([0-9]+)\s*(?:pcs\.?|kom|kos)\b/i);
        if (sm) return parseInt(sm[1], 10);
      }

      return 1;
    },

    /**
     * Razčleni enega kandidata (vrstice besedila ene etikete)
     */
    evaluateLabelCandidate(lines, fullText, options = {}) {
      const knownCatalog = WarehouseLabelParser.knownCatalog || {};
      let sku = '';
      let skuScore = 0;
      let skuLineIdx = -1;

      // 0. PRED-ANALIZA: Poišči EAN / črtno kodo na etiketi, da jo izločimo iz kandidatov za SKU
      let labelBarcode = '';
      for (const line of lines) {
        const bm = line.match(/(?:barcode|ean)[\s.:]*([0-9\s]{8,15})/i);
        if (bm) {
          labelBarcode = bm[1].replace(/\s+/g, '');
          break;
        }
      }

      function isBarcodePrefix(cand) {
        if (!cand) return false;
        if (labelBarcode && labelBarcode.startsWith(cand)) return true;
        return false;
      }

      // 1. ISKANJE ŠIFRE ARTIKLA (SKU)
      // Nivo 1 (+95): Izrecna oznaka (JYSK product no., Article, Artikel, Item no., SKU, itd.)
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        if (/(?:jysk\s*)?(?:product\s*(?:no\.?|number)?|article|art\.?\s*(?:no\.?|st\.?|št\.?|nr\.?)?|artikel|item(?:\s*no\.?)?|sku|vare[\s.:]*nr|koda|šifra)/i.test(line)) {
          // Preveri najprej, če je v vrstici vzorec sku / sku2 (pomembna je prva koda - sku)
          const slashM = line.match(/\b([0-9OIl]{6,8})\s*[\/|\\]\s*([0-9OIl]{6,10})\b/);
          if (slashM) {
            const code1 = WarehouseLabelParser.sanitizeNum(slashM[1]);
            const code2 = WarehouseLabelParser.sanitizeNum(slashM[2]);
            let chosen = code1;
            if (knownCatalog[code1] || options?.knownSkus?.has?.(code1)) {
              chosen = code1;
            } else if (knownCatalog[code2] || options?.knownSkus?.has?.(code2)) {
              chosen = code2;
            }
            if (/^[0-9]{6,8}$/.test(chosen) && !isBarcodePrefix(chosen)) {
              sku = chosen;
              skuScore = 96;
              skuLineIdx = i;
              break;
            }
          }

          const m = line.match(/\b([0-9OIl]{6,8})\b/);
          if (m) {
            const c = WarehouseLabelParser.sanitizeNum(m[1]);
            if (/^[0-9]{6,8}$/.test(c) && !isBarcodePrefix(c)) {
              sku = c;
              skuScore = 95;
              skuLineIdx = i;
              break;
            }
          }

          // Preglej do 2 vrstici naprej (npr. "JYSK product \n number, \n 4334604")
          for (let offset = 1; offset <= 2 && i + offset < lines.length; offset++) {
            const nextL = lines[i + offset];
            if (WarehouseLabelParser.isPureTechnicalSpecs(nextL)) break;
            const nm = nextL.match(/\b([0-9OIl]{6,8})\b/);
            if (nm) {
              const c = WarehouseLabelParser.sanitizeNum(nm[1]);
              if (/^[0-9]{6,8}$/.test(c) && !isBarcodePrefix(c)) {
                sku = c;
                skuScore = 95;
                skuLineIdx = i + offset;
                break;
              }
            }
          }
          if (sku) break;
        }
      }

      // Nivo 2 (+92): JYSK vzorec poševnice "sku / sku2" (npr. "3651003 / 85823001" ali "123456 / 8582300")
      // Pomembna je samo sku (prva šifra pred poševnico)
      if (!sku) {
        for (let i = 0; i < lines.length; i++) {
          const slashM = lines[i].match(/\b([0-9OIl]{6,8})\s*[\/|\\]\s*([0-9OIl]{6,10})\b/);
          if (slashM) {
            const code1 = WarehouseLabelParser.sanitizeNum(slashM[1]);
            const code2 = WarehouseLabelParser.sanitizeNum(slashM[2]);
            let chosen = code1;
            if (knownCatalog[code1] || options?.knownSkus?.has?.(code1)) {
              chosen = code1;
            } else if (knownCatalog[code2] || options?.knownSkus?.has?.(code2)) {
              chosen = code2;
            } else {
              chosen = code1; // Pomembna je prva šifra (sku v sku/sku2)
            }

            if (/^[0-9]{6,8}$/.test(chosen)) {
              sku = chosen;
              skuScore = 92;
              skuLineIdx = i;
              break;
            }
          }
        }
      }

      // Nivo 3 (+88): Tablični format nalepke (npr. "24.07.2025  3759803  24  3604185")
      if (!sku) {
        for (let i = 0; i < lines.length; i++) {
          const tableM = lines[i].match(/\b(?:[0-9]{2}[\.\-\/][0-9]{2}[\.\-\/][0-9]{4}|[0-9]{4}[\.\-\/][0-9]{2}[\.\-\/][0-9]{2})\s+([0-9OIl]{7,8})\s+([0-9]{1,3})\b/);
          if (tableM) {
            const c = WarehouseLabelParser.sanitizeNum(tableM[1]);
            if (/^[0-9]{7,8}$/.test(c)) {
              sku = c;
              skuScore = 88;
              skuLineIdx = i;
              break;
            }
          }
        }
      }

      // Nivo 4 (+85): Šifra s pomišljajem pred nazivom (npr. "3670653 - Wall unit module GEDSTED")
      if (!sku) {
        for (let i = 0; i < lines.length; i++) {
          const dashM = lines[i].match(/\b([0-9OIl]{7,8})\s*[\-\–\—]\s*[a-zA-Z]/);
          if (dashM) {
            const c = WarehouseLabelParser.sanitizeNum(dashM[1]);
            if (/^[0-9]{7,8}$/.test(c)) {
              sku = c;
              skuScore = 85;
              skuLineIdx = i;
              break;
            }
          }
        }
      }

      // Nivo 5 (+80): Šifra v znanem katalogu ali bazi
      if (!sku) {
        for (let i = 0; i < lines.length; i++) {
          if (WarehouseLabelParser.isPureTechnicalSpecs(lines[i])) continue;
          const allNums = lines[i].match(/\b([0-9]{7,8})\b/g);
          if (allNums) {
            for (const num of allNums) {
              if (knownCatalog[num] || options?.knownSkus?.has?.(num)) {
                sku = num;
                skuScore = 80;
                skuLineIdx = i;
                break;
              }
            }
            if (sku) break;
          }
        }
      }

      // Nivo 6 (+60): 7-8 števk na nalepki z Box ali Qty kontekstom
      if (!sku) {
        const hasBox = lines.some(l => /(?:box|colli|karton|[sš]katla)/i.test(l) || /^\s*[1-9]\s*[\/|\\]\s*[1-9]\s*$/.test(l));
        const hasQty = lines.some(l => /(?:quantity|colli|qty|pcs|kom|kos)/i.test(l));
        if (hasBox || hasQty) {
          for (let i = 0; i < lines.length; i++) {
            if (/order|barcode|batch|ean|tel|phone|gross|weight/i.test(lines[i])) continue;
            const anyM = lines[i].match(/\b([0-9]{7,8})\b/);
            if (anyM && !isBarcodePrefix(anyM[1])) {
              sku = anyM[1];
              skuScore = 60;
              skuLineIdx = i;
              break;
            }
          }
        }
      }

      if (!sku) return null;

      // 2. PODATKI O ŠKATLI IN KOLIČINI
      const { bCurrent, bTotal, qInBox: boxQty } = WarehouseLabelParser.parseBoxInfo(lines);
      let qInBox = WarehouseLabelParser.parseQuantity(lines, boxQty);
      if (!qInBox && knownCatalog[sku]?.pieces) {
        qInBox = knownCatalog[sku].pieces;
      }
      if (!qInBox || isNaN(qInBox) || qInBox <= 0) qInBox = 1;

      // 3. NAZIV ARTIKLA
      let prodName = '';

      // A) Iz kataloga po točni šifri (če obstaja)
      if (knownCatalog[sku]?.name) {
        prodName = knownCatalog[sku].name;
      }
      if (!prodName && options?.knownCatalog?.[sku]?.name) {
        prodName = options.knownCatalog[sku].name;
      }

      // B) Prioriteta 1A: Naziv v isti vrstici za vzorcem poševnice (npr. "123456 / 3670653 Wall unit module GEDSTED")
      if (!prodName && skuLineIdx !== -1 && skuLineIdx < lines.length) {
        const slashLine = lines[skuLineIdx];
        if (/[\/|\\]/.test(slashLine)) {
          const afterSlash = slashLine.replace(/^.*?\b[0-9OIl]{6,8}\s*[\/|\\]\s*[0-9OIl]{6,10}\b[\s\-\–\—\:\/]*/i, '').trim();
          const cleanedAfterSlash = WarehouseLabelParser.cleanLabelPrefix(afterSlash);
          if (cleanedAfterSlash.length >= 3 && /[a-zA-Z]{2,}/.test(cleanedAfterSlash) && !WarehouseLabelParser.isPureTechnicalSpecs(cleanedAfterSlash)) {
            prodName = WarehouseLabelParser.appendNameOverflow(lines, skuLineIdx, cleanedAfterSlash);
          }
        }
      }

      // C) Prioriteta 1B: Izrecna oznaka naziva na etiketi (JYSK product text, Product name, Article name, Naziv, Opis)
      if (!prodName) {
        for (let i = 0; i < lines.length; i++) {
          const tm = lines[i].match(/(?:jysk\s*)?(?:product\s*(?:text|name|desc)|article\s*(?:name|text)|item\s*name|naziv|opis)[\s.:]+(.+)/i);
          if (tm) {
            const cand = tm[1].trim();
            const cleaned = WarehouseLabelParser.cleanLabelPrefix(cand);
            if (cleaned.length >= 2 && /[a-zA-Z]{2,}/.test(cleaned) && !WarehouseLabelParser.isPureTechnicalSpecs(cleaned)) {
              prodName = WarehouseLabelParser.appendNameOverflow(lines, i, cleaned);
              break;
            }
          }
        }
      }

      // D) Prioriteta 2: V isti vrstici kot SKU z ločilom (npr. "3670653 - Wall unit module GEDSTED")
      if (!prodName && skuLineIdx !== -1 && skuLineIdx < lines.length) {
        const artLine = lines[skuLineIdx];
        const afterSku = artLine.replace(new RegExp('^.*?\\b' + sku + '\\b[\\s\\-\\–\\—\\:\\/]*', 'i'), '').trim();
        const cleanedAfter = WarehouseLabelParser.cleanLabelPrefix(afterSku);
        if (cleanedAfter.length >= 3 && /[a-zA-Z]{2,}/.test(cleanedAfter) && !WarehouseLabelParser.isPureTechnicalSpecs(cleanedAfter)) {
          prodName = WarehouseLabelParser.appendNameOverflow(lines, skuLineIdx, cleanedAfter);
        }
        if (!prodName) {
          const beforeSku = artLine.replace(new RegExp('[\\s\\-\\–\\—\\:\\/]*\\b' + sku + '\\b.*$', 'i'), '').trim();
          const cleanedBefore = WarehouseLabelParser.cleanLabelPrefix(beforeSku);
          if (cleanedBefore.length >= 3 && /[a-zA-Z]{2,}/.test(cleanedBefore) && !WarehouseLabelParser.isPureTechnicalSpecs(cleanedBefore)) {
            prodName = WarehouseLabelParser.appendNameOverflow(lines, skuLineIdx, cleanedBefore);
          }
        }
      }

      // E) Prioriteta 3: Vrstica takoj PO šifri (npr. Dining chair ADSLEV, Mirror SANDAGER)
      if (!prodName && skuLineIdx !== -1 && skuLineIdx + 1 < lines.length) {
        const nextLine = lines[skuLineIdx + 1];
        const cleanedNext = WarehouseLabelParser.cleanLabelPrefix(nextLine);
        if (cleanedNext.length >= 3 && /[a-zA-Z]{2,}/.test(cleanedNext) && !WarehouseLabelParser.isPureTechnicalSpecs(nextLine) && !WarehouseLabelParser.isBoxOrQtyLine(nextLine)) {
          prodName = WarehouseLabelParser.appendNameOverflow(lines, skuLineIdx + 1, cleanedNext);
        }
      }

      // F) Prioriteta 4: Vrstica takoj PRED šifro
      if (!prodName && skuLineIdx > 0) {
        const prevLine = lines[skuLineIdx - 1];
        const cleanedPrev = WarehouseLabelParser.cleanLabelPrefix(prevLine);
        if (cleanedPrev.length >= 3 && /[a-zA-Z]{2,}/.test(cleanedPrev) && !WarehouseLabelParser.isPureTechnicalSpecs(prevLine) && !WarehouseLabelParser.isBoxOrQtyLine(prevLine)) {
          prodName = WarehouseLabelParser.appendNameOverflow(lines, skuLineIdx - 1, cleanedPrev);
        }
      }

      // G) Prioriteta 5: Iskanje po ključnih pohištvenih besedah
      if (!prodName) {
        const furnitureKeywords = /\b(?:mirror|chair|stol|miza|table|desk|pouffe|pedestal|ulvik|sandager|fjellerup|larvik|vingan|vanse|aars|lyngvig|gedsted|halsted|randrup|randerup|limfjorden|voel|adslev|balslev|foam|pillow|vzglavnik|omara|polica|regal|predalnik|komoda|postelja|posteljni|bed|sofa|couch|cabinet|wardrobe|bench|cushion|duvet|mattress|rug|curtain|basket|box|rack|stand|drawer|unit|module|shelf|sideboard|nightstand|armchair|bookcase)\b/i;
        for (let i = 0; i < lines.length; i++) {
          const line = lines[i];
          if (furnitureKeywords.test(line) && !WarehouseLabelParser.isPureTechnicalSpecs(line) && !WarehouseLabelParser.isBoxOrQtyLine(line)) {
            const cleaned = WarehouseLabelParser.cleanLabelPrefix(line);
            if (cleaned.length >= 3) {
              prodName = WarehouseLabelParser.appendNameOverflow(lines, i, cleaned);
              break;
            }
          }
        }
      }

      // H) Prioriteta 6: Katerakoli veljavna opisna vrstica z besedami
      if (!prodName) {
        for (const line of lines) {
          if (/(?:box|colli|karton|[sš]katla)/i.test(line)) continue;
          if (WarehouseLabelParser.isPureTechnicalSpecs(line)) continue;
          const cleaned = WarehouseLabelParser.cleanLabelPrefix(line);
          if (cleaned.length >= 4 && /[a-zA-Z]{3,}/.test(cleaned) && !/^[0-9\s.,\-\/xX]+$/.test(cleaned)) {
            prodName = cleaned;
            break;
          }
        }
      }

      if (!prodName) prodName = `Artikel ${sku}`;

      return {
        articleNumber: sku,
        name: prodName,
        boxCurrent: bCurrent,
        boxTotal: bTotal,
        quantityInBox: qInBox,
        score: skuScore + (prodName.startsWith('Artikel ') ? 0 : 20),
        matchScore: skuScore + (prodName.startsWith('Artikel ') ? 0 : 20),
        rawText: fullText
      };
    },

    /**
     * Glavna funkcija za analizo etiket škatel in računov
     * @param {string} rawText
     * @param {Array} [blocks=null]
     * @param {Object} [options={}]
     * @returns {Object}
     */
    parse(rawText, blocks = null, options = {}) {
      if (!rawText || typeof rawText !== 'string') {
        return {
          articleNumber: '',
          name: '',
          boxCurrent: 1,
          boxTotal: 1,
          quantityInBox: 1,
          score: 0,
          matchScore: 0,
          rawText: ''
        };
      }

      // 0. Preveri ali gre za trgovski račun kupca
      const receiptData = this.parseReceipt(rawText);
      if (receiptData && receiptData.isReceipt) {
        return receiptData;
      }

      const allLines = rawText.split(/\r?\n/).map(l => l.trim()).filter(Boolean);

      // Kandidati za evalvacijo
      const candidates = [];

      // A) Kandidat 1 je VEDNO celotno besedilo (zagotavlja zanesljivost pri visoki ločljivosti in zveznem branju)
      candidates.push({
        lines: allLines,
        fullText: rawText,
        center: options?.imageCenter || null
      });

      // B) Če imamo strukturirane bloke z bounding boxi, dodamo še prostorske grozde
      if (Array.isArray(blocks) && blocks.length > 1) {
        function getRect(blk) {
          const b = blk.boundingBox || {};
          const left = b.left ?? b.x ?? 0;
          const top = b.top ?? b.y ?? 0;
          const width = b.width ?? (b.right ? b.right - left : 0);
          const height = b.height ?? (b.bottom ? b.bottom - top : 0);
          return {
            left, top, right: left + width, bottom: top + height,
            width, height,
            cx: left + width / 2, cy: top + height / 2,
            raw: blk.rawValue || blk.text || ''
          };
        }

        function rectDist(r1, r2) {
          const dx = Math.max(0, Math.max(r1.left, r2.left) - Math.min(r1.right, r2.right));
          const dy = Math.max(0, Math.max(r1.top, r2.top) - Math.min(r1.bottom, r2.bottom));
          return Math.sqrt(dx * dx + dy * dy);
        }

        const rects = blocks.map(getRect).filter(r => r.raw.trim().length > 0);
        const clusters = [];
        const visited = new Set();
        // Prilagodljiva razdalja glede na ločljivost (vsaj 250px)
        const maxClusterDist = Math.max(250, (options?.imageWidth || 1080) * 0.25);

        for (let i = 0; i < rects.length; i++) {
          if (visited.has(i)) continue;
          visited.add(i);
          const cluster = [rects[i]];
          const queue = [rects[i]];

          while (queue.length > 0) {
            const cur = queue.shift();
            for (let j = 0; j < rects.length; j++) {
              if (!visited.has(j)) {
                if (rectDist(cur, rects[j]) <= maxClusterDist) {
                  visited.add(j);
                  cluster.push(rects[j]);
                  queue.push(rects[j]);
                }
              }
            }
          }
          clusters.push(cluster);
        }

        for (const cl of clusters) {
          cl.sort((a, b) => (a.top - b.top) || (a.left - b.left));
          const left = Math.min(...cl.map(r => r.left));
          const top = Math.min(...cl.map(r => r.top));
          const right = Math.max(...cl.map(r => r.right));
          const bottom = Math.max(...cl.map(r => r.bottom));
          const clText = cl.map(r => r.raw).join('\n');
          const clLines = clText.split(/\r?\n/).map(l => l.trim()).filter(Boolean);

          candidates.push({
            lines: clLines,
            fullText: clText,
            center: { x: (left + right) / 2, y: (top + bottom) / 2 }
          });
        }
      }

      // Evalviramo vse kandidate
      const evaluated = [];
      for (const cand of candidates) {
        const res = this.evaluateLabelCandidate(cand.lines, cand.fullText, options);
        if (res && res.articleNumber) {
          let score = res.score || 0;
          if (cand.center && options?.imageCenter) {
            const dx = cand.center.x - options.imageCenter.x;
            const dy = cand.center.y - options.imageCenter.y;
            const dist = Math.sqrt(dx * dx + dy * dy);
            const maxDist = Math.max(1, options.imageCenter.x || 500);
            score -= Math.min(30, (dist / maxDist) * 20);
          }
          evaluated.push({ ...res, score, matchScore: score });
        }
      }

      if (evaluated.length === 0) {
        return {
          articleNumber: '',
          name: '',
          boxCurrent: 1,
          boxTotal: 1,
          quantityInBox: 1,
          score: 0,
          matchScore: 0,
          rawText
        };
      }

      // Izberemo kandidate z najvišjo oceno
      evaluated.sort((a, b) => b.score - a.score);
      const best = evaluated[0];

      return {
        articleNumber: best.articleNumber,
        name: best.name,
        boxCurrent: best.boxCurrent,
        boxTotal: best.boxTotal,
        quantityInBox: best.quantityInBox,
        score: best.score,
        matchScore: best.matchScore,
        rawText: best.rawText
      };
    }
  };

  global.WarehouseLabelParser = WarehouseLabelParser;
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = WarehouseLabelParser;
  }
})(typeof window !== 'undefined' ? window : globalThis);
