/**
 * db.js - Normaliziran IndexedDB modul za vodenje zalog skladišča
 * Razdeljen na 3 vitke tabele:
 * 1. articles (Šifrant artiklov - ključ je SKU)
 * 2. stock_locations (Ploščata zaloga po škatlah in regalih)
 * 3. logs (Zgodovina premikov in izdaj)
 */

const DB_NAME = 'SkladisceDB';
const DB_VERSION = 4; // Popoln sistem poslovalnice (trgovina, več skladišč, mešane škatle, nabiranje po policah)

let dbInstance = null;

function openDB() {
  if (dbInstance) return Promise.resolve(dbInstance);

  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);

    request.onupgradeneeded = (event) => {
      const db = event.target.result;

      // 1. Tabela artiklov (Katalog / Šifrant artiklov)
      if (!db.objectStoreNames.contains('articles')) {
        const articleStore = db.createObjectStore('articles', { keyPath: 'sku' });
        articleStore.createIndex('name', 'name', { unique: false });
        articleStore.createIndex('category', 'category', { unique: false });
      }

      // 2. Tabela lokacij in zaloge (Ploščata zaloga po škatlah in regalih)
      let stockStore;
      if (!db.objectStoreNames.contains('stock_locations')) {
        stockStore = db.createObjectStore('stock_locations', { keyPath: 'id' });
        stockStore.createIndex('sku', 'sku', { unique: false });
        stockStore.createIndex('shelf', 'shelf', { unique: false });
        stockStore.createIndex('boxNum', 'boxNum', { unique: false });
        stockStore.createIndex('warehouse', 'warehouse', { unique: false });
        stockStore.createIndex('boxId', 'boxId', { unique: false });
      } else {
        stockStore = event.target.transaction.objectStore('stock_locations');
        if (!stockStore.indexNames.contains('warehouse')) {
          stockStore.createIndex('warehouse', 'warehouse', { unique: false });
        }
        if (!stockStore.indexNames.contains('boxId')) {
          stockStore.createIndex('boxId', 'boxId', { unique: false });
        }
      }

      // 3. Tabela premikov (Zgodovina / Dnevnik)
      if (!db.objectStoreNames.contains('logs')) {
        const logStore = db.createObjectStore('logs', { keyPath: 'id', autoIncrement: true });
        logStore.createIndex('sku', 'sku', { unique: false });
        logStore.createIndex('timestamp', 'timestamp', { unique: false });
      }

      // 4. Tabela mešanih škatel (več različnih soft artiklov v enem kartonu)
      if (!db.objectStoreNames.contains('mixed_boxes')) {
        const mixedStore = db.createObjectStore('mixed_boxes', { keyPath: 'boxId' });
        mixedStore.createIndex('warehouse', 'warehouse', { unique: false });
        mixedStore.createIndex('shelf', 'shelf', { unique: false });
      }

      // 5. Tabela nabiralnih nalog / seznama za dopolnitev polic v trgovini
      if (!db.objectStoreNames.contains('replenishment_list')) {
        const repStore = db.createObjectStore('replenishment_list', { keyPath: 'id', autoIncrement: true });
        repStore.createIndex('sku', 'sku', { unique: false });
        repStore.createIndex('status', 'status', { unique: false });
        repStore.createIndex('warehouse', 'warehouse', { unique: false });
      }

      // 6. Tabela konfiguracije skladišč poslovalnice
      if (!db.objectStoreNames.contains('warehouses')) {
        const whStore = db.createObjectStore('warehouses', { keyPath: 'id' });
        whStore.createIndex('name', 'name', { unique: false });
      }
    };

    request.onsuccess = (event) => {
      dbInstance = event.target.result;
      resolve(dbInstance);
    };

    request.onerror = (event) => {
      reject('Napaka pri odpiranju IndexedDB: ' + event.target.error);
    };
  });
}

function parseBoxInfo(boxInfo) {
  const match = String(boxInfo || '').match(/(\d+)\s*[\/:]\s*(\d+)/);
  if (match) {
    return {
      boxCurrent: parseInt(match[1], 10) || 1,
      boxTotal: Math.max(1, parseInt(match[2], 10) || 1)
    };
  }
  return { boxCurrent: 1, boxTotal: 1 };
}

function normalizeBoxLocations(bData, fallbackLoc = 'Brez lokacije') {
  if (!bData) {
    return {
      count: 0,
      location: fallbackLoc,
      locations: [{ name: fallbackLoc, count: 0, isPrimary: true }]
    };
  }

  let locations = [];
  if (Array.isArray(bData.locations) && bData.locations.length > 0) {
    locations = bData.locations.map((loc, idx) => ({
      name: String(loc.name || loc.location || fallbackLoc).trim(),
      count: Math.max(0, Number(loc.count) || 0),
      isPrimary: (idx === 0)
    }));
  } else {
    const rawLoc = String(bData.location || fallbackLoc).trim();
    const parts = rawLoc.split(/\s*[\/,+]\s*/).filter(Boolean);
    if (parts.length > 1 && parts.some(p => /\(\s*\d+\s*(?:kos|kom)?\s*\)/i.test(p))) {
      let parsedSum = 0;
      parts.forEach((p, idx) => {
        const countMatch = p.match(/\(\s*(\d+)\s*(?:kos|kom)?\s*\)/i);
        const count = countMatch ? parseInt(countMatch[1], 10) : 0;
        const cleanName = p.replace(/\(\s*\d+\s*(?:kos|kom)?\s*\)/i, '').trim();
        parsedSum += count;
        locations.push({
          name: cleanName || fallbackLoc,
          count: count,
          isPrimary: (idx === 0)
        });
      });
      const bCount = Math.max(0, Number(bData.count) || 0);
      if (bCount > parsedSum && locations.length > 0) {
        locations[0].count += (bCount - parsedSum);
      }
    } else {
      locations = [{
        name: rawLoc || fallbackLoc,
        count: Math.max(0, Number(bData.count) || 0),
        isPrimary: true
      }];
    }
  }

  if (locations.length === 0) {
    locations.push({ name: fallbackLoc, count: Math.max(0, Number(bData.count) || 0), isPrimary: true });
  }

  const totalCount = locations.reduce((sum, l) => sum + (Number(l.count) || 0), 0);
  let summaryLoc = '';
  if (locations.length <= 1) {
    summaryLoc = locations[0]?.name || fallbackLoc;
  } else {
    summaryLoc = locations.map(l => `${l.name} (${l.count} kos)`).join(' / ');
  }

  return {
    count: totalCount,
    location: summaryLoc,
    locations: locations
  };
}

function normalizeItemBoxes(item) {
  if (!item) return item;
  const parsed = parseBoxInfo(item.boxInfo);
  const boxTotal = Math.max(1, parseInt(item.boxTotal, 10) || parsed.boxTotal);
  item.boxTotal = boxTotal;

  if (!item.boxes || typeof item.boxes !== 'object') {
    item.boxes = {};
  }

  for (let b = 1; b <= boxTotal; b++) {
    const rawBox = item.boxes[b] || {
      count: (boxTotal === 1) ? (Number(item.quantity) || 0) : ((b === parsed.boxCurrent) ? (Number(item.quantity) || 0) : 0),
      location: String(item.location || 'Brez lokacije').trim()
    };
    item.boxes[b] = normalizeBoxLocations(rawBox, item.location || 'Brez lokacije');
  }

  return item;
}

const DB = {
  parseBoxInfo,
  normalizeBoxLocations,
  normalizeItemBoxes,

  /**
   * Sestavi enoten objekt artikla iz tabele 'articles' in pripadajočih vrstic 'stock_locations'
   */
  buildItemFromArticleAndStocks(art, stocks = []) {
    const sku = String(art.sku || '').trim();
    const boxTotal = Math.max(1, parseInt(art.boxTotal, 10) || 1);
    const boxes = {};
    const boxCounts = [];

    const byBox = new Map();
    for (let b = 1; b <= boxTotal; b++) byBox.set(b, []);
    for (const s of stocks) {
      const bNum = Math.min(boxTotal, Math.max(1, parseInt(s.boxNum, 10) || 1));
      if (!byBox.has(bNum)) byBox.set(bNum, []);
      byBox.get(bNum).push(s);
    }

    let primaryShelf = 'Brez lokacije';
    let storeQty = 0;
    let zgornjeQty = 0;
    let spodnjeQty = 0;

    for (let b = 1; b <= boxTotal; b++) {
      const locList = byBox.get(b) || [];
      const formattedLocs = locList.map(l => {
        const count = Math.max(0, Number(l.count) || 0);
        const wh = l.warehouse || (l.shelf && l.shelf.startsWith('TRG') ? 'trgovina' : 'zgornje');
        if (wh === 'trgovina') storeQty += count;
        else if (wh === 'spodnje') spodnjeQty += count;
        else zgornjeQty += count;

        return {
          name: l.shelf || 'Brez lokacije',
          count: count,
          isPrimary: !!l.isPrimary,
          warehouse: wh,
          boxId: l.boxId || null
        };
      });

      if (formattedLocs.length === 0) {
        formattedLocs.push({ name: 'Brez lokacije', count: 0, isPrimary: true, warehouse: 'zgornje', boxId: null });
      }

      const totalCount = formattedLocs.reduce((sum, l) => sum + l.count, 0);
      boxCounts.push(totalCount);

      const activePrimary = formattedLocs.find(l => l.isPrimary) || formattedLocs[0];
      if (b === 1 && activePrimary && activePrimary.name !== 'Brez lokacije') {
        primaryShelf = activePrimary.name;
      }

      const summary = formattedLocs.length <= 1
        ? (formattedLocs[0]?.name || 'Brez lokacije')
        : formattedLocs.map(l => `${l.name}${l.boxId ? ` (Škatla #${l.boxId})` : ''} (${l.count} kos)`).join(' / ');

      boxes[b] = {
        count: totalCount,
        location: summary,
        locations: formattedLocs
      };
    }

    const completeSets = (boxTotal > 1) ? Math.min(...boxCounts) : (boxCounts[0] || 0);
    const shelfCap = Math.max(0, Number(art.shelfCapacity) || 0);
    const neededForShelf = shelfCap > 0 ? Math.max(0, shelfCap - storeQty) : 0;
    const catType = art.categoryType || (/tekstil|blazin|odej|brisa|postelj|rjuhe|vzglavnik|soft|copat|zaves/i.test((art.category || '') + ' ' + (art.name || '')) ? 'soft' : 'furniture');

    return {
      id: sku, // SKU je primarni identifikator artikla
      sku: sku,
      name: art.name || `Artikel ${sku}`,
      category: art.category || 'Splošno',
      boxTotal: boxTotal,
      boxInfo: `Box 1/${boxTotal}`,
      minQuantity: Number(art.minQuantity) || 0,
      unit: art.unit || (boxTotal > 1 ? 'garnitura' : 'kos'),
      notes: art.notes || '',
      lastUpdated: art.lastUpdated || new Date().toISOString(),
      location: primaryShelf,
      quantity: completeSets,
      boxes: boxes,
      // Razširitve za poslovalnico in police
      shelfCapacity: shelfCap,
      shelfLocation: art.shelfLocation || '',
      categoryType: catType,
      storeQuantity: storeQty,
      warehouseQuantity: zgornjeQty + spodnjeQty,
      zgornjeQuantity: zgornjeQty,
      spodnjeQuantity: spodnjeQty,
      neededForShelf: neededForShelf
    };
  },

  getBoxStats(item) {
    const it = normalizeItemBoxes(item);
    const boxTotal = it.boxTotal;
    const counts = [];
    const breakdown = [];
    const missing = [];

    for (let b = 1; b <= boxTotal; b++) {
      const bData = it.boxes[b] || { count: 0, location: it.location || 'Brez lokacije', locations: [] };
      const count = Number(bData.count) || 0;
      counts.push(count);
      breakdown.push({
        boxNum: b,
        boxTotal: boxTotal,
        count: count,
        location: bData.location || 'Brez lokacije',
        locations: bData.locations || [{ name: bData.location || 'Brez lokacije', count: count, isPrimary: true }]
      });
    }

    const minCount = Math.min(...counts);
    const maxCount = Math.max(...counts);
    const completeSets = (boxTotal === 1) ? (Number(it.quantity) || 0) : minCount;

    if (boxTotal > 1) {
      breakdown.forEach(b => {
        if (b.count < maxCount) {
          missing.push(`Manjka ${maxCount - b.count}x Škatla ${b.boxNum}/${boxTotal}`);
        }
      });
    }

    const hasIncomplete = (boxTotal > 1) && (maxCount > 0) && (minCount < maxCount || missing.length > 0);

    return {
      boxTotal,
      boxes: it.boxes,
      breakdown,
      completeSets,
      minCount,
      maxCount,
      isComplete: completeSets > 0 && !hasIncomplete,
      hasIncomplete,
      missingSummary: missing.join(', ')
    };
  },

  /**
   * Poišče vse artikle, katerih škatle ali zaloga so razpršene po različnih regalih
   */
  async getScatteredItems() {
    const allItems = await this.getAllItems();
    const scattered = [];

    for (const item of allItems) {
      const stats = this.getBoxStats(item);
      const locationsList = [];
      const distinctShelves = new Set();

      for (const b of stats.breakdown) {
        if (Array.isArray(b.locations) && b.locations.length > 0) {
          for (const loc of b.locations) {
            const locCount = Number(loc.count) || 0;
            const locName = (loc.name || '').trim();
            if (locCount > 0 && locName && locName !== 'Brez lokacije') {
              distinctShelves.add(locName);
              locationsList.push({
                boxNum: b.boxNum,
                boxTotal: stats.boxTotal,
                shelf: locName,
                count: locCount,
                isPrimary: !!loc.isPrimary
              });
            }
          }
        } else if (b.count > 0 && b.location && b.location !== 'Brez lokacije') {
          const locName = b.location.trim();
          distinctShelves.add(locName);
          locationsList.push({
            boxNum: b.boxNum,
            boxTotal: stats.boxTotal,
            shelf: locName,
            count: b.count,
            isPrimary: true
          });
        }
      }

      // Razpršeno, če so škatle tega artikla na 2 ali več različnih policah
      if (distinctShelves.size > 1) {
        const shelvesArr = Array.from(distinctShelves);
        // Ciljni regal je primarni regal ali regal z največ kosi
        const primaryLoc = locationsList.find(l => l.isPrimary);
        const targetShelf = primaryLoc ? primaryLoc.shelf : shelvesArr[0];
        const boxesToMove = locationsList.filter(l => l.shelf !== targetShelf);

        scattered.push({
          sku: item.sku,
          name: item.name,
          category: item.category,
          boxTotal: stats.boxTotal,
          completeSets: stats.completeSets,
          locations: locationsList,
          distinctShelves: shelvesArr,
          targetShelf: targetShelf,
          boxesToMove: boxesToMove
        });
      }
    }

    return scattered;
  },

  /**
   * Vrne vse artikle s popolnimi podatki o zalogah po regalih
   */
  async getAllItems() {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations'], 'readonly');
      const articleStore = tx.objectStore('articles');
      const stockStore = tx.objectStore('stock_locations');

      const articlesReq = articleStore.getAll();
      const stockReq = stockStore.getAll();

      let articles = [];
      let stocks = [];
      let ready = 0;

      const finish = () => {
        ready++;
        if (ready < 2) return;

        const stockMap = new Map();
        for (const s of stocks) {
          const sku = String(s.sku || '').trim();
          if (!stockMap.has(sku)) stockMap.set(sku, []);
          stockMap.get(sku).push(s);
        }

        const items = articles.map(art => {
          const sku = String(art.sku || '').trim();
          const artStocks = stockMap.get(sku) || [];
          return DB.buildItemFromArticleAndStocks(art, artStocks);
        });

        resolve(items);
      };

      articlesReq.onsuccess = () => { articles = articlesReq.result || []; finish(); };
      articlesReq.onerror = () => reject(articlesReq.error);

      stockReq.onsuccess = () => { stocks = stockReq.result || []; finish(); };
      stockReq.onerror = () => reject(stockReq.error);
    });
  },

  async getItemById(id) {
    if (!id) return null;
    return this.getItemBySKU(String(id).trim());
  },

  async getItemBySKU(sku) {
    if (!sku) return null;
    const cleanSku = String(sku).trim();
    const db = await openDB();

    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations'], 'readonly');
      const articleStore = tx.objectStore('articles');
      const stockStore = tx.objectStore('stock_locations');

      const artReq = articleStore.get(cleanSku);
      const stockIndex = stockStore.index('sku');
      const stockReq = stockIndex.getAll(cleanSku);

      let article = null;
      let stocks = [];
      let ready = 0;

      const finish = () => {
        ready++;
        if (ready < 2) return;
        if (!article) {
          resolve(null);
          return;
        }
        resolve(DB.buildItemFromArticleAndStocks(article, stocks));
      };

      artReq.onsuccess = () => { article = artReq.result || null; finish(); };
      artReq.onerror = () => reject(artReq.error);

      stockReq.onsuccess = () => { stocks = stockReq.result || []; finish(); };
      stockReq.onerror = () => reject(stockReq.error);
    });
  },

  /**
   * Dodajanje ali združevanje artikla (SKU je enolični ključ)
   */
  async addItem(itemData) {
    const cleanSku = String(itemData.sku || '').trim();
    if (!cleanSku) throw new Error('Šifra artikla (SKU) je obvezna.');

    const db = await openDB();
    const boxTotal = Math.max(1, parseInt(itemData.boxTotal, 10) || 1);

    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations', 'logs'], 'readwrite');
      const artStore = tx.objectStore('articles');
      const stockStore = tx.objectStore('stock_locations');
      const logStore = tx.objectStore('logs');

      const checkReq = artStore.get(cleanSku);
      checkReq.onsuccess = () => {
        const existing = checkReq.result;
        const wasMerged = !!existing;

        const artRecord = {
          sku: cleanSku,
          name: String(itemData.name || '').trim() || (existing ? existing.name : `Artikel ${cleanSku}`),
          category: String(itemData.category || 'Splošno').trim(),
          boxTotal: Math.max(boxTotal, existing ? existing.boxTotal : 1),
          minQuantity: Number(itemData.minQuantity) || (existing ? existing.minQuantity : 0),
          shelfCapacity: Number(itemData.shelfCapacity) || (existing ? existing.shelfCapacity : 0),
          shelfLocation: String(itemData.shelfLocation || (existing ? existing.shelfLocation : '')).trim(),
          categoryType: String(itemData.categoryType || (existing ? existing.categoryType : '')).trim(),
          unit: String(itemData.unit || (boxTotal > 1 ? 'garnitura' : 'kos')).trim(),
          notes: String(itemData.notes || '').trim() || (existing ? existing.notes : ''),
          lastUpdated: new Date().toISOString()
        };

        artStore.put(artRecord);

        if (itemData.boxes && typeof itemData.boxes === 'object') {
          for (let b = 1; b <= artRecord.boxTotal; b++) {
            const bData = itemData.boxes[b];
            if (!bData) continue;
            const locs = (Array.isArray(bData.locations) && bData.locations.length > 0)
              ? bData.locations
              : [{ name: bData.location || itemData.location || 'Brez lokacije', count: bData.count || 0, isPrimary: true }];

            for (const loc of locs) {
              const shelfName = String(loc.name || 'Brez lokacije').trim();
              const wh = loc.warehouse || (shelfName.startsWith('TRG') ? 'trgovina' : 'zgornje');
              const boxId = loc.boxId || null;
              const locId = `${cleanSku}_b${b}_${shelfName}${boxId ? `_box_${boxId}` : ''}`;

              const getLocReq = stockStore.get(locId);
              getLocReq.onsuccess = () => {
                const prev = getLocReq.result;
                const newCount = prev ? (prev.count + (Number(loc.count) || 0)) : (Number(loc.count) || 0);
                stockStore.put({
                  id: locId,
                  sku: cleanSku,
                  boxNum: b,
                  shelf: shelfName,
                  warehouse: wh,
                  boxId: boxId,
                  count: Math.max(0, newCount),
                  isPrimary: !!loc.isPrimary,
                  lastUpdated: new Date().toISOString()
                });
              };
            }
          }
        } else {
          const shelfName = String(itemData.location || 'Brez lokacije').trim();
          const wh = itemData.warehouse || (shelfName.startsWith('TRG') ? 'trgovina' : 'zgornje');
          const boxId = itemData.boxId || null;
          const locId = `${cleanSku}_b1_${shelfName}${boxId ? `_box_${boxId}` : ''}`;
          const count = Number(itemData.quantity) || 0;
          stockStore.put({
            id: locId,
            sku: cleanSku,
            boxNum: 1,
            shelf: shelfName,
            warehouse: wh,
            boxId: boxId,
            count: count,
            isPrimary: true,
            lastUpdated: new Date().toISOString()
          });
        }

        logStore.add({
          sku: cleanSku,
          itemName: artRecord.name,
          changeType: wasMerged ? 'in' : 'create',
          changeAmount: Number(itemData.quantity) || 0,
          resultingQty: Number(itemData.quantity) || 0,
          note: wasMerged ? 'Združitev zaloge ob ponovnem vnosu' : `Začetni vnos (${artRecord.boxTotal} škatel)`,
          timestamp: new Date().toISOString()
        });

        tx.oncomplete = () => {
          DB.bumpMutationVersion();
          resolve({ id: cleanSku, sku: cleanSku, wasMerged });
        };
        tx.onerror = () => reject(tx.error);
      };
      checkReq.onerror = () => reject(checkReq.error);
    });
  },

  /**
   * Posodobitev obstoječega artikla
   */
  async updateItem(itemData) {
    const cleanSku = String(itemData.sku || '').trim();
    if (!cleanSku) throw new Error('Šifra artikla (SKU) je obvezna.');

    const db = await openDB();
    const boxTotal = Math.max(1, parseInt(itemData.boxTotal, 10) || 1);

    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations'], 'readwrite');
      const artStore = tx.objectStore('articles');
      const stockStore = tx.objectStore('stock_locations');

      const artRecord = {
        sku: cleanSku,
        name: String(itemData.name || '').trim(),
        category: String(itemData.category || 'Splošno').trim(),
        boxTotal: boxTotal,
        minQuantity: Number(itemData.minQuantity) || 0,
        shelfCapacity: Number(itemData.shelfCapacity) || 0,
        shelfLocation: String(itemData.shelfLocation || '').trim(),
        categoryType: String(itemData.categoryType || '').trim(),
        unit: String(itemData.unit || (boxTotal > 1 ? 'garnitura' : 'kos')).trim(),
        notes: String(itemData.notes || '').trim(),
        lastUpdated: new Date().toISOString()
      };
      artStore.put(artRecord);

      const stockIndex = stockStore.index('sku');
      const getExistingStocks = stockIndex.getAll(cleanSku);

      getExistingStocks.onsuccess = () => {
        const existingStocks = getExistingStocks.result || [];
        existingStocks.forEach(s => stockStore.delete(s.id));

        if (itemData.boxes && typeof itemData.boxes === 'object') {
          for (let b = 1; b <= boxTotal; b++) {
            const bData = itemData.boxes[b];
            if (!bData) continue;
            const locs = (Array.isArray(bData.locations) && bData.locations.length > 0)
              ? bData.locations
              : [{ name: bData.location || itemData.location || 'Brez lokacije', count: bData.count || 0, isPrimary: true }];

            locs.forEach((loc, idx) => {
              const shelfName = String(loc.name || 'Brez lokacije').trim();
              const wh = loc.warehouse || (shelfName.startsWith('TRG') ? 'trgovina' : 'zgornje');
              const boxId = loc.boxId || null;
              const locId = `${cleanSku}_b${b}_${shelfName}${boxId ? `_box_${boxId}` : ''}`;

              stockStore.put({
                id: locId,
                sku: cleanSku,
                boxNum: b,
                shelf: shelfName,
                warehouse: wh,
                boxId: boxId,
                count: Math.max(0, Number(loc.count) || 0),
                isPrimary: loc.isPrimary !== undefined ? !!loc.isPrimary : (idx === 0),
                lastUpdated: new Date().toISOString()
              });
            });
          }
        } else {
          const shelfName = String(itemData.location || 'Brez lokacije').trim();
          const wh = itemData.warehouse || (shelfName.startsWith('TRG') ? 'trgovina' : 'zgornje');
          const boxId = itemData.boxId || null;
          const locId = `${cleanSku}_b1_${shelfName}${boxId ? `_box_${boxId}` : ''}`;
          stockStore.put({
            id: locId,
            sku: cleanSku,
            boxNum: 1,
            shelf: shelfName,
            warehouse: wh,
            boxId: boxId,
            count: Number(itemData.quantity) || 0,
            isPrimary: true,
            lastUpdated: new Date().toISOString()
          });
        }
      };

      tx.oncomplete = () => {
        DB.bumpMutationVersion();
        resolve(cleanSku);
      };
      tx.onerror = () => reject(tx.error);
    });
  },

  async deleteItem(sku) {
    const cleanSku = String(sku).trim();
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations', 'logs'], 'readwrite');
      const artStore = tx.objectStore('articles');
      const stockStore = tx.objectStore('stock_locations');
      const logStore = tx.objectStore('logs');

      const artReq = artStore.get(cleanSku);
      artReq.onsuccess = () => {
        const art = artReq.result;
        if (art) {
          logStore.add({
            sku: cleanSku,
            itemName: art.name,
            changeType: 'delete',
            changeAmount: 0,
            resultingQty: 0,
            note: 'Izbris artikla iz zaloge',
            timestamp: new Date().toISOString()
          });
          artStore.delete(cleanSku);
        }

        const stockIndex = stockStore.index('sku');
        const stocksReq = stockIndex.getAll(cleanSku);
        stocksReq.onsuccess = () => {
          (stocksReq.result || []).forEach(s => stockStore.delete(s.id));
        };
      };

      tx.oncomplete = () => {
        DB.bumpMutationVersion();
        resolve(true);
      };
      tx.onerror = () => reject(tx.error);
    });
  },

  /**
   * Sprememba zaloge posamezne škatle na regalu
   */
  async adjustStock(sku, delta, note = '', type = 'adjust', boxNum = null, shelf = null) {
    const cleanSku = String(sku).trim();
    const db = await openDB();
    const bNum = boxNum ? Math.max(1, parseInt(boxNum, 10)) : 1;

    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations', 'logs'], 'readwrite');
      const artStore = tx.objectStore('articles');
      const stockStore = tx.objectStore('stock_locations');
      const logStore = tx.objectStore('logs');

      const artReq = artStore.get(cleanSku);
      artReq.onsuccess = () => {
        const art = artReq.result;
        if (!art) {
          reject(new Error(`Artikel s šifro ${cleanSku} ne obstaja.`));
          return;
        }

        const stockIndex = stockStore.index('sku');
        const stocksReq = stockIndex.getAll(cleanSku);

        stocksReq.onsuccess = () => {
          const stocks = stocksReq.result || [];
          const boxStocks = stocks.filter(s => s.boxNum === bNum);

          let targetStock = null;
          if (shelf) {
            const cleanShelf = String(shelf).trim();
            targetStock = boxStocks.find(s => s.shelf.toLowerCase() === cleanShelf.toLowerCase());
            if (!targetStock) {
              targetStock = {
                id: `${cleanSku}_b${bNum}_${cleanShelf}`,
                sku: cleanSku,
                boxNum: bNum,
                shelf: cleanShelf,
                count: 0,
                isPrimary: boxStocks.length === 0,
                lastUpdated: new Date().toISOString()
              };
              stocks.push(targetStock);
            }
          } else {
            targetStock = boxStocks.find(s => s.isPrimary) || boxStocks[0];
            if (!targetStock) {
              targetStock = {
                id: `${cleanSku}_b${bNum}_Regal Glavni`,
                sku: cleanSku,
                boxNum: bNum,
                shelf: 'Regal Glavni',
                count: 0,
                isPrimary: true,
                lastUpdated: new Date().toISOString()
              };
              stocks.push(targetStock);
            }
          }

          if (delta >= 0) {
            targetStock.count += Number(delta);
          } else {
            let toRemove = Math.abs(Number(delta));
            if (targetStock.count >= toRemove) {
              targetStock.count -= toRemove;
            } else {
              toRemove -= targetStock.count;
              targetStock.count = 0;
              for (const other of boxStocks) {
                if (other !== targetStock && other.count > 0 && toRemove > 0) {
                  const take = Math.min(other.count, toRemove);
                  other.count -= take;
                  toRemove -= take;
                  stockStore.put(other);
                }
              }
            }
          }

          targetStock.lastUpdated = new Date().toISOString();
          stockStore.put(targetStock);

          const updatedItem = DB.buildItemFromArticleAndStocks(art, stocks);

          logStore.add({
            sku: cleanSku,
            itemName: art.name,
            boxNum: bNum,
            shelf: targetStock.shelf,
            changeType: type,
            changeAmount: Number(delta),
            resultingQty: updatedItem.quantity,
            note: (note || (delta >= 0 ? 'Prevzem škatle' : 'Izdaja škatle')) + (art.boxTotal > 1 ? ` (Šk. ${bNum}/${art.boxTotal} @ ${targetStock.shelf})` : ` (@ ${targetStock.shelf})`),
            timestamp: new Date().toISOString()
          });

          tx.oncomplete = () => {
            DB.bumpMutationVersion();
            resolve(updatedItem);
          };
        };
      };
      artReq.onerror = () => reject(artReq.error);
    });
  },

  /**
   * Izdaja celotne garniture (odšteje škatle iz vseh potrebnih regalov)
   */
  async issueCompleteItem(sku, qty = 1, note = '') {
    const cleanSku = String(sku).trim();
    const issueQty = Math.max(1, Number(qty) || 1);
    const db = await openDB();

    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations', 'logs'], 'readwrite');
      const artStore = tx.objectStore('articles');
      const stockStore = tx.objectStore('stock_locations');
      const logStore = tx.objectStore('logs');

      const artReq = artStore.get(cleanSku);
      artReq.onsuccess = () => {
        const art = artReq.result;
        if (!art) {
          reject(new Error(`Artikel s šifro ${cleanSku} ne obstaja.`));
          return;
        }

        const boxTotal = Math.max(1, art.boxTotal || 1);
        const stockIndex = stockStore.index('sku');
        const stocksReq = stockIndex.getAll(cleanSku);

        stocksReq.onsuccess = () => {
          const stocks = stocksReq.result || [];
          const pickDetails = [];

          for (let b = 1; b <= boxTotal; b++) {
            let remaining = issueQty;
            const boxStocks = stocks.filter(s => s.boxNum === b);
            for (const s of boxStocks) {
              if (remaining <= 0) break;
              if (s.count > 0) {
                const take = Math.min(s.count, remaining);
                s.count -= take;
                remaining -= take;
                s.lastUpdated = new Date().toISOString();
                stockStore.put(s);
                pickDetails.push(`Šk.${b}: ${take}x iz ${s.shelf}`);
              }
            }
          }

          const updatedItem = DB.buildItemFromArticleAndStocks(art, stocks);

          logStore.add({
            sku: cleanSku,
            itemName: art.name,
            changeType: 'out',
            changeAmount: -issueQty,
            resultingQty: updatedItem.quantity,
            note: (note || 'Izdaja po računu') + ` (-${issueQty}x garnitura [${pickDetails.join(', ')}], preostalo: ${updatedItem.quantity})`,
            timestamp: new Date().toISOString()
          });

          tx.oncomplete = () => {
            DB.bumpMutationVersion();
            resolve(updatedItem);
          };
        };
      };
      artReq.onerror = () => reject(artReq.error);
    });
  },

  /**
   * Premestitev zaloge posamezne škatle med dvema regaloma (brez spremembe celotne zaloge)
   */
  async moveStock(sku, boxNum, fromShelf, toShelf, amount, note = '') {
    const cleanSku = String(sku).trim();
    const bNum = Math.max(1, parseInt(boxNum, 10) || 1);
    const moveQty = Math.max(1, parseInt(amount, 10) || 1);
    const fromS = String(fromShelf).trim();
    const toS = String(toShelf).trim();

    if (!fromS || !toS) throw new Error('Izvorna in ciljna lokacija sta obvezni.');
    if (fromS.toLowerCase() === toS.toLowerCase()) throw new Error('Izvorna in ciljna lokacija ne moreta biti enaki.');

    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations', 'logs'], 'readwrite');
      const artStore = tx.objectStore('articles');
      const stockStore = tx.objectStore('stock_locations');
      const logStore = tx.objectStore('logs');

      const artReq = artStore.get(cleanSku);
      artReq.onsuccess = () => {
        const art = artReq.result;
        if (!art) {
          reject(new Error(`Artikel s šifro ${cleanSku} ne obstaja.`));
          return;
        }

        const fromId = `${cleanSku}_b${bNum}_${fromS}`;
        const toId = `${cleanSku}_b${bNum}_${toS}`;

        const fromReq = stockStore.get(fromId);
        fromReq.onsuccess = () => {
          const fromLoc = fromReq.result;
          if (!fromLoc || fromLoc.count < moveQty) {
            reject(new Error(`Na lokaciji "${fromS}" ni dovolj zaloge (na voljo: ${fromLoc ? fromLoc.count : 0}, premakniti želite: ${moveQty}).`));
            return;
          }

          fromLoc.count -= moveQty;
          fromLoc.lastUpdated = new Date().toISOString();
          stockStore.put(fromLoc);

          const toReq = stockStore.get(toId);
          toReq.onsuccess = () => {
            const toLoc = toReq.result || {
              id: toId,
              sku: cleanSku,
              boxNum: bNum,
              shelf: toS,
              count: 0,
              isPrimary: false,
              lastUpdated: new Date().toISOString()
            };

            toLoc.count += moveQty;
            toLoc.lastUpdated = new Date().toISOString();
            stockStore.put(toLoc);

            logStore.add({
              sku: cleanSku,
              itemName: art.name,
              boxNum: bNum,
              shelf: `${fromS} ➔ ${toS}`,
              changeType: 'adjust',
              changeAmount: 0,
              resultingQty: 0,
              note: (note || 'Premestitev med regali') + ` (${moveQty}x Šk.${bNum} iz ${fromS} na ${toS})`,
              timestamp: new Date().toISOString()
            });
          };
        };
      };

      tx.oncomplete = () => {
        DB.bumpMutationVersion();
        resolve(true);
      };
      tx.onerror = () => reject(tx.error);
    });
  },

  async getAllLogs() {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('logs', 'readonly');
      const store = tx.objectStore('logs');
      const req = store.getAll();
      req.onsuccess = () => {
        const logs = req.result || [];
        logs.sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp));
        resolve(logs);
      };
      req.onerror = () => reject(req.error);
    });
  },

  async clearLogs() {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('logs', 'readwrite');
      const store = tx.objectStore('logs');
      const req = store.clear();
      req.onsuccess = () => resolve(true);
      req.onerror = () => reject(req.error);
    });
  },

  /**
   * Izbriše vse artikle in zaloge po regalih iz skladišča
   */
  async clearAllItems() {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations', 'logs'], 'readwrite');
      const artStore = tx.objectStore('articles');
      const stockStore = tx.objectStore('stock_locations');
      const logStore = tx.objectStore('logs');

      artStore.clear();
      stockStore.clear();

      logStore.add({
        sku: 'SYS-CLEAR',
        itemName: 'Baza artiklov izpraznjena',
        changeType: 'delete',
        changeAmount: 0,
        resultingQty: 0,
        note: 'Izbris vseh artiklov in lokacij iz skladišča',
        timestamp: new Date().toISOString()
      });

      tx.oncomplete = () => resolve(true);
      tx.onerror = () => reject(tx.error);
    });
  },

  /**
   * Uveljavi rezultate inventure v stock_locations
   */
  async applyAuditResults(entries) {
    if (!Array.isArray(entries) || entries.length === 0) return { totalAdjustments: 0 };
    const db = await openDB();

    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations', 'logs'], 'readwrite');
      const stockStore = tx.objectStore('stock_locations');
      const logStore = tx.objectStore('logs');

      let adjustments = 0;

      for (const entry of entries) {
        if (!entry.isCounted) continue;
        const counted = Math.max(0, parseInt(entry.countedQty, 10) || 0);
        const book = Math.max(0, parseInt(entry.bookQty, 10) || 0);
        const diff = counted - book;
        if (diff === 0) continue;

        const locId = `${entry.sku}_b${entry.boxNum}_${entry.locName}`;
        stockStore.put({
          id: locId,
          sku: entry.sku,
          boxNum: entry.boxNum,
          shelf: entry.locName,
          count: counted,
          isPrimary: true,
          lastUpdated: new Date().toISOString()
        });

        logStore.add({
          sku: entry.sku,
          itemName: entry.name,
          boxNum: entry.boxNum,
          shelf: entry.locName,
          changeType: 'adjust',
          changeAmount: diff,
          resultingQty: counted,
          note: `Inventurna izravnava (knjižno: ${book}, prešteto: ${counted}, razlika: ${diff > 0 ? '+' : ''}${diff})`,
          timestamp: new Date().toISOString()
        });

        adjustments++;
      }

      tx.oncomplete = () => {
        DB.bumpMutationVersion();
        resolve({ totalAdjustments: adjustments });
      };
      tx.onerror = () => reject(tx.error);
    });
  },

  /**
   * Deduplikacija artiklov (v normalizirani bazi je SKU primarni ključ)
   */
  async deduplicateItems() {
    return {
      mergedGroupsCount: 0,
      removedDuplicatesCount: 0,
      affectedSkus: []
    };
  },

  async seedDemoData() {
    const demoArticles = [
      {
        sku: '3600123',
        name: 'Vrtni stol MELLBY jeklo/tekstilen črna',
        category: 'Vrtno pohištvo',
        boxTotal: 1,
        minQuantity: 4,
        unit: 'kos',
        shelf: 'Regal A-01',
        count: 12,
        notes: 'Enodelni stol, zložljiv'
      },
      {
        sku: '3600456',
        name: 'Vrtna miza SANDVIG Š82xD140 siva',
        category: 'Vrtno pohištvo',
        boxTotal: 1,
        minQuantity: 2,
        unit: 'kos',
        shelf: 'Regal A-03',
        count: 1,
        notes: 'Zadnji kos na zalogi'
      },
      {
        sku: '3651003',
        name: 'Garderobna omara TARP 3 vrata bela',
        category: 'Pohištvo',
        boxTotal: 3,
        minQuantity: 1,
        unit: 'garnitura',
        notes: 'Velika omara 150x200 cm, 3 škatle',
        boxes: [
          { boxNum: 1, shelf: 'Regal G-01 (ogrodje)', count: 3 },
          { boxNum: 2, shelf: 'Regal G-02 (police)', count: 3 },
          { boxNum: 3, shelf: 'Regal G-03 (vrata z ogledalom)', count: 3 }
        ]
      },
      {
        sku: '3698710',
        name: 'Posteljni okvir ABILD 160x200 hrast',
        category: 'Vzmetnice & Nadvložki',
        boxTotal: 2,
        minQuantity: 2,
        unit: 'garnitura',
        notes: '⚠️ 2 cela kompleta, 3x Box 1/2 je viška (manjka 3x Box 2/2)',
        boxes: [
          { boxNum: 1, shelf: 'Regal P-01 (vzglavje)', count: 5 },
          { boxNum: 2, shelf: 'Regal P-02 (okvir & stranice)', count: 2 }
        ]
      },
      {
        sku: '3618930',
        name: 'Predalnik LIMFJORDEN 4 predali bela',
        category: 'Pohištvo',
        boxTotal: 2,
        minQuantity: 2,
        unit: 'garnitura',
        notes: 'JYSK Predalnik LIMFJORDEN (Škatla 1/2 razdeljena na P-04 in P-09 rezervo)',
        boxes: [
          { boxNum: 1, shelf: 'Regal P-04 (ogrodje)', count: 4 },
          { boxNum: 1, shelf: 'Regal P-09 (rezerva)', count: 2 },
          { boxNum: 2, shelf: 'Regal P-05 (predali)', count: 6 }
        ]
      },
      {
        sku: '3670619',
        name: 'DIVERSES',
        category: 'Splošno',
        boxTotal: 1,
        minQuantity: 2,
        unit: 'kos',
        shelf: 'Regal D-01',
        count: 8,
        notes: 'Pomožni artikel za račune'
      }
    ];

    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations', 'logs'], 'readwrite');
      const artStore = tx.objectStore('articles');
      const stockStore = tx.objectStore('stock_locations');
      const logStore = tx.objectStore('logs');

      demoArticles.forEach(item => {
        artStore.put({
          sku: item.sku,
          name: item.name,
          category: item.category,
          boxTotal: item.boxTotal,
          minQuantity: item.minQuantity,
          unit: item.unit,
          notes: item.notes,
          lastUpdated: new Date().toISOString()
        });

        if (item.boxes) {
          item.boxes.forEach((b, idx) => {
            stockStore.put({
              id: `${item.sku}_b${b.boxNum}_${b.shelf}`,
              sku: item.sku,
              boxNum: b.boxNum,
              shelf: b.shelf,
              count: b.count,
              isPrimary: idx === 0,
              lastUpdated: new Date().toISOString()
            });
          });
        } else {
          stockStore.put({
            id: `${item.sku}_b1_${item.shelf}`,
            sku: item.sku,
            boxNum: 1,
            shelf: item.shelf,
            count: item.count,
            isPrimary: true,
            lastUpdated: new Date().toISOString()
          });
        }

        logStore.add({
          sku: item.sku,
          itemName: item.name,
          changeType: 'create',
          changeAmount: item.count || 0,
          resultingQty: item.count || 0,
          note: `Začetni vzorčni vnos`,
          timestamp: new Date().toISOString()
        });
      });

      tx.oncomplete = () => resolve(true);
      tx.onerror = () => reject(tx.error);
    });
  },

  async ensureReceiptDemoItems() {
    const requiredSKUs = ['3618930', '3670619'];
    for (const sku of requiredSKUs) {
      const item = await this.getItemBySKU(sku);
      if (!item) {
        await this.seedDemoData();
        break;
      }
    }
  },

  /**
   * Izvoz celotne baze za varnostno kopijo ali BLE sinhronizacijo
   */
  async exportBackup() {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations', 'logs'], 'readonly');
      const artReq = tx.objectStore('articles').getAll();
      const stockReq = tx.objectStore('stock_locations').getAll();
      const logReq = tx.objectStore('logs').getAll();

      let articles = [], stockLocations = [], logs = [];
      let ready = 0;

      const finish = async () => {
        ready++;
        if (ready < 3) return;
        const items = await DB.getAllItems();
        resolve({
          version: 2,
          createdAt: new Date().toISOString(),
          articles,
          stockLocations,
          items, // Za nazaj združljivo branje
          logs
        });
      };

      artReq.onsuccess = () => { articles = artReq.result || []; finish(); };
      artReq.onerror = () => reject(artReq.error);

      stockReq.onsuccess = () => { stockLocations = stockReq.result || []; finish(); };
      stockReq.onerror = () => reject(stockReq.error);

      logReq.onsuccess = () => { logs = logReq.result || []; finish(); };
      logReq.onerror = () => reject(logReq.error);
    });
  },

  async importBackup(data) {
    if (!data) throw new Error('Neveljavna datoteka za uvoz.');
    return this.mergeSyncData(data);
  },

  getDeviceId() {
    let id = localStorage.getItem('skladisce_device_id');
    if (!id || id.length !== 4) {
      const chars = '0123456789abcdef';
      id = '';
      for (let i = 0; i < 4; i++) {
        id += chars[Math.floor(Math.random() * chars.length)];
      }
      localStorage.setItem('skladisce_device_id', id);
    }
    return id;
  },

  getDbVersion() {
    return parseInt(localStorage.getItem('skladisce_db_version') || '1', 10);
  },

  bumpMutationVersion() {
    const cur = this.getDbVersion();
    const next = cur + 1;
    localStorage.setItem('skladisce_db_version', String(next));
    localStorage.setItem('skladisce_last_log_id', `${this.getDeviceId()}_${Date.now()}_${Math.floor(Math.random() * 10000)}`);
    if (typeof window !== 'undefined' && window.onLocalDatabaseMutated) {
      window.onLocalDatabaseMutated();
    }
    return next;
  },

  getSyncHash() {
    const devId = this.getDeviceId();
    const v = this.getDbVersion();
    const lastLog = localStorage.getItem('skladisce_last_log_id') || String(Date.now());
    const str = `${devId}_v${v}_${lastLog}`;
    let hash = 0x811c9dc5;
    for (let i = 0; i < str.length; i++) {
      hash ^= str.charCodeAt(i);
      hash = Math.imul(hash, 0x01000193);
    }
    return (hash >>> 0);
  },

  async getSyncPayload() {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations', 'mixed_boxes', 'replenishment_list', 'logs'], 'readonly');
      const artReq = tx.objectStore('articles').getAll();
      const stockReq = tx.objectStore('stock_locations').getAll();
      const mixedReq = tx.objectStore('mixed_boxes').getAll();
      const repReq = tx.objectStore('replenishment_list').getAll();
      const logReq = tx.objectStore('logs').getAll();

      let articles = [], stockLocations = [], mixedBoxes = [], replenishmentList = [], logs = [];
      let ready = 0;
      const finish = () => {
        ready++;
        if (ready < 5) return;
        resolve({
          version: 2,
          deviceId: this.getDeviceId(),
          dbVersion: this.getDbVersion(),
          stateHash: this.getSyncHash(),
          exportedAt: new Date().toISOString(),
          articles,
          stockLocations,
          mixedBoxes,
          replenishmentList,
          logs: logs.slice(-300)
        });
      };
      artReq.onsuccess = () => { articles = artReq.result || []; finish(); };
      stockReq.onsuccess = () => { stockLocations = stockReq.result || []; finish(); };
      mixedReq.onsuccess = () => { mixedBoxes = mixedReq.result || []; finish(); };
      repReq.onsuccess = () => { replenishmentList = repReq.result || []; finish(); };
      logReq.onsuccess = () => { logs = logReq.result || []; finish(); };
      tx.onerror = () => reject(tx.error);
    });
  },

  /**
   * Sinhronizacija podatkov med telefoni (BLE mesh ali datoteka)
   */
  async mergeSyncData(syncPayload) {
    if (!syncPayload) throw new Error('Neveljavna datoteka za sinhronizacijo.');

    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations', 'mixed_boxes', 'replenishment_list', 'logs'], 'readwrite');
      const artStore = tx.objectStore('articles');
      const stockStore = tx.objectStore('stock_locations');
      const mixedStore = tx.objectStore('mixed_boxes');
      const repStore = tx.objectStore('replenishment_list');
      const logStore = tx.objectStore('logs');

      let addedCount = 0;
      let updatedCount = 0;
      let addedLogsCount = 0;
      let mixedBoxesCount = 0;
      let repCount = 0;
      const addedItemNames = [];

      const incomingArticles = syncPayload.articles || [];
      const incomingStocks = syncPayload.stockLocations || [];
      const incomingItems = syncPayload.items || [];
      const incomingMixedBoxes = syncPayload.mixedBoxes || [];
      const incomingRepList = syncPayload.replenishmentList || [];

      // Če ima payload stari format items:
      if (incomingItems.length > 0 && incomingArticles.length === 0) {
        for (const item of incomingItems) {
          const sku = String(item.sku || '').trim();
          if (!sku) continue;
          const boxTotal = Math.max(1, parseInt(item.boxTotal, 10) || 1);
          incomingArticles.push({
            sku: sku,
            name: item.name || `Artikel ${sku}`,
            category: item.category || 'Splošno',
            boxTotal: boxTotal,
            minQuantity: Number(item.minQuantity) || 0,
            unit: item.unit || (boxTotal > 1 ? 'garnitura' : 'kos'),
            notes: item.notes || '',
            lastUpdated: item.lastUpdated || new Date().toISOString()
          });

          if (item.boxes) {
            for (let b = 1; b <= boxTotal; b++) {
              const bData = item.boxes[b];
              if (!bData) continue;
              const locs = bData.locations || [{ name: bData.location || item.location || 'Brez lokacije', count: bData.count || 0, isPrimary: true }];
              locs.forEach(l => {
                incomingStocks.push({
                  id: `${sku}_b${b}_${l.name}`,
                  sku: sku,
                  boxNum: b,
                  shelf: l.name,
                  count: Number(l.count) || 0,
                  isPrimary: !!l.isPrimary,
                  lastUpdated: item.lastUpdated || new Date().toISOString()
                });
              });
            }
          }
        }
      }

      // 1. Združevanje artiklov z Last-Write-Wins (LWW)
      for (const art of incomingArticles) {
        if (!art || !art.sku) continue;
        const getReq = artStore.get(art.sku);
        getReq.onsuccess = (e) => {
          const existing = e.target.result;
          if (!existing) {
            artStore.put(art);
            addedCount++;
            addedItemNames.push(art.name || art.sku);
          } else {
            const incTime = new Date(art.lastUpdated || 0).getTime();
            const exTime = new Date(existing.lastUpdated || 0).getTime();
            if (incTime >= exTime) {
              artStore.put(art);
              updatedCount++;
            }
          }
        };
      }

      // 2. Združevanje stanj na policah / regalu z Last-Write-Wins
      for (const st of incomingStocks) {
        if (!st || !st.id || !st.sku) continue;
        const getStReq = stockStore.get(st.id);
        getStReq.onsuccess = (e) => {
          const existing = e.target.result;
          if (!existing) {
            stockStore.put(st);
            updatedCount++;
          } else {
            const incTime = new Date(st.lastUpdated || 0).getTime();
            const exTime = new Date(existing.lastUpdated || 0).getTime();
            if (incTime >= exTime) {
              stockStore.put(st);
              updatedCount++;
            }
          }
        };
      }

      // 3. Združevanje mešanih škatel
      for (const mBox of incomingMixedBoxes) {
        if (!mBox || !mBox.id) continue;
        const getBoxReq = mixedStore.get(mBox.id);
        getBoxReq.onsuccess = (e) => {
          const existing = e.target.result;
          if (!existing) {
            mixedStore.put(mBox);
            mixedBoxesCount++;
          } else {
            const incTime = new Date(mBox.updatedAt || mBox.createdAt || 0).getTime();
            const exTime = new Date(existing.updatedAt || existing.createdAt || 0).getTime();
            if (incTime >= exTime) {
              mixedStore.put(mBox);
              mixedBoxesCount++;
            }
          }
        };
      }

      // 4. Združevanje nalog za dopolnitev polic
      for (const rep of incomingRepList) {
        if (!rep || !rep.id) continue;
        const getRepReq = repStore.get(rep.id);
        getRepReq.onsuccess = (e) => {
          const existing = e.target.result;
          if (!existing) {
            repStore.put(rep);
            repCount++;
          } else {
            if (rep.status === 'completed' && existing.status !== 'completed') {
              repStore.put(rep);
              repCount++;
            } else if (new Date(rep.createdAt || 0) >= new Date(existing.createdAt || 0)) {
              if (existing.status !== 'completed') {
                repStore.put(rep);
              }
            }
          }
        };
      }

      // 5. Združevanje dnevnika sprememb brez podvajanja in reševanje sočasnih skeniranj (CRDT Delta Replay)
      if (Array.isArray(syncPayload.logs)) {
        const getLogsReq = logStore.getAll();
        getLogsReq.onsuccess = () => {
          const existingLogs = getLogsReq.result || [];
          const existingKeys = new Set(existingLogs.map(l => `${l.timestamp}_${l.sku || ''}_${l.action || l.changeType || ''}`));
          
          for (const incLog of syncPayload.logs) {
            if (!incLog || !incLog.timestamp) continue;
            const key = `${incLog.timestamp}_${incLog.sku || ''}_${incLog.action || incLog.changeType || ''}`;
            if (!existingKeys.has(key)) {
              delete incLog.id;
              logStore.add(incLog);
              existingKeys.add(key);
              addedLogsCount++;

              // Če sta oba skladiščnika sočasno poskenirala spremembo zaloge na istem regalu,
              // prištej delta, da se vnos drugega skladiščnika ne izgubi ob uskladitvi!
              const delta = Number(incLog.delta ?? incLog.changeAmount) || 0;
              const cleanSku = String(incLog.sku || '').trim();
              const bNum = Math.max(1, parseInt(incLog.boxNum, 10) || 1);
              const shelf = incLog.shelf || (incLog.location && !incLog.location.includes('➔') ? incLog.location : null);

              if (delta !== 0 && cleanSku && shelf && incLog.changeType !== 'create') {
                const stockId = `${cleanSku}_b${bNum}_${shelf}`;
                stockStore.get(stockId).onsuccess = (sEv) => {
                  const curr = sEv.target.result;
                  if (curr) {
                    curr.count = Math.max(0, (Number(curr.count) || 0) + delta);
                    curr.lastUpdated = new Date().toISOString();
                    stockStore.put(curr);
                  }
                };
              }
            }
          }
        };
      }

      tx.oncomplete = () => {
        resolve({
          addedCount,
          updatedCount,
          decreasedCount: 0,
          addedLogsCount,
          mixedBoxesCount,
          repCount,
          addedItemNames,
          totalIncoming: (incomingArticles.length || incomingItems.length)
        });
      };
      tx.onerror = () => reject(tx.error);
    });
  },

  /* =========================================================================
   * SKLADIŠČA POSLOVALNICE (Prodajalna, Zgornje skladišče, Spodnje skladišče)
   * ========================================================================= */
  async getWarehouses() {
    const db = await openDB();
    return new Promise((resolve) => {
      const tx = db.transaction('warehouses', 'readonly');
      const req = tx.objectStore('warehouses').getAll();
      req.onsuccess = () => {
        let list = req.result || [];
        if (list.length === 0) {
          list = [
            { id: 'trgovina', name: 'Prodajalna (Police)', isStore: true, isDefault: true, color: '#38bdf8' },
            { id: 'zgornje', name: 'Zgornje skladišče (Soft)', isStore: false, isDefault: true, color: '#10b981' },
            { id: 'spodnje', name: 'Spodnje skladišče (Pohištvo)', isStore: false, isDefault: true, color: '#f59e0b' }
          ];
        }
        resolve(list);
      };
      req.onerror = () => resolve([
        { id: 'trgovina', name: 'Prodajalna (Police)', isStore: true, isDefault: true, color: '#38bdf8' },
        { id: 'zgornje', name: 'Zgornje skladišče (Soft)', isStore: false, isDefault: true, color: '#10b981' },
        { id: 'spodnje', name: 'Spodnje skladišče (Pohištvo)', isStore: false, isDefault: true, color: '#f59e0b' }
      ]);
    });
  },

  async saveWarehouse(wh) {
    if (!wh || !wh.name) throw new Error('Naziv skladišča je obvezen');
    const id = wh.id || 'wh_' + Date.now();
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('warehouses', 'readwrite');
      const store = tx.objectStore('warehouses');
      store.put({
        id,
        name: wh.name.trim(),
        isStore: !!wh.isStore,
        isDefault: !!wh.isDefault,
        color: wh.color || '#38bdf8'
      });
      tx.oncomplete = () => resolve(id);
      tx.onerror = () => reject(tx.error);
    });
  },

  async deleteWarehouse(id) {
    if (['trgovina', 'zgornje', 'spodnje'].includes(id)) {
      throw new Error('Privzetih skladišč ni mogoče izbrisati.');
    }
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('warehouses', 'readwrite');
      tx.objectStore('warehouses').delete(id);
      tx.oncomplete = () => resolve(true);
      tx.onerror = () => reject(tx.error);
    });
  },

  /* =========================================================================
   * MEŠANE ŠKATLE (Soft artikli, več različnih SKU-jev v 1 kartonu)
   * ========================================================================= */
  async getAllMixedBoxes() {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('mixed_boxes', 'readonly');
      const req = tx.objectStore('mixed_boxes').getAll();
      req.onsuccess = () => resolve(req.result || []);
      req.onerror = () => reject(req.error);
    });
  },

  async getMixedBoxById(boxId) {
    const cleanId = String(boxId || '').trim();
    if (!cleanId) return null;
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('mixed_boxes', 'readonly');
      const req = tx.objectStore('mixed_boxes').get(cleanId);
      req.onsuccess = () => resolve(req.result || null);
      req.onerror = () => reject(req.error);
    });
  },

  async saveMixedBox(boxData) {
    const boxId = String(boxData.boxId || '').trim();
    if (!boxId) throw new Error('Številka / koda mešane škatle je obvezna.');
    const db = await openDB();

    return new Promise((resolve, reject) => {
      const tx = db.transaction(['mixed_boxes', 'stock_locations', 'logs', 'articles'], 'readwrite');
      const mixedStore = tx.objectStore('mixed_boxes');
      const stockStore = tx.objectStore('stock_locations');
      const logStore = tx.objectStore('logs');
      const artStore = tx.objectStore('articles');

      const items = Array.isArray(boxData.items) ? boxData.items : [];
      const warehouse = boxData.warehouse || 'zgornje';
      const shelf = String(boxData.shelf || 'Brez lokacije').trim();

      // Najprej počisti prejšnje zapise te mešane škatle v stock_locations
      const stockIndex = stockStore.index('boxId');
      const prevReq = stockIndex.getAll(boxId);
      prevReq.onsuccess = () => {
        const prevStocks = prevReq.result || [];
        for (const ps of prevStocks) {
          stockStore.delete(ps.id);
        }

        // Vstavi nove postavke mešane škatle v stock_locations
        for (const it of items) {
          const sku = String(it.sku).trim();
          const qty = Math.max(1, Number(it.qty) || 1);
          const locId = `${sku}_b1_${shelf}_box_${boxId}`;
          stockStore.put({
            id: locId,
            sku: sku,
            boxNum: 1,
            shelf: shelf,
            warehouse: warehouse,
            boxId: boxId,
            count: qty,
            isPrimary: false,
            lastUpdated: new Date().toISOString()
          });

          // Preveri če artikel obstaja v articles, sicer ga ustvari
          artStore.get(sku).onsuccess = (e) => {
            if (!e.target.result) {
              artStore.put({
                sku: sku,
                name: it.name || `Artikel ${sku}`,
                category: 'Tekstil / Soft',
                boxTotal: 1,
                minQuantity: 0,
                unit: 'kos',
                notes: `Mešana škatla #${boxId}`,
                categoryType: 'soft',
                lastUpdated: new Date().toISOString()
              });
            }
          };
        }

        // Shrani vsebnik v mixed_boxes
        const boxRecord = {
          boxId: boxId,
          warehouse: warehouse,
          shelf: shelf,
          notes: String(boxData.notes || '').trim(),
          items: items.map(it => ({
            sku: String(it.sku).trim(),
            name: it.name || `Artikel ${it.sku}`,
            qty: Math.max(1, Number(it.qty) || 1)
          })),
          createdAt: boxData.createdAt || new Date().toISOString(),
          updatedAt: new Date().toISOString()
        };
        mixedStore.put(boxRecord);

        // Zabeleži v dnevnik
        const totalPcs = items.reduce((s, i) => s + (Number(i.qty) || 0), 0);
        logStore.add({
          action: 'pack_mixed_box',
          sku: `BOX-${boxId}`,
          itemName: `Mešana škatla #${boxId} (${items.length} artiklov, ${totalPcs} kos)`,
          boxNum: 1,
          location: `${warehouse === 'zgornje' ? 'Zgornje skladišče' : warehouse === 'spodnje' ? 'Spodnje skladišče' : warehouse} - ${shelf}`,
          delta: totalPcs,
          timestamp: new Date().toISOString()
        });
      };

      tx.oncomplete = () => resolve(boxId);
      tx.onerror = () => reject(tx.error);
    });
  },

  async deleteMixedBox(boxId) {
    const cleanId = String(boxId || '').trim();
    if (!cleanId) return;
    const db = await openDB();

    return new Promise((resolve, reject) => {
      const tx = db.transaction(['mixed_boxes', 'stock_locations', 'logs'], 'readwrite');
      const mixedStore = tx.objectStore('mixed_boxes');
      const stockStore = tx.objectStore('stock_locations');
      const logStore = tx.objectStore('logs');

      // Izbriši povezano zalogo iz stock_locations
      const stockIndex = stockStore.index('boxId');
      const req = stockIndex.getAll(cleanId);
      req.onsuccess = () => {
        const stocks = req.result || [];
        for (const s of stocks) {
          stockStore.delete(s.id);
        }
        mixedStore.delete(cleanId);

        logStore.add({
          action: 'delete_mixed_box',
          sku: `BOX-${cleanId}`,
          itemName: `Izbris mešane škatle #${cleanId}`,
          boxNum: 1,
          location: 'Izbrisano',
          delta: 0,
          timestamp: new Date().toISOString()
        });
      };

      tx.oncomplete = () => resolve(true);
      tx.onerror = () => reject(tx.error);
    });
  },

  /* =========================================================================
   * NABIRANJE PO KAPACITETI POLICE (Store Replenishment / Pick List)
   * ========================================================================= */
  async getReplenishmentList(filterStatus = 'all') {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('replenishment_list', 'readonly');
      const req = tx.objectStore('replenishment_list').getAll();
      req.onsuccess = () => {
        let list = req.result || [];
        if (filterStatus !== 'all') {
          list = list.filter(r => r.status === filterStatus);
        }
        list.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
        resolve(list);
      };
      req.onerror = () => reject(req.error);
    });
  },

  async addToReplenishment(repData) {
    const sku = String(repData.sku || '').trim();
    if (!sku) throw new Error('Šifra artikla (SKU) je obvezna.');
    const db = await openDB();

    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations', 'replenishment_list'], 'readwrite');
      const artStore = tx.objectStore('articles');
      const stockStore = tx.objectStore('stock_locations');
      const repStore = tx.objectStore('replenishment_list');

      artStore.get(sku).onsuccess = (e) => {
        const art = e.target.result;
        const name = repData.name || (art ? art.name : `Artikel ${sku}`);
        const neededQty = Math.max(1, parseInt(repData.neededQty, 10) || 1);
        const currentShelfQty = Math.max(0, parseInt(repData.currentShelfQty, 10) || 0);
        const targetShelf = repData.targetShelf || (art ? art.shelfLocation : '') || 'Prodajne police';

        // Preveri kje v skladišču je zaloga
        const stockIndex = stockStore.index('sku');
        stockIndex.getAll(sku).onsuccess = (sEvent) => {
          const stocks = sEvent.target.result || [];
          const whStocks = stocks.filter(s => s.warehouse !== 'trgovina' && Number(s.count) > 0);
          
          let sourceWarehouse = 'zgornje';
          let sourceLocation = 'Ni zaloge v skladišču';
          let boxId = null;

          if (whStocks.length > 0) {
            const bestStock = whStocks.find(s => s.warehouse === 'zgornje') || whStocks[0];
            sourceWarehouse = bestStock.warehouse || 'zgornje';
            sourceLocation = bestStock.shelf || 'Brez police';
            boxId = bestStock.boxId || null;
          }

          const repRecord = {
            sku: sku,
            name: name,
            neededQty: neededQty,
            currentShelfQty: currentShelfQty,
            targetShelf: targetShelf,
            sourceWarehouse: sourceWarehouse,
            sourceLocation: sourceLocation,
            boxId: boxId,
            status: 'pending',
            createdAt: new Date().toISOString()
          };

          const addReq = repStore.add(repRecord);
          addReq.onsuccess = () => {};
        };
      };

      tx.oncomplete = () => {
        this.bumpMutationVersion();
        resolve(true);
      };
      tx.onerror = () => reject(tx.error);
    });
  },

  async completeReplenishmentItem(repId, transferQty = null) {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(['replenishment_list', 'stock_locations', 'logs'], 'readwrite');
      const repStore = tx.objectStore('replenishment_list');
      const stockStore = tx.objectStore('stock_locations');
      const logStore = tx.objectStore('logs');

      repStore.get(repId).onsuccess = (e) => {
        const item = e.target.result;
        if (!item) {
          reject(new Error('Postavka ne obstaja'));
          return;
        }

        const qtyToTransfer = transferQty !== null ? Math.max(1, Number(transferQty)) : item.neededQty;

        // 1. Zmanjšaj zalogo v skladišču
        const stockIndex = stockStore.index('sku');
        stockIndex.getAll(item.sku).onsuccess = (sEvent) => {
          const stocks = sEvent.target.result || [];
          let remaining = qtyToTransfer;

          for (const st of stocks) {
            if (st.warehouse !== 'trgovina' && Number(st.count) > 0 && remaining > 0) {
              const deduct = Math.min(st.count, remaining);
              st.count -= deduct;
              remaining -= deduct;
              st.lastUpdated = new Date().toISOString();
              stockStore.put(st);
            }
          }

          // 2. Povečaj zalogo na polici trgovine
          const targetShelf = item.targetShelf || 'Prodajne police';
          const storeLocId = `${item.sku}_b1_${targetShelf}_trgovina`;
          stockStore.get(storeLocId).onsuccess = (stEvent) => {
            const storeLoc = stEvent.target.result;
            const newCount = (storeLoc ? Number(storeLoc.count) : 0) + qtyToTransfer;
            stockStore.put({
              id: storeLocId,
              sku: item.sku,
              boxNum: 1,
              shelf: targetShelf,
              warehouse: 'trgovina',
              count: newCount,
              isPrimary: true,
              lastUpdated: new Date().toISOString()
            });

            // 3. Posodobi status naloge
            item.status = 'completed';
            item.completedAt = new Date().toISOString();
            item.transferredQty = qtyToTransfer;
            repStore.put(item);

            // 4. Zabeleži v dnevnik
            logStore.add({
              action: 'replenish_shelf',
              sku: item.sku,
              itemName: item.name,
              boxNum: 1,
              location: `Prenos: ${item.sourceWarehouse === 'zgornje' ? 'Zgornje skl.' : 'Spodnje skl.'} (${item.sourceLocation}) ➔ ${targetShelf}`,
              delta: qtyToTransfer,
              timestamp: new Date().toISOString()
            });
          };
        };
      };

      tx.oncomplete = () => {
        this.bumpMutationVersion();
        resolve(true);
      };
      tx.onerror = () => reject(tx.error);
    });
  },

  async removeReplenishmentItem(repId) {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('replenishment_list', 'readwrite');
      tx.objectStore('replenishment_list').delete(repId);
      tx.oncomplete = () => {
        this.bumpMutationVersion();
        resolve(true);
      };
      tx.onerror = () => reject(tx.error);
    });
  },

  /* =========================================================================
   * MEDLOKACIJSKI PRENOS ZALOGE (Skladišče ➔ Trgovina ali obratno)
   * ========================================================================= */
  async transferStock(sku, fromData, toData, qty) {
    const cleanSku = String(sku).trim();
    const transferQty = Math.max(1, Number(qty) || 1);
    const db = await openDB();

    return new Promise((resolve, reject) => {
      const tx = db.transaction(['articles', 'stock_locations', 'logs'], 'readwrite');
      const artStore = tx.objectStore('articles');
      const stockStore = tx.objectStore('stock_locations');
      const logStore = tx.objectStore('logs');

      const stockIndex = stockStore.index('sku');
      stockIndex.getAll(cleanSku).onsuccess = (e) => {
        const stocks = e.target.result || [];
        const fromShelf = typeof fromData === 'string' ? fromData : fromData.shelf;
        const fromWh = typeof fromData === 'object' ? fromData.warehouse : null;
        const fromBoxId = typeof fromData === 'object' ? fromData.boxId : null;

        const source = stocks.find(s => 
          s.shelf === fromShelf && 
          (!fromWh || s.warehouse === fromWh) && 
          (!fromBoxId || s.boxId === fromBoxId) &&
          Number(s.count) > 0
        );

        if (!source || source.count < transferQty) {
          reject(new Error(`Na izhodiščni lokaciji (${fromShelf}) ni dovolj zaloge (na voljo: ${source ? source.count : 0}, zahtevano: ${transferQty}).`));
          return;
        }

        source.count -= transferQty;
        source.lastUpdated = new Date().toISOString();
        stockStore.put(source);

        const toShelf = typeof toData === 'string' ? toData : toData.shelf;
        const toWh = typeof toData === 'object' ? toData.warehouse : (toShelf.startsWith('TRG') ? 'trgovina' : 'zgornje');
        const toBoxId = typeof toData === 'object' ? toData.boxId : null;
        const destId = `${cleanSku}_b1_${toShelf}${toBoxId ? `_box_${toBoxId}` : ''}`;

        stockStore.get(destId).onsuccess = (destEv) => {
          const dest = destEv.target.result;
          const newDestCount = (dest ? Number(dest.count) : 0) + transferQty;
          stockStore.put({
            id: destId,
            sku: cleanSku,
            boxNum: 1,
            shelf: toShelf,
            warehouse: toWh,
            boxId: toBoxId,
            count: newDestCount,
            isPrimary: toWh === 'trgovina',
            lastUpdated: new Date().toISOString()
          });

          artStore.get(cleanSku).onsuccess = (artEv) => {
            const art = artEv.target.result;
            logStore.add({
              action: 'transfer_stock',
              sku: cleanSku,
              itemName: art ? art.name : `Artikel ${cleanSku}`,
              boxNum: 1,
              location: `${fromShelf} ➔ ${toShelf}`,
              delta: transferQty,
              note: `Medlokacijski prenos (${transferQty} kos)`,
              timestamp: new Date().toISOString()
            });
          };
        };
      };

      tx.oncomplete = () => {
        this.bumpMutationVersion();
        resolve(true);
      };
      tx.onerror = () => reject(tx.error);
    });
  }
};
