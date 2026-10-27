/**
 * app.js - Glavna logika skladiščne PWA aplikacije
 */

// Globalno stanje
let currentFilter = 'all';
let cameraStream = null;
let barcodeDetector = null;
if (typeof window !== 'undefined' && 'BarcodeDetector' in window) {
  try {
    barcodeDetector = new window.BarcodeDetector({ formats: ['qr_code', 'ean_13', 'code_128', 'code_39'] });
  } catch (e) {
    try { barcodeDetector = new window.BarcodeDetector(); } catch (err) {}
  }
}
let scanInterval = null;

// Zagotovimo varno razdaljo od orodne vrstice/zareze (safe-area-inset-top za Android status bar)
function initSafeAreaInsets() {
  if (typeof window !== 'undefined' && window.Capacitor && window.Capacitor.isNativePlatform()) {
    const rootStyle = getComputedStyle(document.documentElement);
    const safeTop = rootStyle.getPropertyValue('--safe-top').trim();
    if (!safeTop || safeTop === '0px' || safeTop === '') {
      document.documentElement.style.setProperty('--safe-top', '32px');
    }
  }
}
initSafeAreaInsets();

// Inicializacija ob nalaganju strani
document.addEventListener('DOMContentLoaded', async () => {
  initSafeAreaInsets();
  // 1. Registracija Service Workerja za offline podporo
  registerServiceWorker();

  // 2. Spremljanje online/offline stanja
  setupNetworkListeners();

  // 3. Priprava navigacije med zavihki
  setupTabNavigation();

  // 4. Dogodki za iskalnik in filtre
  setupSearchAndFilters();

  // 5. Dogodki za modalna okna
  setupModals();

  // 6. Dogodki za uvoz/izvoz in nastavitve
  setupSettingsAndBackup();

  // 6b. Priprava modula za inventuro
  setupAuditModule();

  // 6c. Priprava brezžične BLE sinhronizacije
  setupBLESync();

  // 7. Priprava optičnega čitalnika škatel (Google ML Kit)
  setupBoxOCRScanner();

  // 7b. Priprava polnjenja polic (nabiranje) in mešanih škatel
  setupReplenishmentListeners();
  setupMixedBoxesListeners();

  // 8. Naloži podatke iz IndexedDB (le če je čisto prvi zagon in še ni bilo inicializirano ali izbrisano)
  const isDemoSeeded = localStorage.getItem('skladisce_demo_seeded');
  const initialItems = await DB.getAllItems();
  if ((!initialItems || initialItems.length === 0) && !isDemoSeeded) {
    await DB.seedDemoData();
    localStorage.setItem('skladisce_demo_seeded', 'true');
  }
  await refreshInventory();
  updateActiveShelfPillDisplay();
  await populateWarehouseSelectors();
});

/* =========================================================================
   1. SERVICE WORKER & ONLINE STATUS
   ========================================================================= */
function registerServiceWorker() {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('./sw.js')
      .then((reg) => {
        console.log('Service Worker uspešno registriran:', reg.scope);
      })
      .catch((err) => {
        console.warn('Registracija Service Workerja ni uspela:', err);
      });
  }
}

function setupNetworkListeners() {
  const statusEl = document.getElementById('connectionStatus');
  const textEl = document.getElementById('connectionText');

  function updateStatus() {
    if (navigator.onLine) {
      statusEl.className = 'offline-pill online';
      textEl.textContent = 'Povezano';
    } else {
      statusEl.className = 'offline-pill offline';
      textEl.textContent = 'Brez povezave (Offline)';
      showToast('Delate v načinu brez povezave. Vsi podatki se shranjujejo lokalno.');
    }
  }

  window.addEventListener('online', updateStatus);
  window.addEventListener('offline', updateStatus);
  updateStatus();
}

/* =========================================================================
   2. NAVIGACIJA MED ZAVIHKI
   ========================================================================= */
window.switchTab = function(targetId) {
  const tabs = document.querySelectorAll('.nav-tab');
  const contents = document.querySelectorAll('.tab-content');

  tabs.forEach(t => {
    if (t.dataset.tab === targetId) t.classList.add('active');
    else t.classList.remove('active');
  });

  contents.forEach(c => {
    c.style.display = 'none';
    c.classList.remove('active');
  });

  const targetEl = document.getElementById(targetId);
  if (targetEl) {
    targetEl.style.display = (targetId === 'tab-scanner') ? 'flex' : 'block';
    targetEl.classList.add('active');
  }

  // Preklopi gumb Nazaj in naslov v glavi
  const btnBack = document.getElementById('btnHeaderBackHome');
  const appTitle = document.getElementById('headerAppTitle');

  if (targetId === 'tab-home') {
    if (btnBack) btnBack.style.display = 'none';
    if (appTitle) appTitle.textContent = 'Skladišče & Trgovina';
    window.scrollTo({ top: 0, behavior: 'smooth' });
    updateHomeDashboard();
  } else {
    if (btnBack) btnBack.style.display = 'inline-flex';
    const titles = {
      'tab-inventory': 'Zaloga',
      'tab-replenishment': 'Police',
      'tab-scanner': 'Čitalnik',
      'tab-audit': 'Inventura',
      'tab-history': 'Dnevnik',
      'tab-settings': 'Nastavitve'
    };
    if (appTitle) appTitle.textContent = titles[targetId] || 'Skladišče';
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  // Če zapustimo čitalnik, ugasnemo kamero za varčevanje z baterijo
  if (targetId !== 'tab-scanner') {
    stopCamera();
  } else {
    startCamera();
    updateActiveShelfPillDisplay();
    populateWarehouseSelectors();
  }

  // Osveži podatke za ustrezen zavihek
  if (targetId === 'tab-home') {
    updateHomeDashboard();
  } else if (targetId === 'tab-inventory') {
    refreshInventory();
  } else if (targetId === 'tab-replenishment') {
    refreshReplenishmentTab();
  } else if (targetId === 'tab-history') {
    refreshLogs();
  } else if (targetId === 'tab-audit') {
    loadAuditSession();
  } else if (targetId === 'tab-settings') {
    renderSettingsWarehouses();
  }
};

window.updateHomeDashboard = async function() {
  try {
    const items = (typeof cachedItems !== 'undefined' && cachedItems && cachedItems.length > 0)
      ? cachedItems
      : await DB.getAllItems();

    const totalItems = items.length;
    let totalQty = 0;
    let neededForShelfCount = 0;
    let criticalCount = 0;

    for (const it of items) {
      totalQty += Number(it.quantity) || 0;
      if ((Number(it.neededForShelf) || 0) > 0) {
        neededForShelfCount++;
      }
      const minQ = Number(it.minQuantity) || 0;
      if (minQ > 0 && (Number(it.quantity) || 0) <= minQ) {
        criticalCount++;
      }
    }

    const statItemsEl = document.getElementById('homeStatItems');
    const statQtyEl = document.getElementById('homeStatQty');
    const statShelfEl = document.getElementById('homeStatShelfNeeded');
    const statCritEl = document.getElementById('homeStatCritical');

    if (statItemsEl) statItemsEl.textContent = totalItems.toLocaleString('sl-SI');
    if (statQtyEl) statQtyEl.textContent = totalQty.toLocaleString('sl-SI');
    if (statShelfEl) statShelfEl.textContent = neededForShelfCount;
    if (statCritEl) statCritEl.textContent = criticalCount;

    // Značke na ploščicah
    const repList = await DB.getReplenishmentList('pending');
    const repTileBadge = document.getElementById('homeTileRepBadge');
    if (repTileBadge) {
      const pendingCount = (repList && repList.length) ? repList.length : neededForShelfCount;
      if (pendingCount > 0) {
        repTileBadge.textContent = `${pendingCount} za dopolnit`;
        repTileBadge.className = 'tile-badge badge-warning';
      } else {
        repTileBadge.textContent = 'Vse polno';
        repTileBadge.className = 'tile-badge badge-success';
      }
    }

    // Mešane škatle
    const mixedBoxes = await DB.getAllMixedBoxes();
    const mixedTileBadge = document.getElementById('homeTileMixedBadge');
    if (mixedTileBadge) {
      mixedTileBadge.textContent = `${mixedBoxes.length} škatel`;
    }

    // Konsolidacija
    const scattered = await DB.getScatteredItems();
    const consolTileBadge = document.getElementById('homeTileConsolBadge');
    if (consolTileBadge) {
      if (scattered.length > 0) {
        consolTileBadge.textContent = `${scattered.length} razpršenih`;
        consolTileBadge.className = 'tile-badge badge-warning';
      } else {
        consolTileBadge.textContent = '0';
        consolTileBadge.className = 'tile-badge badge-neutral';
      }
    }

    // Prevzem tovornjaka
    const recBanner = document.getElementById('homeReceivingBanner');
    const recTitle = document.getElementById('homeReceivingTitle');
    const recSub = document.getElementById('homeReceivingSub');
    const recTileBadge = document.getElementById('homeTileReceivingBadge');

    if (typeof getActiveReceivingSession === 'function') {
      const session = getActiveReceivingSession();
      if (session && session.active) {
        const entries = Array.isArray(session.entries) ? session.entries : [];
        const totalBoxes = entries.reduce((sum, e) => sum + (Number(e.count) || 0), 0);
        if (recBanner) recBanner.classList.add('active');
        if (recTitle) recTitle.textContent = `Prevzem tovornjaka (${totalBoxes} škatel)`;
        if (recSub) recSub.textContent = 'Klikni za nadaljevanje skeniranja ali zaključek';
        if (recTileBadge) {
          recTileBadge.textContent = `${totalBoxes} škatel`;
          recTileBadge.className = 'tile-badge badge-success';
        }
      } else {
        if (recBanner) recBanner.classList.remove('active');
        if (recTileBadge) {
          recTileBadge.textContent = 'Novo';
          recTileBadge.className = 'tile-badge badge-info';
        }
      }
    }

    const timeEl = document.getElementById('homeSummaryTime');
    if (timeEl) {
      const now = new Date();
      timeEl.textContent = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}`;
    }

  } catch (err) {
    console.error('Napaka pri posodabljanju domačega zaslona:', err);
  }
};

window.filterLowStockFromHome = function() {
  window.switchTab('tab-inventory');
  const chipLow = document.querySelector('#categoryChips button[data-filter="low"]');
  if (chipLow) chipLow.click();
};

function setupTabNavigation() {
  const tabs = document.querySelectorAll('.nav-tab');
  tabs.forEach(tab => {
    tab.addEventListener('click', () => {
      window.switchTab(tab.dataset.tab);
    });
  });
}

/* =========================================================================
   3. ISKALNIK, FILTRI & PRIKAZ ZALOGE
   ========================================================================= */
let currentWarehouseFilter = 'all';
let cachedWarehouses = [
  { id: 'zgornje', name: 'Zgornje skladišče (Soft)', isStore: false, isDefault: true, color: '#10b981' },
  { id: 'spodnje', name: 'Spodnje skladišče (Pohištvo)', isStore: false, isDefault: true, color: '#f59e0b' },
  { id: 'trgovina', name: 'Prodajalna (Police)', isStore: true, isDefault: true, color: '#38bdf8' }
];

function getActiveScannerWarehouse() {
  try {
    const session = getActiveReceivingSession();
    if (session && session.active && session.warehouse && session.warehouse !== 'all') {
      return session.warehouse;
    }
    return localStorage.getItem('skladisce_active_scan_warehouse') || 'zgornje';
  } catch (e) {
    return 'zgornje';
  }
}

window.getActiveScannerWarehouse = getActiveScannerWarehouse;

window.setActiveScannerWarehouse = function(whId) {
  try {
    localStorage.setItem('skladisce_active_scan_warehouse', whId);
  } catch (e) {}
  const sel = document.getElementById('activeScannerWarehouseSelect');
  if (sel && sel.value !== whId) sel.value = whId;
  const boxSel = document.getElementById('boxResultWarehouseSelect');
  if (boxSel && boxSel.value !== whId) boxSel.value = whId;

  const whObj = cachedWarehouses.find(w => w.id === whId);
  if (whObj) {
    showToast(`Izbrano skladišče: ${whObj.name}`);
  }
};

window.onBoxResultWarehouseChange = function(whId) {
  window.setActiveScannerWarehouse(whId);
};

window.populateWarehouseSelectors = async function() {
  try {
    const list = await DB.getWarehouses();
    if (Array.isArray(list) && list.length > 0) {
      cachedWarehouses = list;
    }
    const activeWh = getActiveScannerWarehouse();

    // 1. Zgornja vrstica v zavihku čitalnika (tab-scanner)
    const scanSel = document.getElementById('activeScannerWarehouseSelect');
    if (scanSel) {
      scanSel.innerHTML = cachedWarehouses.map(w => 
        `<option value="${escapeHtml(w.id)}" ${w.id === activeWh ? 'selected' : ''}>🏢 ${escapeHtml(w.name)}</option>`
      ).join('');
    }

    // 2. Prevzemna pošiljka (modalReceivingBatch)
    const recSel = document.getElementById('recSessionWarehouseSelect');
    if (recSel) {
      const session = getActiveReceivingSession();
      const currentRecWh = session ? (session.warehouse || 'all') : 'all';
      let recOptions = `<option value="all" ${currentRecWh === 'all' ? 'selected' : ''}>🌐 Vsa skladišča (avtomatsko po artiklih)</option>`;
      recOptions += cachedWarehouses.map(w => 
        `<option value="${escapeHtml(w.id)}" ${w.id === currentRecWh ? 'selected' : ''}>🏢 ${escapeHtml(w.name)}</option>`
      ).join('');
      recSel.innerHTML = recOptions;
    }

    // 3. Hitra sprememba zaloge v zalogi (modalStockChange)
    const stockChangeSel = document.getElementById('changeWarehouseSelect');
    if (stockChangeSel) {
      stockChangeSel.innerHTML = cachedWarehouses.map(w => 
        `<option value="${escapeHtml(w.id)}" ${w.id === activeWh ? 'selected' : ''}>🏢 ${escapeHtml(w.name)}</option>`
      ).join('');
    }

    // 4. Začetek nove prevzemne pošiljke (modalStartReceiving)
    const startRecSel = document.getElementById('startReceivingWarehouseSelect');
    if (startRecSel) {
      let startOptions = `<option value="all">🌐 Vsa skladišča (avtomatsko po artiklih)</option>`;
      startOptions += cachedWarehouses.map(w => 
        `<option value="${escapeHtml(w.id)}" ${w.id === activeWh ? 'selected' : ''}>🏢 ${escapeHtml(w.name)}</option>`
      ).join('');
      startRecSel.innerHTML = startOptions;
    }
  } catch (e) {
    console.warn('populateWarehouseSelectors error:', e);
  }
};

function setupSearchAndFilters() {
  const searchInput = document.getElementById('searchInput');
  searchInput.addEventListener('input', () => {
    renderItemsList();
  });

  const chips = document.getElementById('categoryChips');
  chips.addEventListener('click', (e) => {
    const btn = e.target.closest('.chip');
    if (!btn) return;

    chips.querySelectorAll('.chip').forEach(c => c.classList.remove('active'));
    btn.classList.add('active');
    currentFilter = btn.dataset.filter;
    renderItemsList();
  });

  const whChips = document.getElementById('warehouseChips');
  if (whChips) {
    whChips.addEventListener('click', (e) => {
      const btn = e.target.closest('.chip');
      if (!btn) return;

      whChips.querySelectorAll('.chip').forEach(c => c.classList.remove('active'));
      btn.classList.add('active');
      currentWarehouseFilter = btn.dataset.wh || 'all';
      renderItemsList();
    });
  }
}

let cachedItems = [];

async function refreshInventory() {
  try {
    cachedItems = await DB.getAllItems();
    if (!Array.isArray(cachedItems)) cachedItems = [];

    try { updateStatsBar(); } catch (e) { console.warn('updateStatsBar error:', e); }
    try { updateCategoryChips(); } catch (e) { console.warn('updateCategoryChips error:', e); }
    try { renderItemsList(); } catch (e) { console.error('renderItemsList error:', e); }
    try { updateReceivingBannerUI(); } catch (e) { console.warn('updateReceivingBannerUI error:', e); }
    if (typeof updateConsolidationBadge === 'function') {
      try { updateConsolidationBadge(); } catch (e) { console.warn('updateConsolidationBadge error:', e); }
    }
    if (typeof updateMixedBoxesBadges === 'function') {
      try { updateMixedBoxesBadges(); } catch (e) { console.warn('updateMixedBoxesBadges error:', e); }
    }
    if (typeof updateHomeDashboard === 'function') {
      try { updateHomeDashboard(); } catch (e) { console.warn('updateHomeDashboard error:', e); }
    }
  } catch (err) {
    console.error('Napaka pri branju zaloge:', err);
    showToast('Napaka pri branju podatkov.');
  }
}

function updateStatsBar() {
  if (!cachedItems || !Array.isArray(cachedItems)) return;
  const totalItems = cachedItems.length;
  const totalQty = cachedItems.reduce((sum, item) => sum + (Number(item.quantity) || 0), 0);
  const lowCount = cachedItems.filter(item => (Number(item.quantity) || 0) <= (Number(item.minQuantity) || 0)).length;

  const elItems = document.getElementById('statTotalItems');
  if (elItems) elItems.textContent = totalItems;
  const elQty = document.getElementById('statTotalQty');
  if (elQty) elQty.textContent = Math.round(totalQty);
  const elLow = document.getElementById('statLowStock');
  if (elLow) elLow.textContent = lowCount;
}

function updateCategoryChips() {
  const chipsContainer = document.getElementById('categoryChips');
  const categories = [...new Set(cachedItems.map(i => i.category).filter(Boolean))];

  // Ohrani prva dva ("Vse" in "Nizka zaloga")
  let html = `
    <button class="chip ${currentFilter === 'all' ? 'active' : ''}" data-filter="all">Vse</button>
    <button class="chip ${currentFilter === 'low' ? 'active' : ''}" data-filter="low">Nizka zaloga</button>
  `;

  categories.forEach(cat => {
    html += `<button class="chip ${currentFilter === cat ? 'active' : ''}" data-filter="${escapeHtml(cat)}">${escapeHtml(cat)}</button>`;
  });

  chipsContainer.innerHTML = html;
}

function renderItemsList() {
  const listEl = document.getElementById('itemsList');
  const search = (document.getElementById('searchInput').value || '').trim().toLowerCase();

  let filtered = cachedItems.filter(item => {
    // Iskalni filter
    const matchesSearch = !search ||
      (item.name && item.name.toLowerCase().includes(search)) ||
      (item.sku && item.sku.toLowerCase().includes(search)) ||
      (item.location && item.location.toLowerCase().includes(search));

    if (!matchesSearch) return false;

    // Filter po skladišču
    if (currentWarehouseFilter !== 'all') {
      if (currentWarehouseFilter === 'trgovina') {
        const hasStore = (Number(item.storeQuantity) || 0) > 0 || (item.shelfLocation && item.shelfLocation.length > 0);
        if (!hasStore) return false;
      } else if (currentWarehouseFilter === 'zgornje') {
        const hasZgornje = (Number(item.zgornjeQuantity) || 0) > 0 || ((item.categoryType === 'soft' || !item.categoryType) && Number(item.quantity) > 0 && (Number(item.spodnjeQuantity) || 0) <= 0);
        if (!hasZgornje) return false;
      } else if (currentWarehouseFilter === 'spodnje') {
        const hasSpodnje = (Number(item.spodnjeQuantity) || 0) > 0 || (item.categoryType === 'furniture' && Number(item.quantity) > 0 && (Number(item.zgornjeQuantity) || 0) <= 0);
        if (!hasSpodnje) return false;
      }
    }

    // Filter po znački/kategoriji
    if (currentFilter === 'all') return true;
    if (currentFilter === 'low') {
      return (Number(item.quantity) || 0) <= (Number(item.minQuantity) || 0);
    }
    return item.category === currentFilter;
  });

  if (filtered.length === 0) {
    listEl.innerHTML = `
      <div class="empty-box">
        <p>Ni najdenih artiklov.</p>
        ${cachedItems.length === 0 ? '<button class="btn-secondary" onclick="loadDemoData()" style="max-width:220px;">Naloži testne artikle</button>' : ''}
      </div>
    `;
    return;
  }

  // Optimizacija za 10.000+ artiklov: ne izrišemo vseh naenkrat v DOM, ampak prvih 60 zadetkov
  const displayLimit = 60;
  const displayed = filtered.slice(0, displayLimit);

  let html = displayed.map(item => {
    const stats = DB.getBoxStats(item);
    const qty = stats.completeSets;
    const minQty = Number(item.minQuantity) || 0;
    
    let statusClass = 'status-ok';
    let statusText = 'Na zalogi';
    if (qty <= 0) {
      statusClass = 'status-empty';
      statusText = 'Ni na zalogi';
    } else if (qty <= minQty) {
      statusClass = 'status-low';
      statusText = 'Nizka zaloga';
    }

    let boxBreakdownHtml = '';
    if (stats.boxTotal > 1) {
      boxBreakdownHtml = `
        <div class="box-breakdown-container" onclick="openEditModal('${escapeHtml(item.sku)}')">
          <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom: 2px;">
            <span style="font-size:0.75rem; color:var(--text-muted); font-weight:600;">${stats.boxTotal} škatle na garnituro:</span>
            ${stats.hasIncomplete 
              ? `<span class="badge-incomplete">${escapeHtml(stats.missingSummary)}</span>` 
              : `<span class="badge-complete">Celi kompleti (${stats.completeSets})</span>`}
          </div>
          ${stats.breakdown.map(b => `
            <div class="box-breakdown-row">
              <span>Škatla ${b.boxNum}/${stats.boxTotal}: <span class="box-breakdown-loc">${escapeHtml(b.location)}</span></span>
              <span class="box-breakdown-count">${b.count} kos</span>
            </div>
          `).join('')}
        </div>
      `;
    }

    let whBreakdownHtml = '';
    const storeQty = Number(item.storeQuantity) || 0;
    const zgornjeQty = Number(item.zgornjeQuantity) || 0;
    const spodnjeQty = Number(item.spodnjeQuantity) || 0;
    const shelfCap = Number(item.shelfCapacity) || 0;
    const needed = Number(item.neededForShelf) || (shelfCap > storeQty ? (shelfCap - storeQty) : 0);

    let whPills = [];
    if (storeQty > 0 || shelfCap > 0) {
      whPills.push(`<span class="wh-pill wh-pill-trgovina" title="Zaloga na prodajnih policah">🏬 Trgovina: ${storeQty}${shelfCap > 0 ? `/${shelfCap}` : ''} kos</span>`);
    }
    if (needed > 0 && (zgornjeQty > 0 || spodnjeQty > 0 || item.quantity > 0)) {
      whPills.push(`<span class="wh-pill wh-pill-needed" onclick="event.stopPropagation(); quickAddReplenishFromItem('${escapeHtml(item.sku)}')" title="Klikni za dodajanje na nabiralni seznam">+ Za police: ${needed}</span>`);
    }
    if (zgornjeQty > 0) {
      whPills.push(`<span class="wh-pill wh-pill-zgornje" title="Zaloga v zgornjem skladišču">📦 Zgornje: ${zgornjeQty}</span>`);
    }
    if (spodnjeQty > 0) {
      whPills.push(`<span class="wh-pill wh-pill-spodnje" title="Zaloga v spodnjem skladišču">🛋️ Spodnje: ${spodnjeQty}</span>`);
    }
    if (item.boxId) {
      whPills.push(`<span class="wh-pill wh-pill-mixed" title="Artikel se nahaja v mešani škatli">📦 Škatla #${escapeHtml(item.boxId)}</span>`);
    }

    if (whPills.length > 0) {
      whBreakdownHtml = `<div class="wh-breakdown-row">${whPills.join('')}</div>`;
    }

    return `
      <div class="item-card" data-sku="${escapeHtml(item.sku)}">
        <div class="item-top" onclick="openEditModal('${escapeHtml(item.sku)}')">
          <div class="item-title-wrap">
            <div class="item-name">${escapeHtml(item.name)}</div>
            <div style="display:flex; flex-wrap:wrap; gap:6px; align-items:center;">
              <span class="item-sku-badge"># ${escapeHtml(item.sku)}</span>
              ${item.location ? `<span class="item-location">${escapeHtml(item.location)}</span>` : ''}
              ${stats.boxTotal > 1 ? `<span class="item-location" style="color:var(--primary); font-weight:600;">${stats.boxTotal}-delni komplet</span>` : ''}
            </div>
          </div>
          <span class="stock-status-badge ${statusClass}">${statusText}</span>
        </div>

        ${boxBreakdownHtml}
        ${whBreakdownHtml}

        <div class="item-bottom">
          <div class="item-qty-display" onclick="openEditModal('${escapeHtml(item.sku)}')">
            <span class="qty-number">${qty}</span>
            <span class="qty-unit">${escapeHtml(item.unit || (stats.boxTotal > 1 ? 'kompletov' : 'kos'))}</span>
          </div>
          <div class="quick-actions">
            <button class="btn-qty btn-move" onclick="event.stopPropagation(); openMoveModal('${escapeHtml(item.sku)}')" title="Premakni med regali" style="font-size: 0.72rem; padding: 0 8px; width: auto;">Regal</button>
            <button class="btn-qty" onclick="openStockChangeModal('${escapeHtml(item.sku)}', 'out')" title="Izdaj blago">-</button>
            <button class="btn-qty" onclick="openStockChangeModal('${escapeHtml(item.sku)}', 'in')" title="Prevzemi blago">+</button>
          </div>
        </div>
      </div>
    `;
  }).join('');

  if (filtered.length > displayLimit) {
    html += `
      <div style="text-align: center; padding: 12px; font-size: 0.85rem; color: var(--text-muted);">
        Prikazanih prvih ${displayLimit} od skupaj <strong>${filtered.length}</strong> ujemajočih se artiklov. Uporabite iskalnik zgoraj za ožji izbor.
      </div>
    `;
  }

  listEl.innerHTML = html;
}

function renderModalBoxInputs(item) {
  const container = document.getElementById('modalBoxLocationsContainer');
  const rowsEl = document.getElementById('modalBoxRows');
  if (!container || !rowsEl) return;

  const boxInfoVal = document.getElementById('itemBoxInfo').value;
  const parsed = DB.parseBoxInfo(boxInfoVal);
  const boxTotal = (item && item.boxTotal) ? item.boxTotal : parsed.boxTotal;

  if (boxTotal <= 1) {
    container.style.display = 'none';
    rowsEl.innerHTML = '';
    return;
  }

  container.style.display = 'block';
  let html = '';
  const currentBoxes = (item && item.boxes) ? item.boxes : {};

  for (let b = 1; b <= boxTotal; b++) {
    const rawBox = currentBoxes[b] || { count: 0, location: document.getElementById('itemLocation').value || '' };
    const bData = DB.normalizeBoxLocations(rawBox, document.getElementById('itemLocation').value || 'Brez lokacije');
    const locs = (bData.locations && bData.locations.length > 0) ? bData.locations : [{ name: bData.location || '', count: bData.count || 0, isPrimary: true }];

    html += `
      <div class="modal-box-group" data-box="${b}" style="background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.08); border-radius: var(--radius-sm); padding: 8px 10px; display:flex; flex-direction:column; gap:6px;">
        <div style="display:flex; justify-content:space-between; align-items:center;">
          <span style="font-size: 0.82rem; font-weight: 700; color: var(--primary);">
            Škatla ${b}/${boxTotal}
          </span>
          <button type="button" onclick="addModalShelfRow(${b})" style="background: rgba(56, 189, 248, 0.12); border: 1px solid rgba(56, 189, 248, 0.3); color: #38bdf8; font-size: 0.72rem; padding: 3px 8px; border-radius: 4px; cursor: pointer; font-weight: 700;">
            + Dodaj drug regal (preliv)
          </button>
        </div>
        <div class="modal-box-shelves-container" id="modalBoxShelves_${b}" style="display:flex; flex-direction:column; gap:5px;">
          ${locs.map((loc, lIdx) => `
            <div class="modal-shelf-row" style="display:flex; gap:6px; align-items:center;">
              <span style="font-size:0.75rem; color:${lIdx === 0 ? 'var(--text-muted)' : '#fbbf24'}; width: 62px; font-weight:600;">
                ${lIdx === 0 ? 'Glavni:' : 'Rezerva:'}
              </span>
              <input type="text" class="form-control modal-shelf-name" placeholder="Regal (npr. Regal P-04)" value="${escapeHtml(loc.name)}" style="flex:2; padding:5px 8px; font-size:0.85rem;">
              <div style="display:flex; align-items:center; gap:3px;">
                <input type="number" min="0" class="form-control modal-shelf-count" placeholder="Kosov" value="${loc.count}" style="width:65px; text-align:center; padding:5px 6px; font-size:0.85rem;">
                <span style="font-size:0.72rem; color:var(--text-muted);">kos</span>
              </div>
              ${lIdx > 0 ? `
                <button type="button" onclick="removeModalShelfRow(this)" style="background:none; border:none; color:#ef4444; cursor:pointer; font-size:1.1rem; padding:0 4px;" title="Odstrani ta regal">✕</button>
              ` : '<div style="width:20px;"></div>'}
            </div>
          `).join('')}
        </div>
      </div>
    `;
  }
  rowsEl.innerHTML = html;
}

window.addModalShelfRow = function(boxNum) {
  const container = document.getElementById(`modalBoxShelves_${boxNum}`);
  if (!container) return;
  const div = document.createElement('div');
  div.className = 'modal-shelf-row';
  div.style.cssText = 'display:flex; gap:6px; align-items:center;';
  div.innerHTML = `
    <span style="font-size:0.75rem; color:#fbbf24; width: 62px; font-weight:600;">Rezerva:</span>
    <input type="text" class="form-control modal-shelf-name" placeholder="Rezervni regal (npr. Regal P-09)" value="" style="flex:2; padding:5px 8px; font-size:0.85rem;">
    <div style="display:flex; align-items:center; gap:3px;">
      <input type="number" min="0" class="form-control modal-shelf-count" placeholder="Kosov" value="0" style="width:65px; text-align:center; padding:5px 6px; font-size:0.85rem;">
      <span style="font-size:0.72rem; color:var(--text-muted);">kos</span>
    </div>
    <button type="button" onclick="removeModalShelfRow(this)" style="background:none; border:none; color:#ef4444; cursor:pointer; font-size:1.1rem; padding:0 4px;" title="Odstrani ta regal">✕</button>
  `;
  container.appendChild(div);
};

window.removeModalShelfRow = function(btn) {
  btn.closest('.modal-shelf-row')?.remove();
};

/* =========================================================================
   4. MODALNA OKNA: DODAJ / UREDI & PREMIK
   ========================================================================= */
function setupModals() {
  // Modal za artikel
  const modalItem = document.getElementById('modalItem');
  const btnOpenAddModal = document.getElementById('btnOpenAddModal');
  const btnCloseItemModal = document.getElementById('btnCloseItemModal');
  const formItem = document.getElementById('formItem');
  const btnGenSku = document.getElementById('btnGenSku');
  const btnDeleteItem = document.getElementById('btnDeleteItem');
  const itemBoxInfoInput = document.getElementById('itemBoxInfo');

  itemBoxInfoInput.addEventListener('input', () => {
    const id = document.getElementById('itemId').value;
    if (id) {
      DB.getItemById(id).then(it => renderModalBoxInputs(it));
    } else {
      renderModalBoxInputs(null);
    }
  });

  btnOpenAddModal.addEventListener('click', () => {
    formItem.reset();
    document.getElementById('itemId').value = '';
    if (lastUsedShelf) {
      document.getElementById('itemLocation').value = lastUsedShelf;
    }
    document.getElementById('modalItemTitle').textContent = 'Nov artikel';
    btnDeleteItem.style.display = 'none';
    renderModalBoxInputs(null);
    modalItem.style.display = 'flex';
  });

  btnCloseItemModal.addEventListener('click', () => {
    modalItem.style.display = 'none';
  });

  btnGenSku.addEventListener('click', () => {
    const rnd = Math.floor(1000 + Math.random() * 9000);
    document.getElementById('itemSku').value = 'ART-' + rnd;
  });

  formItem.addEventListener('submit', async (e) => {
    e.preventDefault();
    const id = document.getElementById('itemId').value;
    const boxInfoStr = document.getElementById('itemBoxInfo').value || 'Box 1/1';
    const parsedBox = DB.parseBoxInfo(boxInfoStr);
    const boxTotal = parsedBox.boxTotal;

    const locInputs = document.querySelectorAll('.modal-box-loc');
    const countInputs = document.querySelectorAll('.modal-box-count');

    let boxes = {};
    let counts = [];

    if (boxTotal > 1) {
      for (let b = 1; b <= boxTotal; b++) {
        const shelfRows = document.querySelectorAll(`#modalBoxShelves_${b} .modal-shelf-row`);
        const locations = [];
        if (shelfRows.length > 0) {
          shelfRows.forEach((row, idx) => {
            const nameInput = row.querySelector('.modal-shelf-name');
            const countInput = row.querySelector('.modal-shelf-count');
            const name = nameInput?.value?.trim() || `Regal ${b}`;
            const count = Number(countInput?.value) || 0;
            locations.push({
              name: name,
              count: count,
              isPrimary: (idx === 0)
            });
          });
        } else {
          locations.push({
            name: document.getElementById('itemLocation').value || 'Brez lokacije',
            count: 0,
            isPrimary: true
          });
        }
        const bData = DB.normalizeBoxLocations({ locations }, document.getElementById('itemLocation').value || 'Brez lokacije');
        boxes[b] = bData;
        counts.push(bData.count);
      }
    } else {
      const singleQty = Number(document.getElementById('itemQuantity').value) || 0;
      const singleLoc = document.getElementById('itemLocation').value || 'Brez lokacije';
      boxes[1] = DB.normalizeBoxLocations({ count: singleQty, location: singleLoc }, singleLoc);
      counts.push(singleQty);
    }

    const calculatedCompleteSets = (boxTotal > 1) ? Math.min(...counts) : Number(document.getElementById('itemQuantity').value);

    const itemData = {
      sku: document.getElementById('itemSku').value,
      name: document.getElementById('itemName').value,
      boxInfo: boxInfoStr,
      boxTotal: boxTotal,
      boxes: boxes,
      category: document.getElementById('itemCategory').value,
      location: document.getElementById('itemLocation').value,
      quantity: calculatedCompleteSets,
      minQuantity: Number(document.getElementById('itemMinQuantity').value) || 0,
      unit: document.getElementById('itemUnit').value,
      notes: document.getElementById('itemNotes').value,
      shelfCapacity: Number(document.getElementById('itemShelfCapacity')?.value) || 0,
      shelfLocation: document.getElementById('itemShelfLocation')?.value?.trim() || '',
      categoryType: document.getElementById('itemCategoryType')?.value || 'soft',
      warehouse: document.getElementById('itemPrimaryWarehouse')?.value || 'zgornje'
    };

    try {
      if (id) {
        itemData.id = Number(id);
        await DB.updateItem(itemData);
        showToast('Artikel uspešno posodobljen.');
      } else {
        const res = await DB.addItem(itemData);
        if (res && res.wasMerged) {
          showToast('Artikel s to šifro že obstaja – zaloga in lokacije so bile združene.');
        } else {
          showToast('Nov artikel dodan v zalogo.');
        }
      }
      modalItem.style.display = 'none';
      await refreshInventory();
    } catch (err) {
      console.error(err);
      showToast('Napaka pri shranjevanju artikla.');
    }
  });

  btnDeleteItem.addEventListener('click', async () => {
    const id = document.getElementById('itemId').value;
    if (!id) return;
    if (confirm('Ali ste prepričani, da želite izbrisati ta artikel?')) {
      await DB.deleteItem(id);
      modalItem.style.display = 'none';
      showToast('Artikel izbrisan.');
      await refreshInventory();
    }
  });

  // Modal za hitri prevzem / izdajo
  const modalStockChange = document.getElementById('modalStockChange');
  const btnCloseStockChange = document.getElementById('btnCloseStockChange');
  const formStockChange = document.getElementById('formStockChange');

  if (btnCloseStockChange) {
    btnCloseStockChange.addEventListener('click', () => {
      modalStockChange.style.display = 'none';
    });
  }

  if (formStockChange) {
    formStockChange.addEventListener('submit', async (e) => {
      e.preventDefault();
      const itemId = document.getElementById('changeItemId').value;
      const direction = document.getElementById('changeDirection').value;
      const amount = Number(document.getElementById('changeAmount').value);
      const note = document.getElementById('changeNote').value;
      const warehouse = document.getElementById('changeWarehouseSelect')?.value || getActiveScannerWarehouse();

      const delta = (direction === 'in') ? amount : -amount;

      try {
        if (direction === 'in') {
          const item = await DB.getItemById(itemId);
          if (item) {
            await recordReceivingEntry(item.sku, item.name, 1, item.boxTotal || 1, item.location || 'Glavni regal', amount, warehouse);
          }
        }
        await DB.adjustStock(itemId, delta, note, direction, null, null, warehouse);
        if (navigator.vibrate) navigator.vibrate(60);
        modalStockChange.style.display = 'none';
        const whObj = cachedWarehouses.find(w => w.id === warehouse);
        const whName = whObj ? whObj.name : warehouse;
        showToast(direction === 'in' ? `Prevzem +${amount} zabeležen v ${whName}` : `Izdaja -${amount} zabeležena iz ${whName}`);
        await refreshInventory();
      } catch (err) {
        console.error(err);
        showToast('Napaka pri posodobitvi zaloge.');
      }
    });
  }

  // Modal za premestitev med regali
  const modalMoveStock = document.getElementById('modalMoveStock');
  const btnCloseMoveStockModal = document.getElementById('btnCloseMoveStockModal');
  const btnCancelMoveStock = document.getElementById('btnCancelMoveStock');
  const formMoveStock = document.getElementById('formMoveStock');

  if (btnCloseMoveStockModal) {
    btnCloseMoveStockModal.addEventListener('click', () => {
      modalMoveStock.style.display = 'none';
    });
  }
  if (btnCancelMoveStock) {
    btnCancelMoveStock.addEventListener('click', () => {
      modalMoveStock.style.display = 'none';
    });
  }
  if (formMoveStock) {
    formMoveStock.addEventListener('submit', async (e) => {
      e.preventDefault();
      const sku = document.getElementById('moveItemSku').value;
      const boxNum = parseInt(document.getElementById('moveBoxNum').value, 10) || 1;
      const fromShelf = document.getElementById('moveFromShelf').value;
      const toShelf = document.getElementById('moveToShelf').value.trim();
      const amount = parseInt(document.getElementById('moveAmount').value, 10) || 1;
      const note = document.getElementById('moveNote').value.trim();

      try {
        await DB.moveStock(sku, boxNum, fromShelf, toShelf, amount, note);
        if (navigator.vibrate) navigator.vibrate(60);
        modalMoveStock.style.display = 'none';
        showToast(`Premaknjeno ${amount} škatel (${fromShelf} -> ${toShelf})`);
        await refreshInventory();
        if (typeof renderConsolidationList === 'function' && document.getElementById('modalConsolidation')?.style.display === 'flex') {
          await renderConsolidationList();
        }
      } catch (err) {
        console.error(err);
        showToast(err.message || 'Napaka pri premestitvi med regali.');
      }
    });
  }

  // Modal za prevzemno pošiljko
  const modalReceivingBatch = document.getElementById('modalReceivingBatch');
  const btnCloseReceivingModal = document.getElementById('btnCloseReceivingModal');
  const btnKeepReceiving = document.getElementById('btnKeepReceiving');
  const btnFinishReceivingSession = document.getElementById('btnFinishReceivingSession');
  const btnExportReceivingPDF = document.getElementById('btnExportReceivingPDF');
  const btnOpenReceivingModal = document.getElementById('btnOpenReceivingModal');

  if (btnCloseReceivingModal) {
    btnCloseReceivingModal.addEventListener('click', () => {
      modalReceivingBatch.style.display = 'none';
    });
  }
  if (btnKeepReceiving) {
    btnKeepReceiving.addEventListener('click', () => {
      modalReceivingBatch.style.display = 'none';
      switchTab('tab-scanner');
    });
  }
  if (btnFinishReceivingSession) {
    btnFinishReceivingSession.addEventListener('click', () => {
      finishReceivingSession();
    });
  }
  if (btnExportReceivingPDF) {
    btnExportReceivingPDF.addEventListener('click', () => {
      exportReceivingPDF();
    });
  }
  if (btnOpenReceivingModal) {
    btnOpenReceivingModal.addEventListener('click', () => {
      const session = getActiveReceivingSession();
      if (session && session.active) {
        openReceivingModal();
      } else {
        const name = prompt('Vnesite naziv ali številko prevzemne pošiljke:', `Pošiljka ${new Date().toLocaleDateString('sl-SI')}`);
        if (name !== null) {
          startReceivingSession(name.trim());
        }
      }
    });
  }

  // Zapri modal ob kliku zunaj
  window.addEventListener('click', (e) => {
    if (e.target === modalItem) modalItem.style.display = 'none';
    if (e.target === modalStockChange) modalStockChange.style.display = 'none';
    if (e.target === modalMoveStock) modalMoveStock.style.display = 'none';
    if (e.target === modalReceivingBatch) modalReceivingBatch.style.display = 'none';
    const modalCons = document.getElementById('modalConsolidation');
    if (modalCons && e.target === modalCons) modalCons.style.display = 'none';
    const modalArch = document.getElementById('modalReceivingArchive');
    if (modalArch && e.target === modalArch) modalArch.style.display = 'none';
  });

  updateReceivingBannerUI();
}

window.openEditModal = async function(id) {
  const item = await DB.getItemById(id);
  if (!item) return;

  document.getElementById('itemId').value = item.id;
  document.getElementById('itemSku').value = item.sku || '';
  document.getElementById('itemName').value = item.name || '';
  document.getElementById('itemBoxInfo').value = item.boxInfo || 'Box 1/1';
  const boxTotalEl = document.getElementById('itemBoxTotal');
  if (boxTotalEl) boxTotalEl.value = item.boxTotal || 1;
  document.getElementById('itemCategory').value = item.category || '';
  document.getElementById('itemLocation').value = item.location || '';
  document.getElementById('itemQuantity').value = item.quantity || 0;
  document.getElementById('itemMinQuantity').value = item.minQuantity || 0;
  document.getElementById('itemUnit').value = item.unit || 'kos';
  document.getElementById('itemNotes').value = item.notes || '';
  if (document.getElementById('itemShelfCapacity')) document.getElementById('itemShelfCapacity').value = item.shelfCapacity || '';
  if (document.getElementById('itemShelfLocation')) document.getElementById('itemShelfLocation').value = item.shelfLocation || '';
  if (document.getElementById('itemCategoryType')) document.getElementById('itemCategoryType').value = item.categoryType || 'soft';
  if (document.getElementById('itemPrimaryWarehouse')) document.getElementById('itemPrimaryWarehouse').value = item.warehouse || 'zgornje';

  renderModalBoxInputs(item);

  document.getElementById('modalItemTitle').textContent = 'Uredi artikel';
  document.getElementById('btnDeleteItem').style.display = 'block';
  document.getElementById('modalItem').style.display = 'flex';
};

window.openStockChangeModal = async function(id, direction) {
  const item = await DB.getItemById(id);
  if (!item) return;

  document.getElementById('changeItemId').value = item.id;
  document.getElementById('changeDirection').value = direction;
  document.getElementById('changeItemName').textContent = item.name;
  document.getElementById('changeItemCurrent').textContent = `Trenutno: ${item.quantity} ${item.unit || 'kos'} (${item.location || 'Brez lokacije'})`;
  document.getElementById('changeAmount').value = '1';
  document.getElementById('changeNote').value = '';

  const title = document.getElementById('stockChangeTitle');
  const label = document.getElementById('changeAmountLabel');
  const whLabel = document.getElementById('changeWarehouseLabel');
  const btn = document.getElementById('btnConfirmStockChange');

  if (direction === 'in') {
    title.textContent = 'Prevzem v skladišče';
    label.textContent = 'Količina prevzema (+)';
    if (whLabel) whLabel.textContent = 'Skladišče prevzema';
    btn.textContent = 'Potrdi prevzem';
  } else {
    title.textContent = 'Izdaja iz skladišča';
    label.textContent = 'Količina izdaje (-)';
    if (whLabel) whLabel.textContent = 'Skladišče izdaje';
    btn.textContent = 'Potrdi izdajo';
  }

  await populateWarehouseSelectors();
  const whSelect = document.getElementById('changeWarehouseSelect');
  if (whSelect) {
    const itemWh = item.primaryWarehouse || item.warehouse || (item.categoryType === 'furniture' ? 'spodnje' : 'zgornje');
    whSelect.value = itemWh || getActiveScannerWarehouse();
  }

  document.getElementById('modalStockChange').style.display = 'flex';
};

/* =========================================================================
   4b. PREMESTITEV MED REGALI (HITRI PREMIK)
   ========================================================================= */
window.openMoveModal = async function(sku, prefillBoxNum = null, prefillFromShelf = null, prefillToShelf = null, prefillAmount = null) {
  const item = await DB.getItemBySKU(sku);
  if (!item) return;

  const modalMove = document.getElementById('modalMoveStock');
  document.getElementById('moveItemSku').value = item.sku;
  document.getElementById('moveItemName').textContent = item.name;
  document.getElementById('moveItemSkuDisplay').textContent = `# ${item.sku}`;
  document.getElementById('moveToShelf').value = prefillToShelf || '';
  document.getElementById('moveNote').value = prefillToShelf ? 'Konsolidacija zaloge' : '';

  // Napolni predloge regalov za ciljni regal (vsi regali v skladišču)
  const datalist = document.getElementById('shelfSuggestions');
  if (datalist) {
    const allShelves = new Set();
    cachedItems.forEach(it => {
      if (it.boxes) {
        Object.values(it.boxes).forEach(bx => {
          (bx.locations || []).forEach(l => {
            if (l.name && l.name !== 'Brez lokacije') allShelves.add(l.name);
          });
        });
      }
    });
    datalist.innerHTML = Array.from(allShelves).sort().map(s => `<option value="${escapeHtml(s)}">`).join('');
  }

  const boxSelectGroup = document.getElementById('moveBoxSelectGroup');
  const boxSelect = document.getElementById('moveBoxNum');
  const fromSelect = document.getElementById('moveFromShelf');
  const amountInput = document.getElementById('moveAmount');

  const updateFromShelves = () => {
    const selectedBox = parseInt(boxSelect.value, 10) || 1;
    const bData = item.boxes && item.boxes[selectedBox];
    fromSelect.innerHTML = '';
    
    const locsWithStock = (bData?.locations || []).filter(l => (Number(l.count) || 0) > 0);
    if (locsWithStock.length === 0) {
      fromSelect.innerHTML = `<option value="">(Ni zaloge na nobenem regalu)</option>`;
      amountInput.value = 0;
      amountInput.max = 0;
    } else {
      locsWithStock.forEach(l => {
        const opt = document.createElement('option');
        opt.value = l.name;
        opt.textContent = `${l.name} (${l.count} kos na voljo)`;
        fromSelect.appendChild(opt);
      });
      amountInput.max = locsWithStock[0].count;
      amountInput.value = Math.min(1, locsWithStock[0].count);
    }
  };

  if (item.boxTotal > 1) {
    boxSelectGroup.style.display = 'block';
    boxSelect.innerHTML = '';
    for (let b = 1; b <= item.boxTotal; b++) {
      const opt = document.createElement('option');
      opt.value = b;
      opt.textContent = `Škatla ${b}/${item.boxTotal}`;
      boxSelect.appendChild(opt);
    }
    if (prefillBoxNum) {
      boxSelect.value = prefillBoxNum;
    }
  } else {
    boxSelectGroup.style.display = 'none';
    boxSelect.innerHTML = '<option value="1">Škatla 1/1</option>';
  }

  boxSelect.onchange = updateFromShelves;
  fromSelect.onchange = () => {
    const selectedBox = parseInt(boxSelect.value, 10) || 1;
    const bData = item.boxes && item.boxes[selectedBox];
    const curLoc = (bData?.locations || []).find(l => l.name === fromSelect.value);
    if (curLoc) {
      amountInput.max = curLoc.count;
      amountInput.value = Math.min(1, curLoc.count);
    }
  };

  updateFromShelves();

  if (prefillFromShelf) {
    fromSelect.value = prefillFromShelf;
    const selectedBox = parseInt(boxSelect.value, 10) || 1;
    const bData = item.boxes && item.boxes[selectedBox];
    const curLoc = (bData?.locations || []).find(l => l.name === prefillFromShelf);
    if (curLoc) {
      amountInput.max = curLoc.count;
      amountInput.value = prefillAmount ? Math.min(prefillAmount, curLoc.count) : curLoc.count;
    }
  }

  modalMove.style.display = 'flex';
};

/* =========================================================================
   4c. PREVZEMNA POŠILJKA & ANALIZA KOMPLETIRANJA TER PRIMANJKLJAJEV
   ========================================================================= */
const RECEIVING_SESSION_KEY = 'skladisce_active_receiving_session';

function getActiveReceivingSession() {
  try {
    const raw = localStorage.getItem(RECEIVING_SESSION_KEY);
    if (!raw) return null;
    const session = JSON.parse(raw);
    if (!session || typeof session !== 'object') return null;
    if (!session.initialStock || typeof session.initialStock !== 'object') {
      session.initialStock = {};
    }
    if (!Array.isArray(session.entries)) {
      session.entries = [];
    }
    return session;
  } catch (e) {
    return null;
  }
}

function saveActiveReceivingSession(session) {
  if (!session) {
    localStorage.removeItem(RECEIVING_SESSION_KEY);
  } else {
    localStorage.setItem(RECEIVING_SESSION_KEY, JSON.stringify(session));
  }
  updateReceivingBannerUI();
}

function startReceivingSession(customName = '', warehouse = 'all') {
  const dateStr = new Date().toLocaleDateString('sl-SI');
  const session = {
    id: 'rec_' + Date.now(),
    name: customName || `Prevzem ${dateStr}`,
    warehouse: warehouse || 'all',
    startedAt: new Date().toISOString(),
    active: true,
    initialStock: {}, // { [sku]: { [boxNum]: number } }
    entries: [] // Array<{ id, timestamp, sku, name, boxNum, boxTotal, shelf, warehouse, count }>
  };
  saveActiveReceivingSession(session);
  if (warehouse && warehouse !== 'all') {
    setActiveScannerWarehouse(warehouse);
  }
  showToast(`Prevzem pošiljke "${session.name}" je zagnan.`);
  return session;
}

async function recordReceivingEntry(sku, name, boxNum, boxTotal, shelf, count, warehouse = null) {
  const session = getActiveReceivingSession();
  if (!session || !session.active) return;

  const cleanSku = String(sku).trim();
  const bNum = Math.max(1, parseInt(boxNum, 10) || 1);
  const bTotal = Math.max(1, parseInt(boxTotal, 10) || 1);
  const qty = Math.max(1, parseInt(count, 10) || 1);
  const resolvedWh = warehouse || (session.warehouse && session.warehouse !== 'all' ? session.warehouse : getActiveScannerWarehouse());

  if (!session.initialStock) session.initialStock = {};

  // Če za ta SKU še nimamo posnetega začetnega stanja pred pošiljko, ga preberemo iz DB
  if (!session.initialStock[cleanSku]) {
    session.initialStock[cleanSku] = {};
    const item = await DB.getItemBySKU(cleanSku);
    if (item && item.boxes) {
      for (let b = 1; b <= item.boxTotal; b++) {
        session.initialStock[cleanSku][b] = Number(item.boxes[b]?.count) || 0;
      }
    } else {
      for (let b = 1; b <= bTotal; b++) {
        session.initialStock[cleanSku][b] = 0;
      }
    }
  }

  // Preverimo stanje novo kompletiranih garnitur PRED tem vnosom
  const prevCompleted = calculateSkuCompletion(cleanSku, bTotal, session.initialStock[cleanSku], session.entries).completedFromOld;

  // Dodamo nov vnos
  session.entries.push({
    id: 'entry_' + Date.now() + '_' + Math.floor(Math.random() * 1000),
    timestamp: new Date().toISOString(),
    sku: cleanSku,
    name: name || `Artikel ${cleanSku}`,
    boxNum: bNum,
    boxTotal: bTotal,
    shelf: shelf || 'Glavni regal',
    warehouse: resolvedWh,
    count: qty
  });

  // Preverimo stanje novo kompletiranih garnitur PO tem vnosu
  const newCompleted = calculateSkuCompletion(cleanSku, bTotal, session.initialStock[cleanSku], session.entries).completedFromOld;

  saveActiveReceivingSession(session);

  if (newCompleted > prevCompleted) {
    const diff = newCompleted - prevCompleted;
    showToast(`Kompletirano: +${diff} komplet artikla "${name}" (dopolnjeno s predhodno zalogo).`);
  }
}

/**
 * Izračuna kompletiranje garnitur po točnem pravilu:
 * - Če na paleti prideta 1/2 in 2/2 in imamo v skladišču že 2/2, se NE izpiše kompletirano.
 * - Le tiste škatle, ki so v tej pošiljki dejansko dopolnile manjkajoče škatle iz predhodne zaloge, štejejo.
 * - Vse vmesne izdaje iz skladišča se NE štejejo v prevzemno pošiljko in ne zmanjšujejo prevzetih škatel.
 */
function calculateSkuCompletion(sku, boxTotal, initialBoxCounts = {}, entries = []) {
  const bTotal = Math.max(1, parseInt(boxTotal, 10) || 1);
  const skuEntries = (Array.isArray(entries) ? entries : []).filter(e => e && String(e.sku).trim() === String(sku).trim());
  const initBoxCounts = (initialBoxCounts && typeof initialBoxCounts === 'object') ? initialBoxCounts : {};

  // Seštevek prejetih škatel v tej pošiljki po boxNum
  const batchCounts = {};
  for (let b = 1; b <= bTotal; b++) {
    batchCounts[b] = 0;
  }
  for (const entry of skuEntries) {
    const b = entry.boxNum || 1;
    batchCounts[b] = (batchCounts[b] || 0) + (Number(entry.count) || 0);
  }

  // Začetna zaloga pred pošiljko
  const initCounts = {};
  for (let b = 1; b <= bTotal; b++) {
    initCounts[b] = Number(initBoxCounts[b]) || 0;
  }

  // Število kompletov pred začetkom pošiljke
  const initMin = Math.min(...Object.values(initCounts));

  // Število celih kompletov znotraj same pošiljke (prišli na paleti skupaj)
  const batchMin = Math.min(...Object.values(batchCounts));

  // Skupno stanje po prejemu te pošiljke
  const currentCounts = {};
  for (let b = 1; b <= bTotal; b++) {
    currentCounts[b] = initCounts[b] + batchCounts[b];
  }
  const currentMin = Math.min(...Object.values(currentCounts));

  // Celotno povečanje celih garnitur
  const totalNewSets = Math.max(0, currentMin - initMin);

  // Kompletirano izključno iz predhodne zaloge (stara zaloga dopolnjena z novimi škatlami)
  // Če sta prišli 1/2 in 2/2 skupaj, se pobotata sami med seboj in ne štejeta kot dopolnitev stare zaloge
  const completedFromOld = (bTotal > 1) ? Math.max(0, totalNewSets - batchMin) : 0;

  // Primanjkljaji v pošiljki (če je prišlo več ene škatle kot druge)
  const batchMax = Math.max(...Object.values(batchCounts));
  const missingInBatch = [];
  if (bTotal > 1 && batchMax > 0) {
    for (let b = 1; b <= bTotal; b++) {
      if (batchCounts[b] < batchMax) {
        missingInBatch.push({
          boxNum: b,
          boxTotal: bTotal,
          missingCount: batchMax - batchCounts[b],
          receivedCount: batchCounts[b]
        });
      }
    }
  }

  return {
    sku,
    boxTotal: bTotal,
    initCounts,
    batchCounts,
    currentCounts,
    initMin,
    currentMin,
    batchMin,
    totalNewSets,
    completedFromOld,
    missingInBatch
  };
}

window.handleReceivingBannerClick = function() {
  const session = getActiveReceivingSession();
  if (session && session.active) {
    openReceivingModal();
  } else {
    openStartReceivingModal();
  }
};

window.openStartReceivingModal = async function() {
  await populateWarehouseSelectors();
  const dateStr = new Date().toLocaleDateString('sl-SI');
  const nameInput = document.getElementById('startRecNameInput');
  if (nameInput) nameInput.value = `Pošiljka ${dateStr}`;
  const modal = document.getElementById('modalStartReceiving');
  if (modal) modal.style.display = 'flex';
};

window.closeStartReceivingModal = function() {
  const modal = document.getElementById('modalStartReceiving');
  if (modal) modal.style.display = 'none';
};

window.handleStartReceivingForm = function(event) {
  if (event) event.preventDefault();
  const name = document.getElementById('startRecNameInput')?.value?.trim() || `Pošiljka ${new Date().toLocaleDateString('sl-SI')}`;
  const warehouse = document.getElementById('startReceivingWarehouseSelect')?.value || 'all';
  startReceivingSession(name, warehouse);
  closeStartReceivingModal();
  switchTab('tab-scanner');
};

window.updateReceivingSessionWarehouse = function(whId) {
  const session = getActiveReceivingSession();
  if (!session) return;
  session.warehouse = whId;
  saveActiveReceivingSession(session);
  if (whId !== 'all') {
    setActiveScannerWarehouse(whId);
  }
  const whObj = cachedWarehouses.find(w => w.id === whId);
  showToast(`Skladišče pošiljke: ${whObj ? whObj.name : 'Vsa skladišča'}`);
};

function updateReceivingBannerUI() {
  try {
    const banner = document.getElementById('receivingBanner');
    const title = document.getElementById('receivingBannerTitle');
    const sub = document.getElementById('receivingBannerSub');
    const btn = document.getElementById('btnOpenReceivingModal');
    if (!banner || !title || !sub || !btn) return;

    const session = getActiveReceivingSession();
    if (session && session.active) {
      banner.style.display = 'flex';
      banner.style.background = 'rgba(16, 185, 129, 0.12)';
      banner.style.borderColor = 'rgba(16, 185, 129, 0.35)';

      const entries = Array.isArray(session.entries) ? session.entries : [];
      const totalBoxes = entries.reduce((sum, e) => sum + (Number(e.count) || 0), 0);
      
      let totalCompleted = 0;
      const skus = [...new Set(entries.map(e => e.sku).filter(Boolean))];
      for (const sku of skus) {
        const entry = entries.find(e => e.sku === sku);
        if (!entry) continue;
        const initMap = (session.initialStock && session.initialStock[sku]) ? session.initialStock[sku] : {};
        const res = calculateSkuCompletion(sku, entry.boxTotal || 1, initMap, entries);
        totalCompleted += res.completedFromOld;
      }

      title.innerHTML = `Prevzem: <strong>${escapeHtml(session.name)}</strong> <span style="font-size:0.75rem; color:#10b981; font-weight:normal;">(v teku)</span>`;
      sub.innerHTML = `Prevzeto: <strong>${totalBoxes}</strong> škatel &bull; Kompletirano: <strong style="color:#10b981;">${totalCompleted}</strong>`;
      btn.textContent = 'Pregled in zaključek';
      btn.style.background = '#10b981';
    } else {
      banner.style.display = 'flex';
      banner.style.background = 'rgba(255, 255, 255, 0.03)';
      banner.style.borderColor = 'var(--border)';
      title.innerHTML = `Prevzemna pošiljka <span style="font-size:0.75rem; color:var(--text-muted); font-weight:normal;">(ni aktivna)</span>`;
      sub.innerHTML = `Spremljanje prevzema blaga, kompletiranja in primanjkljajev.`;
      btn.textContent = 'Začni nov prevzem';
      btn.style.background = 'var(--primary)';
    }
  } catch (err) {
    console.error('Napaka v updateReceivingBannerUI:', err);
  }
}

window.openReceivingModal = async function() {
  try {
    const session = getActiveReceivingSession();
    if (!session) {
      showToast('Ni aktivne prevzemne pošiljke.');
      return;
    }

    const modal = document.getElementById('modalReceivingBatch');
    if (!modal) {
      console.error('modalReceivingBatch ni najden v DOM!');
      return;
    }

    const titleEl = document.getElementById('receivingModalTitle');
    if (titleEl) titleEl.textContent = session.name || 'Prevzem pošiljke';
    
    const dateEl = document.getElementById('receivingModalDate');
    if (dateEl) {
      const startDate = session.startedAt 
        ? (new Date(session.startedAt).toLocaleDateString('sl-SI') + ' ob ' + new Date(session.startedAt).toLocaleTimeString('sl-SI', { hour: '2-digit', minute: '2-digit' }))
        : 'danes';
      dateEl.textContent = `Začetek prevzema: ${startDate}`;
    }

    await populateWarehouseSelectors();
    const recSel = document.getElementById('recSessionWarehouseSelect');
    if (recSel) {
      recSel.value = session.warehouse || 'all';
    }

    const entries = Array.isArray(session.entries) ? session.entries : [];
    const initialStockMap = (session.initialStock && typeof session.initialStock === 'object') ? session.initialStock : {};

    const totalBoxes = entries.reduce((sum, e) => sum + (Number(e.count) || 0), 0);
    const totalEl = document.getElementById('recStatTotalBoxes');
    if (totalEl) totalEl.textContent = totalBoxes;

    // Analiza po artiklih
    const skus = [...new Set(entries.map(e => e.sku).filter(Boolean))];
    let totalCompletedSets = 0;
    let totalMissingBoxes = 0;

    const completedItems = [];
    const missingItems = [];

    for (const sku of skus) {
      const entry = entries.find(e => e.sku === sku);
      if (!entry) continue;
      const boxTotal = Math.max(1, parseInt(entry.boxTotal, 10) || 1);
      const initBoxStock = initialStockMap[sku] || {};
      const res = calculateSkuCompletion(sku, boxTotal, initBoxStock, entries);

      if (res.completedFromOld > 0) {
        totalCompletedSets += res.completedFromOld;
        completedItems.push({
          sku,
          name: entry.name,
          boxTotal,
          completedCount: res.completedFromOld,
          currentMin: res.currentMin
        });
      }

      if (res.missingInBatch && res.missingInBatch.length > 0) {
        const missCount = res.missingInBatch.reduce((sum, m) => sum + m.missingCount, 0);
        totalMissingBoxes += missCount;
        missingItems.push({
          sku,
          name: entry.name,
          boxTotal,
          missing: res.missingInBatch
        });
      }
    }

    const compEl = document.getElementById('recStatCompleted');
    if (compEl) compEl.textContent = totalCompletedSets;

    const missEl = document.getElementById('recStatMissing');
    if (missEl) missEl.textContent = totalMissingBoxes;

    // Izpis kompletiranih
    const compListEl = document.getElementById('recCompletedList');
    if (compListEl) {
      if (completedItems.length === 0) {
        compListEl.innerHTML = `<div style="font-size:0.8rem; color:var(--text-muted); padding:6px 0;">Ni dopolnjenih garnitur iz predhodne zaloge.</div>`;
      } else {
        compListEl.innerHTML = completedItems.map(c => `
          <div style="background: rgba(16, 185, 129, 0.08); border: 1px solid rgba(16, 185, 129, 0.25); border-radius: var(--radius-sm); padding: 8px 10px; display: flex; justify-content: space-between; align-items: center;">
            <div>
              <div style="font-weight: 700; font-size: 0.88rem; color: #fff;">${escapeHtml(c.name)}</div>
              <div style="font-size: 0.75rem; color: var(--text-muted);"># ${escapeHtml(c.sku)} &bull; ${c.boxTotal}-delna garnitura &bull; Skupaj na zalogi: ${c.currentMin} kpl</div>
            </div>
            <div style="background: #10b981; color: #fff; font-weight: 800; font-size: 0.85rem; border-radius: 6px; padding: 4px 8px; white-space: nowrap;">
              +${c.completedCount} kpl
            </div>
          </div>
        `).join('');
      }
    }

    // Izpis primanjkljajev
    const missListEl = document.getElementById('recMissingList');
    if (missListEl) {
      if (missingItems.length === 0) {
        missListEl.innerHTML = `<div style="font-size:0.8rem; color:var(--text-muted); padding:6px 0;">Brez primanjkljajev v tej pošiljki.</div>`;
      } else {
        missListEl.innerHTML = missingItems.map(m => `
          <div style="background: rgba(245, 158, 11, 0.08); border: 1px solid rgba(245, 158, 11, 0.25); border-radius: var(--radius-sm); padding: 8px 10px;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px;">
              <div style="font-weight: 700; font-size: 0.88rem; color: #fff;">${escapeHtml(m.name)}</div>
              <span style="font-size: 0.72rem; color: #fbbf24; font-weight: 700;"># ${escapeHtml(m.sku)}</span>
            </div>
            <div style="font-size: 0.76rem; color: #fbbf24;">
              ${m.missing.map(x => `Manjka ${x.missingCount}x Škatla ${x.boxNum}/${x.boxTotal} (prevzeto: ${x.receivedCount} kos)`).join('<br>')}
            </div>
          </div>
        `).join('');
      }
    }

    // Seznam vseh prevzetih postavk
    const allListEl = document.getElementById('recAllReceivedList');
    if (allListEl) {
      if (entries.length === 0) {
        allListEl.innerHTML = `<div style="font-size:0.8rem; color:var(--text-muted); padding:6px 0;">Ni prevzetih postavk.</div>`;
      } else {
        allListEl.innerHTML = entries.slice().reverse().map(e => {
          const whBadgeClass = (e.warehouse === 'spodnje') ? 'wh-pill-spodnje' : (e.warehouse === 'trgovina' ? 'wh-pill-trgovina' : 'wh-pill-zgornje');
          const whObj = cachedWarehouses.find(w => w.id === e.warehouse);
          const whName = whObj ? whObj.name.split(' ')[0] : (e.warehouse || 'Zgornje');
          return `
          <div style="display: flex; justify-content: space-between; align-items: center; background: rgba(255,255,255,0.02); border: 1px solid var(--border); border-radius: 4px; padding: 6px 8px; font-size: 0.78rem;">
            <div>
              <strong style="color: #fff;">${escapeHtml(e.name)}</strong>
              <div style="color: var(--text-muted); font-size: 0.72rem; display: flex; gap: 5px; align-items: center; margin-top: 2px;">
                <span>#${escapeHtml(e.sku)}</span>
                <span>&bull;</span>
                <span>Šk.${e.boxNum}/${e.boxTotal}</span>
                <span>&bull;</span>
                <span>${escapeHtml(e.shelf)}</span>
                <span class="wh-pill ${whBadgeClass}" style="font-size: 0.65rem; padding: 1px 5px;">${escapeHtml(whName)}</span>
              </div>
            </div>
            <div style="font-weight: 700; color: #10b981;">+${e.count} kos</div>
          </div>
        `;
        }).join('');
      }
    }

    modal.style.display = 'flex';
  } catch (err) {
    console.error('Napaka v openReceivingModal:', err);
    showToast('Napaka pri odpiranju prevzema: ' + err.message);
  }
};

window.finishReceivingSession = function() {
  const session = getActiveReceivingSession();
  if (!session) return;

  if (confirm(`Ali želite zaključiti prevzem pošiljke "${session.name}"?`)) {
    try {
      const historyKey = 'skladisce_receiving_history';
      const hist = JSON.parse(localStorage.getItem(historyKey) || '[]');
      session.finishedAt = new Date().toISOString();
      session.active = false;

      // Izračunaj statistični povzetek pred arhiviranjem
      const entries = Array.isArray(session.entries) ? session.entries : [];
      const totalBoxes = entries.reduce((sum, e) => sum + (Number(e.count) || 0), 0);
      const skus = [...new Set(entries.map(e => e.sku).filter(Boolean))];

      let totalCompleted = 0;
      let missingCount = 0;
      for (const sku of skus) {
        const entry = entries.find(e => e.sku === sku);
        if (!entry) continue;
        const initMap = (session.initialStock && session.initialStock[sku]) ? session.initialStock[sku] : {};
        const res = calculateSkuCompletion(sku, entry.boxTotal || 1, initMap, entries);
        totalCompleted += res.totalNewSets;
        if (res.missingInBatch.length > 0) missingCount++;
      }

      session.stats = {
        totalBoxes,
        uniqueSkusCount: skus.length,
        totalCompletedSets: totalCompleted,
        incompleteItemsCount: missingCount
      };

      hist.unshift(session);
      if (hist.length > 50) hist.pop();
      localStorage.setItem(historyKey, JSON.stringify(hist));
    } catch (e) {
      console.warn('Napaka pri shranjevanju v zgodovino prevzemov:', e);
    }

    localStorage.removeItem(RECEIVING_SESSION_KEY);
    document.getElementById('modalReceivingBatch').style.display = 'none';
    updateReceivingBannerUI();
    showToast(`Prevzem pošiljke "${session.name}" je zaključen in arhiviran.`);
  }
};

window.exportReceivingPDF = function(customSession = null) {
  const session = customSession || getActiveReceivingSession();
  if (!session) {
    showToast('Ni prevzemne pošiljke za izvoz.');
    return;
  }

  if (typeof window.jspdf === 'undefined' || !window.jspdf.jsPDF) {
    showToast('jsPDF knjižnica ni naložena.');
    return;
  }

  const { jsPDF } = window.jspdf;
  const doc = new jsPDF({
    orientation: 'portrait',
    unit: 'mm',
    format: 'a4'
  });

  const pageWidth = 210;
  const pageHeight = 297;
  const margin = 12;
  const contentWidth = pageWidth - (margin * 2); // 186mm

  let currentPage = 1;
  let y = 14;

  function stripSlo(str) {
    if (!str) return '';
    return String(str)
      .replace(/[čČ]/g, c => c === 'č' ? 'c' : 'C')
      .replace(/[šŠ]/g, c => c === 'š' ? 's' : 'S')
      .replace(/[žŽ]/g, c => c === 'ž' ? 'z' : 'Z')
      .replace(/[ćĆ]/g, c => c === 'ć' ? 'c' : 'C')
      .replace(/[đĐ]/g, c => c === 'đ' ? 'dj' : 'Dj');
  }

  // Izračun analitike
  const skus = [...new Set((session.entries || []).map(e => e.sku))];
  const totalBoxes = (session.entries || []).reduce((sum, e) => sum + (Number(e.count) || 0), 0);
  let totalCompletedSets = 0;
  let totalMissingBoxes = 0;

  const completedItems = [];
  const missingItems = [];

  for (const sku of skus) {
    const entry = session.entries.find(e => e.sku === sku);
    const boxTotal = entry.boxTotal || 1;
    const res = calculateSkuCompletion(sku, boxTotal, session.initialStock[sku], session.entries);

    if (res.completedFromOld > 0) {
      totalCompletedSets += res.completedFromOld;
      completedItems.push({
        sku,
        name: entry.name,
        boxTotal,
        completedCount: res.completedFromOld,
        currentMin: res.currentMin
      });
    }

    if (res.missingInBatch.length > 0) {
      const missCount = res.missingInBatch.reduce((sum, m) => sum + m.missingCount, 0);
      totalMissingBoxes += missCount;
      missingItems.push({
        sku,
        name: entry.name,
        boxTotal,
        missing: res.missingInBatch
      });
    }
  }

  function drawHeader() {
    doc.setFillColor(5, 150, 105);
    doc.rect(margin, y, contentWidth, 22, 'F');

    doc.setTextColor(255, 255, 255);
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(13);
    doc.text('ZAPISNIK PREVZEMA POSILJKE BLAGA', margin + 6, y + 8.5);

    doc.setFont('helvetica', 'normal');
    doc.setFontSize(8.2);
    doc.setTextColor(209, 250, 229);
    const startStr = new Date(session.startedAt).toLocaleDateString('sl-SI') + ' ob ' + new Date(session.startedAt).toLocaleTimeString('sl-SI', { hour: '2-digit', minute: '2-digit' });
    const printStr = new Date().toLocaleDateString('sl-SI') + ' ob ' + new Date().toLocaleTimeString('sl-SI', { hour: '2-digit', minute: '2-digit' });
    const whObj = cachedWarehouses.find(w => w.id === session.warehouse);
    const whNameStr = whObj ? whObj.name : (session.warehouse === 'all' ? 'Vsa skladisca' : (session.warehouse || 'Vsa skladisca'));
    doc.text(stripSlo(`Posiljka: "${session.name}" | Skladisce: ${whNameStr} | Zacetek: ${startStr}`), margin + 6, y + 16);

    y += 26;

    doc.setFillColor(240, 253, 244);
    doc.rect(margin, y, contentWidth, 12, 'F');
    doc.setDrawColor(167, 243, 208);
    doc.rect(margin, y, contentWidth, 12, 'S');

    doc.setFont('helvetica', 'bold');
    doc.setFontSize(8.2);
    doc.setTextColor(15, 23, 42);

    const colW = contentWidth / 3;
    doc.text(`SKUPAJ PREVZETIH SKATEL: ${totalBoxes}`, margin + 5, y + 7.5);

    doc.setTextColor(5, 150, 105);
    doc.text(`NOVO KOMPLETIRANO: +${totalCompletedSets} kpl`, margin + colW + 5, y + 7.5);

    if (totalMissingBoxes > 0) {
      doc.setTextColor(217, 119, 6);
      doc.text(`PRIMANKLJAJI: ${totalMissingBoxes} skatel`, margin + (colW * 2) + 5, y + 7.5);
    } else {
      doc.setTextColor(100, 116, 139);
      doc.text(`PRIMANKLJAJI: 0 skatel (Vsi pari)`, margin + (colW * 2) + 5, y + 7.5);
    }

    y += 16;
  }

  function drawFooter(pageNo) {
    doc.setFont('helvetica', 'normal');
    doc.setFontSize(7.5);
    doc.setTextColor(148, 163, 184);
    doc.text(`Skladisce - Zapisnik prevzema posiljke - Stran ${pageNo}`, margin, pageHeight - 6);
  }

  function checkPageBreak(neededHeight) {
    if (y + neededHeight > pageHeight - 20) {
      drawFooter(currentPage);
      doc.addPage();
      currentPage++;
      y = 14;
      return true;
    }
    return false;
  }

  drawHeader();

  // RAZDELEK 1: NOVO KOMPLETIRANE GARNITURE
  doc.setFillColor(16, 185, 129);
  doc.rect(margin, y, contentWidth, 7, 'F');
  doc.setTextColor(255, 255, 255);
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(8.5);
  doc.text('1. NOVO KOMPLETIRANE GARNITURE (Dopolnjeno s predhodno zalogo)', margin + 4, y + 4.8);
  y += 9;

  if (completedItems.length === 0) {
    doc.setFont('helvetica', 'italic');
    doc.setFontSize(8);
    doc.setTextColor(100, 116, 139);
    doc.text('V tej posiljki nobena garnitura ni bila na novo kompletirana iz predhodno manjkajoce zaloge.', margin + 4, y + 4);
    y += 8;
  } else {
    doc.setFillColor(241, 245, 249);
    doc.rect(margin, y, contentWidth, 6, 'F');
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(7.5);
    doc.setTextColor(51, 65, 85);
    doc.text('SIFRA ARTIKLA', margin + 3, y + 4.2);
    doc.text('NAZIV ARTIKLA', margin + 40, y + 4.2);
    doc.text('GARNITURA', margin + 115, y + 4.2);
    doc.text('NOVO KOMPLETIRANO', margin + 145, y + 4.2);
    y += 7;

    for (const c of completedItems) {
      checkPageBreak(8);
      doc.setFillColor(255, 255, 255);
      doc.rect(margin, y, contentWidth, 7, 'F');
      doc.setDrawColor(226, 232, 240);
      doc.line(margin, y + 7, margin + contentWidth, y + 7);

      doc.setFont('helvetica', 'bold');
      doc.setFontSize(7.5);
      doc.setTextColor(15, 23, 42);
      doc.text(stripSlo(c.sku), margin + 3, y + 4.8);

      doc.setFont('helvetica', 'normal');
      const cleanName = stripSlo(c.name);
      doc.text(cleanName.length > 40 ? cleanName.substring(0, 38) + '...' : cleanName, margin + 40, y + 4.8);

      doc.text(`${c.boxTotal}-delni kpl`, margin + 115, y + 4.8);

      doc.setFont('helvetica', 'bold');
      doc.setTextColor(5, 150, 105);
      doc.text(`+${c.completedCount} kpl (skupaj: ${c.currentMin})`, margin + 145, y + 4.8);

      y += 7.5;
    }
  }

  y += 5;
  checkPageBreak(25);

  // RAZDELEK 2: PRIMANJKLJAJI V TEJ POŠILJKI
  doc.setFillColor(217, 119, 6);
  doc.rect(margin, y, contentWidth, 7, 'F');
  doc.setTextColor(255, 255, 255);
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(8.5);
  doc.text('2. PRIMANKLJAJI IN NEPOPOLNE SKATLE V TEJ POSILJKI', margin + 4, y + 4.8);
  y += 9;

  if (missingItems.length === 0) {
    doc.setFont('helvetica', 'italic');
    doc.setFontSize(8);
    doc.setTextColor(100, 116, 139);
    doc.text('Vsi vecskatelni artikli so prispeli v ustreznem stevilu skatel (ni zaznanih primankljajev).', margin + 4, y + 4);
    y += 8;
  } else {
    for (const m of missingItems) {
      const missText = m.missing.map(x => `Manjka ${x.missingCount}x Skatla ${x.boxNum}/${x.boxTotal} (prejeto: ${x.receivedCount} kos)`).join(', ');
      checkPageBreak(8);

      doc.setFillColor(254, 243, 199);
      doc.rect(margin, y, contentWidth, 7, 'F');
      doc.setDrawColor(251, 191, 36);
      doc.rect(margin, y, contentWidth, 7, 'S');

      doc.setFont('helvetica', 'bold');
      doc.setFontSize(7.5);
      doc.setTextColor(180, 83, 9);
      doc.text(stripSlo(m.sku), margin + 3, y + 4.8);

      doc.setTextColor(30, 41, 59);
      const cleanName = stripSlo(m.name);
      doc.text(cleanName.length > 30 ? cleanName.substring(0, 28) + '...' : cleanName, margin + 35, y + 4.8);

      doc.setTextColor(180, 83, 9);
      doc.text(stripSlo(missText), margin + 95, y + 4.8);

      y += 8.5;
    }
  }

  y += 5;
  checkPageBreak(25);

  // RAZDELEK 3: SEZNAM VSEH PREVZETIH POSTAVK
  doc.setFillColor(30, 41, 59);
  doc.rect(margin, y, contentWidth, 7, 'F');
  doc.setTextColor(255, 255, 255);
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(8.5);
  doc.text('3. SEZNAM VSEH PREVZETIH SKATEL PO REGALIH', margin + 4, y + 4.8);
  y += 8;

  doc.setFillColor(241, 245, 249);
  doc.rect(margin, y, contentWidth, 6, 'F');
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(7.2);
  doc.setTextColor(71, 85, 105);
  doc.text('DATUM & CAS', margin + 3, y + 4.2);
  doc.text('SIFRA', margin + 32, y + 4.2);
  doc.text('NAZIV ARTIKLA', margin + 62, y + 4.2);
  doc.text('SKATLA', margin + 125, y + 4.2);
  doc.text('REGAL', margin + 145, y + 4.2);
  doc.text('KOLICINA', margin + 172, y + 4.2);
  y += 7;

  let rowCount = 0;
  for (const entry of (session.entries || [])) {
    checkPageBreak(7);
    rowCount++;
    const isEven = rowCount % 2 === 0;
    if (isEven) {
      doc.setFillColor(248, 250, 252);
      doc.rect(margin, y, contentWidth, 6.5, 'F');
    }
    doc.setDrawColor(226, 232, 240);
    doc.line(margin, y + 6.5, margin + contentWidth, y + 6.5);

    const timeStr = new Date(entry.timestamp).toLocaleDateString('sl-SI') + ' ' + new Date(entry.timestamp).toLocaleTimeString('sl-SI', { hour: '2-digit', minute: '2-digit' });
    doc.setFont('helvetica', 'normal');
    doc.setFontSize(7.2);
    doc.setTextColor(100, 116, 139);
    doc.text(timeStr, margin + 3, y + 4.4);

    doc.setFont('helvetica', 'bold');
    doc.setTextColor(15, 23, 42);
    doc.text(stripSlo(entry.sku), margin + 32, y + 4.4);

    doc.setFont('helvetica', 'normal');
    const cleanName = stripSlo(entry.name);
    doc.text(cleanName.length > 32 ? cleanName.substring(0, 30) + '...' : cleanName, margin + 62, y + 4.4);

    doc.text(`${entry.boxNum}/${entry.boxTotal}`, margin + 125, y + 4.4);
    doc.text(stripSlo(entry.shelf || 'Glavni regal'), margin + 145, y + 4.4);

    doc.setFont('helvetica', 'bold');
    doc.setTextColor(5, 150, 105);
    doc.text(`+${entry.count} kos`, margin + 172, y + 4.4);

    y += 6.5;
  }

  // Podpisno polje na dnu
  checkPageBreak(25);
  y += 8;
  doc.setDrawColor(203, 213, 225);
  doc.line(margin, y, margin + 65, y);
  doc.line(margin + 115, y, margin + contentWidth, y);

  doc.setFont('helvetica', 'normal');
  doc.setFontSize(7.5);
  doc.setTextColor(100, 116, 139);
  doc.text('Podpis skladiscnika / prevzemnika', margin, y + 4.5);
  doc.text('Podpis in zig vodje skladisca', margin + 115, y + 4.5);

  drawFooter(currentPage);

  const cleanFileTitle = stripSlo(session.name).replace(/[^a-zA-Z0-9_-]/g, '_');
  const safeFilename = `Prevzem_${cleanFileTitle}_${new Date().toISOString().slice(0, 10)}.pdf`;
  downloadFile(doc.output('blob'), safeFilename, 'application/pdf');
  showToast(`PDF poročilo "${safeFilename}" je shranjeno.`);
};

/* =========================================================================
   4c. KONSOLIDACIJA ZALOGE (ZDRUŽEVANJE RAZPRŠENIH ŠKATEL)
   ========================================================================= */
window.updateConsolidationBadge = async function() {
  try {
    const badge = document.getElementById('consolidationBadge');
    if (!badge) return;
    const scattered = await DB.getScatteredItems();
    badge.textContent = scattered.length;
    const btn = document.getElementById('btnOpenConsolidation');
    if (btn) {
      if (scattered.length > 0) {
        btn.style.borderColor = 'rgba(168, 85, 247, 0.7)';
        btn.style.boxShadow = '0 0 10px rgba(168, 85, 247, 0.25)';
      } else {
        btn.style.borderColor = 'rgba(168, 85, 247, 0.35)';
        btn.style.boxShadow = 'none';
      }
    }
  } catch (e) {
    console.warn('Napaka pri osveževanju značke konsolidacije:', e);
  }
};

window.openConsolidationModal = async function() {
  const modal = document.getElementById('modalConsolidation');
  if (!modal) return;
  modal.style.display = 'flex';
  await renderConsolidationList();
};

window.closeConsolidationModal = function() {
  const modal = document.getElementById('modalConsolidation');
  if (modal) modal.style.display = 'none';
};

window.renderConsolidationList = async function() {
  const listEl = document.getElementById('consolidationItemsList');
  if (!listEl) return;

  listEl.innerHTML = '<div style="color: var(--text-muted); text-align: center; padding: 20px;">Iščem razpršene artikle in škatle...</div>';

  try {
    const scattered = await DB.getScatteredItems();
    await updateConsolidationBadge();

    if (scattered.length === 0) {
      listEl.innerHTML = `
        <div style="background: rgba(16, 185, 129, 0.1); border: 1px solid rgba(16, 185, 129, 0.3); border-radius: var(--radius-md); padding: 20px 16px; text-align: center;">
          <h4 style="font-size: 1rem; color: #10b981; margin-bottom: 4px;">Zaloga je usklajena</h4>
          <p style="font-size: 0.82rem; color: var(--text-muted); margin: 0;">
            Vsi kompleti imajo škatle zbrane na enotnih regalih.
          </p>
        </div>
      `;
      return;
    }

    listEl.innerHTML = scattered.map(item => {
      return `
        <div style="background: var(--bg-card); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px; display: flex; flex-direction: column; gap: 8px;">
          <div style="display: flex; justify-content: space-between; align-items: flex-start; gap: 8px;">
            <div>
              <div style="font-weight: 700; font-size: 0.95rem; color: #fff;">${escapeHtml(item.name)}</div>
              <div style="font-size: 0.76rem; color: var(--primary); font-family: monospace;"># ${escapeHtml(item.sku)} &bull; ${item.boxTotal > 1 ? `${item.boxTotal}-delni komplet` : 'Enojni artikel'}</div>
            </div>
            <span class="badge" style="background: rgba(168, 85, 247, 0.2); color: #c084fc; border: 1px solid rgba(168, 85, 247, 0.4); font-size: 0.72rem; padding: 4px 8px; border-radius: 4px; white-space: nowrap;">
              Cilj: ${escapeHtml(item.targetShelf)}
            </span>
          </div>

          <!-- Prikaz trenutne razpršenosti -->
          <div style="background: rgba(0,0,0,0.25); border-radius: var(--radius-sm); padding: 8px 10px; font-size: 0.78rem; display: flex; flex-direction: column; gap: 4px;">
            <div style="font-size: 0.7rem; color: var(--text-muted); text-transform: uppercase; font-weight: 700;">Trenutne lokacije škatel:</div>
            ${item.locations.map(loc => `
              <div style="display: flex; justify-content: space-between; align-items: center; padding: 2px 0;">
                <span style="color: ${loc.shelf === item.targetShelf ? '#34d399' : '#f59e0b'};">
                  Škatla ${loc.boxNum}/${loc.boxTotal}: <strong>${escapeHtml(loc.shelf)}</strong>
                </span>
                <span style="font-weight: 700; color: #fff;">${loc.count} kos</span>
              </div>
            `).join('')}
          </div>

          <!-- Nalog za premik za vsako škatlo, ki ni na cilju -->
          ${item.boxesToMove.map(bm => `
            <div style="display: flex; justify-content: space-between; align-items: center; background: rgba(245, 158, 11, 0.08); border: 1px solid rgba(245, 158, 11, 0.3); border-radius: var(--radius-sm); padding: 8px 10px;">
              <div style="font-size: 0.78rem;">
                <span style="color: #fbbf24; font-weight: 700;">Premakni Škatlo ${bm.boxNum}/${bm.boxTotal}:</span>
                <div style="color: var(--text-muted); font-size: 0.74rem;">
                  Iz <strong>${escapeHtml(bm.shelf)}</strong> -> Na <strong>${escapeHtml(item.targetShelf)}</strong> (${bm.count} kos)
                </div>
              </div>
              <button type="button" class="btn-sm" onclick="quickConsolidateMove('${escapeHtml(item.sku)}', ${bm.boxNum}, '${escapeHtml(bm.shelf)}', '${escapeHtml(item.targetShelf)}', ${bm.count})" style="background: #a855f7; color: #fff; border: none; border-radius: 6px; padding: 7px 12px; font-weight: 700; font-size: 0.76rem; cursor: pointer; white-space: nowrap;">
                Združi
              </button>
            </div>
          `).join('')}
        </div>
      `;
    }).join('');
  } catch (err) {
    console.error('Napaka v renderConsolidationList:', err);
    listEl.innerHTML = `<div style="color: #ef4444; padding: 12px;">Napaka pri branju konsolidacije: ${err.message}</div>`;
  }
};

window.quickConsolidateMove = function(sku, boxNum, fromShelf, toShelf, amount) {
  openMoveModal(sku, boxNum, fromShelf, toShelf, amount);
};

window.exportConsolidationPDF = async function() {
  try {
    showToast('Pripravljam nalog za konsolidacijo zaloge...');
    const scattered = await DB.getScatteredItems();

    if (typeof window.jspdf === 'undefined' || !window.jspdf.jsPDF) {
      showToast('jsPDF knjižnica ni na voljo.');
      return;
    }

    const { jsPDF } = window.jspdf;
    const doc = new jsPDF({
      orientation: 'portrait',
      unit: 'mm',
      format: 'a4'
    });

    const pageWidth = 210;
    const pageHeight = 297;
    const margin = 12;
    const contentWidth = pageWidth - (margin * 2);

    let currentPage = 1;
    let y = 14;

    function stripSlo(str) {
      if (!str) return '';
      return String(str)
        .replace(/[čČ]/g, c => c === 'č' ? 'c' : 'C')
        .replace(/[šŠ]/g, c => c === 'š' ? 's' : 'S')
        .replace(/[žŽ]/g, c => c === 'ž' ? 'z' : 'Z')
        .replace(/[ćĆ]/g, c => c === 'ć' ? 'c' : 'C')
        .replace(/[đĐ]/g, c => c === 'đ' ? 'dj' : 'Dj');
    }

    function drawHeader() {
      doc.setFillColor(126, 34, 206); // Vijolična #7e22ce
      doc.rect(margin, y, contentWidth, 22, 'F');

      doc.setTextColor(255, 255, 255);
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(13);
      doc.text('DELOVNI NALOG: KONSOLIDACIJA IN ZDRUZEVANJE ZALOGE', margin + 6, y + 8.5);

      doc.setFont('helvetica', 'normal');
      doc.setFontSize(8.2);
      doc.setTextColor(243, 232, 255);
      const nowStr = new Date().toLocaleDateString('sl-SI') + ' ob ' + new Date().toLocaleTimeString('sl-SI', { hour: '2-digit', minute: '2-digit' });
      doc.text(`Seznam nalog za premik razprsenih skatel na enotne regale | Datum: ${nowStr}`, margin + 6, y + 16);

      y += 26;

      doc.setFillColor(243, 232, 255);
      doc.rect(margin, y, contentWidth, 8, 'F');
      doc.setDrawColor(168, 85, 247);
      doc.rect(margin, y, contentWidth, 8, 'S');

      doc.setFont('helvetica', 'bold');
      doc.setFontSize(8);
      doc.setTextColor(88, 28, 135);

      doc.text('SIFRA / ARTIKEL', margin + 3, y + 5.5);
      doc.text('SKATLA & TRENUTNI REGAL (VIR)', margin + 70, y + 5.5);
      doc.text('CILJNI REGAL (KAM PREMAKNITI)', margin + 130, y + 5.5);
      doc.text('OPRAVLJENO', margin + 168, y + 5.5);

      y += 8.5;
    }

    function drawFooter(pageNo) {
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(7.5);
      doc.setTextColor(148, 163, 184);
      doc.text(`Skladisce - Nalog za konsolidacijo - Stran ${pageNo}`, margin, pageHeight - 6);
    }

    drawHeader();

    if (scattered.length === 0) {
      y += 8;
      doc.setFillColor(240, 253, 244);
      doc.rect(margin, y, contentWidth, 24, 'F');
      doc.setDrawColor(34, 197, 94);
      doc.rect(margin, y, contentWidth, 24, 'S');

      doc.setFont('helvetica', 'bold');
      doc.setFontSize(11);
      doc.setTextColor(22, 101, 52);
      doc.text('VSE SKATLE SO ZE KONSOLIDIRANE', margin + 8, y + 10);

      doc.setFont('helvetica', 'normal');
      doc.setFontSize(8.5);
      doc.setTextColor(21, 128, 61);
      doc.text('Noben artikel nima razprsenih skatel po razlicnih regalih.', margin + 8, y + 18);

      drawFooter(currentPage);
      const fileName = `skladisce-konsolidacija-${getDateStamp()}.pdf`;
      await downloadFile(doc.output('blob'), fileName, 'application/pdf');
      showToast('PDF nalog konsolidacije prenesen.');
      return;
    }

    let rowIndex = 0;
    for (const item of scattered) {
      for (const box of item.boxesToMove) {
        if (y > pageHeight - 20) {
          drawFooter(currentPage);
          doc.addPage();
          currentPage++;
          y = 14;
          drawHeader();
        }

        const bg = (rowIndex % 2 === 0) ? 255 : 250;
        doc.setFillColor(bg, bg, bg);
        doc.rect(margin, y, contentWidth, 12, 'F');
        doc.setDrawColor(226, 232, 240);
        doc.line(margin, y + 12, margin + contentWidth, y + 12);

        doc.setFont('helvetica', 'bold');
        doc.setFontSize(8.5);
        doc.setTextColor(15, 23, 42);
        doc.text(stripSlo(item.sku), margin + 3, y + 5);

        doc.setFont('helvetica', 'normal');
        doc.setFontSize(7.5);
        doc.setTextColor(71, 85, 105);
        const nameTrunc = stripSlo(item.name || '').slice(0, 30);
        doc.text(nameTrunc, margin + 3, y + 9.5);

        // Vir
        doc.setFont('helvetica', 'bold');
        doc.setFontSize(8);
        doc.setTextColor(180, 83, 9);
        const boxLabel = item.boxTotal > 1 ? `Skatla ${box.boxNum}/${box.boxTotal}` : `Artikel`;
        doc.text(`${boxLabel} (${box.count} kos)`, margin + 70, y + 5);
        doc.setFont('helvetica', 'normal');
        doc.setTextColor(71, 85, 105);
        doc.text(`Iz: ${stripSlo(box.shelf)}`, margin + 70, y + 9.5);

        // Cilj
        doc.setFont('helvetica', 'bold');
        doc.setFontSize(8.5);
        doc.setTextColor(22, 101, 52);
        doc.text(`Na: ${stripSlo(item.targetShelf)}`, margin + 130, y + 7);

        // Kvadratek za kljukico
        doc.setDrawColor(100, 116, 139);
        doc.rect(margin + 172, y + 3.5, 5, 5, 'S');

        y += 12;
        rowIndex++;
      }
    }

    drawFooter(currentPage);
    const fileName = `skladisce-konsolidacija-${getDateStamp()}.pdf`;
    await downloadFile(doc.output('blob'), fileName, 'application/pdf');
    showToast('PDF delovni nalog konsolidacije shranjen.');
  } catch (err) {
    console.error('Napaka pri PDF konsolidacije:', err);
    showToast('Napaka pri PDF konsolidacije: ' + err.message);
  }
};

/* =========================================================================
   4d. ARHIV PRETEKLIH PREVZEMNIH POŠILJK
   ========================================================================= */
window.openReceivingArchiveModal = function() {
  const modal = document.getElementById('modalReceivingArchive');
  if (!modal) return;
  modal.style.display = 'flex';
  renderReceivingArchiveList();
};

window.closeReceivingArchiveModal = function() {
  const modal = document.getElementById('modalReceivingArchive');
  if (modal) modal.style.display = 'none';
};

window.renderReceivingArchiveList = function() {
  const listEl = document.getElementById('receivingArchiveList');
  if (!listEl) return;

  try {
    const historyKey = 'skladisce_receiving_history';
    const hist = JSON.parse(localStorage.getItem(historyKey) || '[]');

    if (!Array.isArray(hist) || hist.length === 0) {
      listEl.innerHTML = `
        <div style="background: rgba(255, 255, 255, 0.02); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 20px 16px; text-align: center;">
          <h4 style="font-size: 1rem; color: #fff; margin-bottom: 4px;">Arhiv je prazen</h4>
          <p style="font-size: 0.82rem; color: var(--text-muted); margin: 0;">
            Zaključene prevzemne pošiljke se samodejno shranijo v arhiv.
          </p>
        </div>
      `;
      return;
    }

    listEl.innerHTML = hist.map((session, index) => {
      const started = session.startedAt ? new Date(session.startedAt).toLocaleString('sl-SI', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : 'Neznano';
      const finished = session.finishedAt ? new Date(session.finishedAt).toLocaleString('sl-SI', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : 'Neznano';

      const entries = Array.isArray(session.entries) ? session.entries : [];
      const totalBoxes = session.stats?.totalBoxes || entries.reduce((sum, e) => sum + (Number(e.count) || 0), 0);
      const uniqueSkus = session.stats?.uniqueSkusCount || new Set(entries.map(e => e.sku)).size;
      const completedSets = session.stats?.totalCompletedSets || 0;
      const incompleteCount = session.stats?.incompleteItemsCount || 0;

      return `
        <div style="background: var(--bg-card); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px; display: flex; flex-direction: column; gap: 8px;">
          <div style="display: flex; justify-content: space-between; align-items: flex-start; gap: 8px;">
            <div>
              <div style="font-weight: 700; font-size: 1rem; color: #fff;">${escapeHtml(session.name || 'Neimenovana pošiljka')}</div>
              <div style="font-size: 0.73rem; color: var(--text-muted); margin-top: 2px;">
                Začetek: ${started} &bull; Zaključek: ${finished}
              </div>
            </div>
            <button type="button" class="btn-sm" onclick="deleteHistoricalReceivingSession(${index})" style="background: none; border: 1px solid rgba(239, 68, 68, 0.3); color: #f87171; border-radius: 6px; padding: 4px 8px; font-size: 0.72rem; cursor: pointer;" title="Izbriši iz arhiva">
              Izbriši
            </button>
          </div>

          <!-- Značke statistike -->
          <div style="display: flex; flex-wrap: wrap; gap: 6px; font-size: 0.75rem;">
            <span class="badge" style="background: rgba(56, 189, 248, 0.15); color: #38bdf8; border: 1px solid rgba(56, 189, 248, 0.3); padding: 3px 8px; border-radius: 4px;">
              ${totalBoxes} škatel (${uniqueSkus} artiklov)
            </span>
            <span class="badge" style="background: rgba(16, 185, 129, 0.15); color: #10b981; border: 1px solid rgba(16, 185, 129, 0.3); padding: 3px 8px; border-radius: 4px;">
              ${completedSets} celih kompletov
            </span>
            ${incompleteCount > 0 ? `
              <span class="badge" style="background: rgba(245, 158, 11, 0.15); color: #fbbf24; border: 1px solid rgba(245, 158, 11, 0.3); padding: 3px 8px; border-radius: 4px;">
                ${incompleteCount} primanjkljajev
              </span>
            ` : `
              <span class="badge" style="background: rgba(16, 185, 129, 0.15); color: #34d399; border: 1px solid rgba(16, 185, 129, 0.3); padding: 3px 8px; border-radius: 4px;">
                Brez primanjkljajev
              </span>
            `}
          </div>

          <!-- Gumbi za dejanja -->
          <div style="display: flex; gap: 8px; margin-top: 4px;">
            <button type="button" class="btn-primary" onclick="exportHistoricalReceivingPDF(${index})" style="margin: 0; flex: 2; background: linear-gradient(135deg, #38bdf8, #0284c7); padding: 9px 12px; font-size: 0.8rem;">
              Prenesi PDF zapisnik
            </button>
            <button type="button" class="btn-secondary" onclick="toggleHistoricalDetails(${index})" style="margin: 0; flex: 1; padding: 9px 10px; font-size: 0.8rem;">
              Podrobnosti
            </button>
          </div>

          <!-- Podrobnosti seznama (privzeto skrito) -->
          <div id="histDetails_${index}" style="display: none; margin-top: 6px; border-top: 1px solid rgba(255,255,255,0.06); padding-top: 8px; max-height: 180px; overflow-y: auto; font-size: 0.76rem; flex-direction: column; gap: 4px;">
            <div style="font-weight: 700; color: var(--text-muted); margin-bottom: 2px;">Vse prevzete škatle v tej pošiljki:</div>
            ${entries.slice().reverse().map(e => `
              <div style="display:flex; justify-content:space-between; background: rgba(0,0,0,0.2); padding: 4px 6px; border-radius: 4px;">
                <span>${escapeHtml(e.name)} (#${escapeHtml(e.sku)} &bull; Šk.${e.boxNum}/${e.boxTotal} &bull; ${escapeHtml(e.shelf)})</span>
                <span style="color:#10b981; font-weight:700;">+${e.count}</span>
              </div>
            `).join('')}
          </div>
        </div>
      `;
    }).join('');
  } catch (err) {
    console.error('Napaka v renderReceivingArchiveList:', err);
    listEl.innerHTML = `<div style="color: #ef4444; padding: 12px;">Napaka pri branju arhiva: ${err.message}</div>`;
  }
};

window.toggleHistoricalDetails = function(index) {
  const el = document.getElementById(`histDetails_${index}`);
  if (el) {
    el.style.display = (el.style.display === 'none' || !el.style.display) ? 'flex' : 'none';
  }
};

window.exportHistoricalReceivingPDF = function(index) {
  const historyKey = 'skladisce_receiving_history';
  const hist = JSON.parse(localStorage.getItem(historyKey) || '[]');
  const session = hist[index];
  if (!session) {
    showToast('Pošiljka ni najdena v arhivu.');
    return;
  }
  showToast(`Pripravljam PDF za arhivsko pošiljko "${session.name}"...`);
  exportReceivingPDF(session);
};

window.deleteHistoricalReceivingSession = function(index) {
  const historyKey = 'skladisce_receiving_history';
  const hist = JSON.parse(localStorage.getItem(historyKey) || '[]');
  const session = hist[index];
  if (!session) return;

  if (confirm(`Ali res želite izbrisati arhivski zapis prevzema "${session.name}"?`)) {
    hist.splice(index, 1);
    localStorage.setItem(historyKey, JSON.stringify(hist));
    renderReceivingArchiveList();
    showToast('Zapis izbrisan iz arhiva.');
  }
};

/* =========================================================================
   5. OPTIČNI ČITALNIK ŠKATEL (OCR)
   ========================================================================= */
let currentScannedBox = null;
let currentMultiplier = 1;
let lastUsedShelf = '';
try {
  lastUsedShelf = localStorage.getItem('skladisce_last_shelf') || '';
} catch (e) {}

// Pomožna funkcija za pridobitev vseh znanih šifer artiklov v skladišču (za takojšnjo potrditev OCR)
function getKnownSKUsSet() {
  const skus = new Set();
  if (Array.isArray(cachedItems)) {
    for (const it of cachedItems) {
      if (it && it.sku) skus.add(String(it.sku).trim());
    }
  }
  return skus;
}

/* =========================================================================
   QR KODE REGALOV IN POLIC (TLORIS SKLADIŠČA)
   ========================================================================= */

function isShelfQRCode(str) {
  if (!str || typeof str !== 'string') return false;
  const clean = str.trim();
  if (/^(?:REGAL|LOC|SHELF|SKLADISCE:LOC):/i.test(clean)) return true;
  // Standardni tloris skladišča: R1-A-P1 do R6-B-P3 ali stene SZ-P1, SS-P2, SL1-P3, SL2-P1
  if (/^(?:R[1-6]-[AB]|S[ZSLB][12]?)-P[1-3]$/i.test(clean)) return true;
  if (/^Regal\s+[A-Za-z0-9-]+/i.test(clean)) return true;
  return false;
}

function parseShelfQR(str) {
  let clean = String(str || '').trim();
  clean = clean.replace(/^(?:REGAL|LOC|SHELF|SKLADISCE:LOC):/i, '').trim();

  let friendly = clean;
  const m = clean.match(/^R([1-6])-([AB])-P([1-3])$/i);
  if (m) {
    const row = m[1];
    const side = (m[2].toUpperCase() === 'A') ? 'Stran A (Zgoraj)' : 'Stran B (Spodaj)';
    const shelfMap = { '1': 'Spodnja polica', '2': 'Srednja polica', '3': 'Zgornja polica' };
    friendly = `Regal ${row} • ${side} • ${shelfMap[m[3]] || `Polica ${m[3]}`}`;
  } else {
    const wallMap = {
      'SZ': 'Stena Zgoraj',
      'SS': 'Stena Spodaj',
      'SL1': 'Stena Levo (Nad vrati)',
      'SL2': 'Stena Levo (Pod vrati)'
    };
    const wm = clean.match(/^(SZ|SS|SL1|SL2)-P([1-3])$/i);
    if (wm) {
      const shelfMap = { '1': 'Spodnja polica', '2': 'Srednja polica', '3': 'Zgornja polica' };
      friendly = `${wallMap[wm[1].toUpperCase()]} • ${shelfMap[wm[2]]}`;
    }
  }

  return { code: clean, displayName: friendly };
}

function playShelfChimeBeep() {
  try {
    const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    if (audioCtx.state === 'suspended') audioCtx.resume();
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(587.33, audioCtx.currentTime); // D5
    osc.frequency.setValueAtTime(880, audioCtx.currentTime + 0.08); // A5
    gain.gain.setValueAtTime(0.28, audioCtx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + 0.25);
    osc.connect(gain);
    gain.connect(audioCtx.destination);
    osc.start();
    osc.stop(audioCtx.currentTime + 0.26);
  } catch (e) {}
}

let lastScannedShelfCode = '';
let lastShelfScanTime = 0;

function handleShelfQRScanned(rawVal) {
  const parsed = parseShelfQR(rawVal);
  if (!parsed || !parsed.code) return;

  const now = performance.now();
  if (parsed.code === lastScannedShelfCode && (now - lastShelfScanTime) < 2500) {
    return;
  }
  lastScannedShelfCode = parsed.code;
  lastShelfScanTime = now;

  playShelfChimeBeep();
  if (navigator.vibrate) navigator.vibrate([70, 40, 70]);

  lastUsedShelf = parsed.code;
  try { localStorage.setItem('skladisce_last_shelf', parsed.code); } catch(e){}

  updateActiveShelfPillDisplay();

  const locInput = document.getElementById('boxResultLocationInput');
  if (locInput) {
    locInput.value = parsed.code;
  }

  showToast(`Spremenjen aktivni regal: ${parsed.displayName}`);
}

function updateActiveShelfPillDisplay() {
  const textEl = document.getElementById('currentActiveShelfText');
  if (textEl) {
    if (lastUsedShelf) {
      const parsed = parseShelfQR(lastUsedShelf);
      textEl.textContent = parsed.code + ' (' + parsed.displayName + ')';
    } else {
      textEl.textContent = 'Ni določen (Poskeniraj QR regala)';
    }
  }
}

window.promptManualShelfChange = function() {
  const current = lastUsedShelf || 'R1-A-P1';
  const val = prompt('Vnesite želeni regal in polico (npr. R1-A-P1 ali SZ-P2):', current);
  if (val && val.trim()) {
    handleShelfQRScanned(val.trim());
  }
};

/* =========================================================================
   1-KLIK UPRAVLJANJE S FIZIČNIMI TIPKAMI ZA GLASNOST
   ========================================================================= */

window.handleHardwareVolumeKey = function(direction) {
  const resultCard = document.getElementById('scanResultCard');
  const isCardOpen = resultCard && resultCard.style.display === 'flex';

  if (!isCardOpen) {
    return false;
  }

  if (direction === 'up') {
    // 1-KLIK POTRDITEV PREVZEMA
    executeBoxStockChange('in');
    return true;
  } else if (direction === 'down') {
    // ZAVRŽI / ZAPRI KARTICO
    closeBoxResultCard();
    showToast('Zaznava zavržena.');
    return true;
  }
  return false;
};

// Podpora za fizične tipke ali bližnjice na tipkovnici (ArrowUp / ArrowDown)
window.addEventListener('keydown', (e) => {
  const resultCard = document.getElementById('scanResultCard');
  const isCardOpen = resultCard && resultCard.style.display === 'flex';
  if (!isCardOpen) return;

  if (e.key === 'VolumeUp' || e.code === 'AudioVolumeUp' || e.key === 'ArrowUp') {
    e.preventDefault();
    window.handleHardwareVolumeKey('up');
  } else if (e.key === 'VolumeDown' || e.code === 'AudioVolumeDown' || e.key === 'ArrowDown' || e.key === 'Escape') {
    e.preventDefault();
    window.handleHardwareVolumeKey('down');
  }
});

// Nastavitve za samodejno branje v realnem času
let isAutoScanEnabled = true;
let isScanLoopRunning = false;
let isProcessingFrame = false;
let lastDetectedSKU = null;
let lastDetectedBoxKey = null;
let lastDetectedTime = 0;
let lastCandidateSKU = null;
let lastCandidateBox = null;

// Strojni TextDetector (Android WebView / Chrome)
let nativeTextDetector = null;
if (typeof window !== 'undefined' && 'TextDetector' in window) {
  try {
    nativeTextDetector = new window.TextDetector();
  } catch (e) {
    console.warn('TextDetector ni na voljo:', e);
  }
}

/**
 * Zvočni pisk ob uspešnem zaznavanju škatle
 */
function playSuccessBeep() {
  try {
    const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    if (audioCtx.state === 'suspended') {
      audioCtx.resume();
    }
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(1750, audioCtx.currentTime);
    osc.frequency.setValueAtTime(2400, audioCtx.currentTime + 0.05);
    gain.gain.setValueAtTime(0.25, audioCtx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + 0.12);
    osc.connect(gain);
    gain.connect(audioCtx.destination);
    osc.start();
    osc.stop(audioCtx.currentTime + 0.13);
  } catch (e) {
    // AudioContext blocked or not supported
  }
}

/**
 * Vizualni utrip vizirja ob prepoznavi škatle
 */
function triggerDetectionFlash() {
  const frame = document.getElementById('scanFrameBox');
  const label = document.getElementById('frameLabelText');
  if (frame) {
    frame.classList.add('detected');
    if (label) label.textContent = 'Prepoznano';
    setTimeout(() => {
      frame.classList.remove('detected');
      if (label) label.textContent = 'Usmeri kamero v etiketo';
    }, 1500);
  }
}

/**
 * Obračanje platna za zaznavo narobe obrnjenih (180°) in ležečih (90°) škatel
 */
function rotateCanvas(sourceCanvas, degrees) {
  const rotated = document.createElement('canvas');
  const ctx = rotated.getContext('2d', { willReadFrequently: true });
  if (degrees === 180) {
    rotated.width = sourceCanvas.width;
    rotated.height = sourceCanvas.height;
    ctx.translate(sourceCanvas.width, sourceCanvas.height);
    ctx.rotate(Math.PI);
    ctx.drawImage(sourceCanvas, 0, 0);
  } else if (degrees === 90) {
    rotated.width = sourceCanvas.height;
    rotated.height = sourceCanvas.width;
    ctx.translate(sourceCanvas.height, 0);
    ctx.rotate(Math.PI / 2);
    ctx.drawImage(sourceCanvas, 0, 0);
  } else if (degrees === 270) {
    rotated.width = sourceCanvas.height;
    rotated.height = sourceCanvas.width;
    ctx.translate(0, sourceCanvas.width);
    ctx.rotate((3 * Math.PI) / 2);
    ctx.drawImage(sourceCanvas, 0, 0);
  } else {
    rotated.width = sourceCanvas.width;
    rotated.height = sourceCanvas.height;
    ctx.drawImage(sourceCanvas, 0, 0);
  }
  return rotated;
}

function isCapacitorNative() {
  return typeof window !== 'undefined' &&
         window.Capacitor &&
         typeof window.Capacitor.isNativePlatform === 'function' &&
         window.Capacitor.isNativePlatform();
}

async function triggerNativeMLKitScan() {
  try {
    const plugins = window.Capacitor?.Plugins;
    if (!plugins || !plugins.Camera || !plugins.TextRecognition) {
      showToast('Vtičnik za kamero ni na voljo.');
      return;
    }

    const photo = await plugins.Camera.getPhoto({
      quality: 95,
      allowEditing: false,
      resultType: 'uri',
      source: 'CAMERA'
    });

    if (!photo || !photo.path) return;

    const overlay = document.getElementById('ocrLoadingOverlay');
    const loadingText = document.getElementById('ocrLoadingText');
    if (overlay) overlay.style.display = 'flex';
    if (loadingText) loadingText.textContent = 'Obdelava slike z AI...';

    const result = await plugins.TextRecognition.processImage({
      path: photo.path
    });

    if (overlay) overlay.style.display = 'none';

    const parsed = WarehouseLabelParser.parse(result.text || '', result.blocks || null, {
      knownSkus: getKnownSKUsSet()
    });

    if (parsed && (parsed.articleNumber || parsed.isReceipt)) {
      playSuccessBeep();
      triggerDetectionFlash();
      await handleBoxScanResult(parsed);
    } else {
      showToast('Na posnetku ni bilo prepoznane veljavne skladiščne etikete.');
    }

  } catch (err) {
    const overlay = document.getElementById('ocrLoadingOverlay');
    if (overlay) overlay.style.display = 'none';
    if (err && err.message && (err.message.includes('cancel') || err.message.includes('User cancelled'))) {
      return;
    }
    console.error('OCR napaka:', err);
    showToast('Napaka pri branju kamere: ' + (err.message || err));
  }
}

async function triggerNativeMLKitGallery() {
  try {
    const plugins = window.Capacitor?.Plugins;
    if (!plugins || !plugins.Camera || !plugins.TextRecognition) {
      document.getElementById('ocrFileInput').click();
      return;
    }

    const photo = await plugins.Camera.getPhoto({
      quality: 95,
      allowEditing: false,
      resultType: 'uri',
      source: 'PHOTOS'
    });

    if (!photo || !photo.path) return;

    const overlay = document.getElementById('ocrLoadingOverlay');
    const loadingText = document.getElementById('ocrLoadingText');
    if (overlay) overlay.style.display = 'flex';
    if (loadingText) loadingText.textContent = 'Obdelava slike z AI...';

    const result = await plugins.TextRecognition.processImage({
      path: photo.path
    });

    if (overlay) overlay.style.display = 'none';

    const parsed = WarehouseLabelParser.parse(result.text || '', result.blocks || null, {
      knownSkus: getKnownSKUsSet()
    });

    if (parsed && (parsed.articleNumber || parsed.isReceipt)) {
      playSuccessBeep();
      triggerDetectionFlash();
      await handleBoxScanResult(parsed);
    } else {
      showToast('Na sliki ni bilo prepoznane veljavne skladiščne etikete.');
    }

  } catch (err) {
    const overlay = document.getElementById('ocrLoadingOverlay');
    if (overlay) overlay.style.display = 'none';
  }
}

function setupBoxOCRScanner() {
  const btnCapture = document.getElementById('btnCaptureOCR');
  const btnToggle = document.getElementById('btnToggleCamera');
  const btnPhoto = document.getElementById('btnTriggerCameraPhoto');
  const fileInput = document.getElementById('ocrFileInput');
  const btnManual = document.getElementById('btnManualScan');
  const manualInput = document.getElementById('manualScanInput');
  const btnToggleAuto = document.getElementById('btnToggleAutoScan');
  const autoText = document.getElementById('autoScanStatusText');

  const isNative = isCapacitorNative();
  if (isNative) {
    btnCapture.textContent = 'Ultra-HD zajem';
    btnCapture.style.background = 'linear-gradient(135deg, #0284c7, #0369a1)';
    btnCapture.style.color = '#fff';
    btnCapture.style.borderColor = '#0284c7';
    btnCapture.style.fontSize = '0.85rem';
    btnCapture.style.fontWeight = '700';
    btnCapture.style.padding = '8px 4px';
    btnCapture.addEventListener('click', triggerHighResSensorCapture);
    btnPhoto.addEventListener('click', triggerNativeMLKitGallery);

    const scanFrame = document.getElementById('scanFrameBox');
    if (scanFrame) {
      scanFrame.style.cursor = 'pointer';
      scanFrame.title = 'Dotik za ostrenje (Tap to focus)';
    }
  } else {
    // Spletni način (PWA v brskalniku)
    btnCapture.addEventListener('click', triggerHighResSensorCapture);

    btnPhoto.addEventListener('click', () => {
      fileInput.click();
    });
  }

  // Preklop samodejnega branja (ON / OFF)
  if (btnToggleAuto) {
    btnToggleAuto.addEventListener('click', () => {
      isAutoScanEnabled = !isAutoScanEnabled;
      if (isAutoScanEnabled) {
        btnToggleAuto.classList.remove('disabled');
        btnToggleAuto.classList.add('active');
        if (autoText) autoText.textContent = 'SAMODEJNO BRANJE: VKLOP';
        showToast('Samodejno branje vklopljeno');
      } else {
        btnToggleAuto.classList.remove('active');
        btnToggleAuto.classList.add('disabled');
        if (autoText) autoText.textContent = 'SAMODEJNO BRANJE: PAVZA';
        showToast('Samodejno branje začasno ustavljeno');
      }
    });
  }

  fileInput.addEventListener('change', async (e) => {
    const file = e.target.files[0];
    if (!file) return;

    const img = new Image();
    img.onload = async () => {
      const canvas = document.createElement('canvas');
      const ctx = canvas.getContext('2d', { willReadFrequently: true });
      // Visoka ločljivost do 1400px za natančno prepoznavo malih podrobnosti
      const scale = Math.min(1, 1400 / img.width);
      canvas.width = Math.round(img.width * scale);
      canvas.height = Math.round(img.height * scale);
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      preprocessAndCheckContrast(canvas, ctx);

      await processImageWithOCR(canvas);
    };
    img.src = URL.createObjectURL(file);
    fileInput.value = '';
  });

  // Ročno iskanje po 7 števkah
  btnManual.addEventListener('click', () => {
    const code = manualInput.value.trim();
    if (code) {
      handleManual7DigitLookup(code);
    }
  });

    manualInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') btnManual.click();
  });

  // Gumbi za pomoč pri izdaji po računu
  const btnReceiptTrigger = document.getElementById('btnReceiptDispatchTrigger');
  if (btnReceiptTrigger) {
    btnReceiptTrigger.addEventListener('click', showReceiptDispatchPicker);
  }

  const btnQuickDispatch = document.getElementById('btnQuickDispatch');
  if (btnQuickDispatch) {
    btnQuickDispatch.addEventListener('click', showReceiptDispatchPicker);
  }

  const btnCloseDispatch = document.getElementById('btnCloseReceiptDispatch');
  if (btnCloseDispatch) {
    btnCloseDispatch.addEventListener('click', closeReceiptDispatchModal);
  }

  const btnCancelDispatch = document.getElementById('btnCancelDispatch');
  if (btnCancelDispatch) {
    btnCancelDispatch.addEventListener('click', closeReceiptDispatchModal);
  }

  const btnFinishDispatch = document.getElementById('btnFinishDispatch');
  if (btnFinishDispatch) {
    btnFinishDispatch.addEventListener('click', finishReceiptDispatch);
  }

  const receiptFileInput = document.getElementById('receiptFileInput');
  if (receiptFileInput) {
    receiptFileInput.addEventListener('change', async (e) => {
      const file = e.target.files[0];
      if (!file) return;

      const img = new Image();
      img.onload = async () => {
        const canvas = document.createElement('canvas');
        const ctx = canvas.getContext('2d', { willReadFrequently: true });
        const scale = Math.min(1, 1400 / img.width);
        canvas.width = Math.round(img.width * scale);
        canvas.height = Math.round(img.height * scale);
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
        preprocessAndCheckContrast(canvas, ctx);

        await processImageWithOCR(canvas);
      };
      img.src = URL.createObjectURL(file);
      receiptFileInput.value = '';
    });
  }
}

/**
 * Zagon in ustavitev zanke samodejnega prepoznavanja
 */
function startAutoScanLoop() {
  if (isScanLoopRunning) return;
  isScanLoopRunning = true;
  runAutoScanTick();
}

function stopAutoScanLoop() {
  isScanLoopRunning = false;
}

/**
 * Ena ponovitev v zanki samodejnega branja
 */
async function runAutoScanTick() {
  if (!isScanLoopRunning) return;

  const video = document.getElementById('cameraVideo');
  const resultCard = document.getElementById('scanResultCard');
  const scannerTab = document.getElementById('tab-scanner');

  const isScannerVisible = scannerTab && scannerTab.style.display !== 'none';
  const isVideoReady = video && video.readyState >= 2 && !video.paused;
  const isResultOpen = resultCard && resultCard.style.display === 'flex';

  if (isAutoScanEnabled && isScannerVisible && isVideoReady && !isResultOpen && !isProcessingFrame) {
    const now = performance.now();
    // 1.2s premor po prepoznavi
    if (now - lastDetectedTime > 1200) {
      await processAutoScanFrame(video);
    }
  }

  // Preveri naslednji frame
  if (isScanLoopRunning) {
    setTimeout(runAutoScanTick, 150);
  }
}

/**
 * Pomožna funkcija: Laplacian ostrenje robov na canvasu za reševanje zamegljenih slik ob tresenju roke
 */
function applySharpenFilterToCanvas(canvas, strength = 1.35) {
  try {
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    const imgData = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const src = imgData.data;
    const output = ctx.createImageData(canvas.width, canvas.height);
    const dst = output.data;
    const width = canvas.width;
    const height = canvas.height;
    const k = strength;

    for (let y = 1; y < height - 1; y++) {
      for (let x = 1; x < width - 1; x++) {
        const idx = (y * width + x) * 4;
        for (let c = 0; c < 3; c++) {
          const top    = ((y - 1) * width + x) * 4 + c;
          const bottom = ((y + 1) * width + x) * 4 + c;
          const left   = (y * width + (x - 1)) * 4 + c;
          const right  = (y * width + (x + 1)) * 4 + c;
          const curr   = idx + c;
          const neighborAvg = (src[top] + src[bottom] + src[left] + src[right]) * 0.25;
          const diff = src[curr] - neighborAvg;
          // Coring: zadušitev šuma pri majhnih razlikah, ojačanje robov pri večjih potezah besedila
          if (Math.abs(diff) > 5.0) {
            dst[curr] = Math.min(255, Math.max(0, src[curr] + diff * k * 2.2));
          } else {
            dst[curr] = src[curr];
          }
        }
        dst[idx + 3] = 255;
      }
    }
    ctx.putImageData(output, 0, 0);
  } catch (e) {
    console.warn('Napaka pri ostrenju:', e);
  }
}

/**
 * Analiza sličice z Google ML Kit (več-stopenjski sistem: raw -> auto-sharpen -> center-crop fallback)
 */
async function parseFrameWithOcr(canvas) {
  // 1. Primarno: Nativni Google ML Kit (Android aplikacija)
  if (window.Capacitor?.Plugins?.MLKitDirect) {
    try {
      const base64 = canvas.toDataURL('image/jpeg', 0.85);
      const res = await window.Capacitor.Plugins.MLKitDirect.recognizeBase64({ 
        base64,
        includeBlocks: true
      });
      if (res && res.text && res.text.trim()) {
        const parsed = WarehouseLabelParser.parse(res.text, res.blocks || null, {
          knownSkus: getKnownSKUsSet(),
          imageCenter: { x: canvas.width / 2, y: canvas.height / 2 },
          imageWidth: canvas.width,
          imageHeight: canvas.height
        });
        if (parsed && (parsed.articleNumber || parsed.secondarySku)) {
          return parsed;
        }
      }
      return null;
    } catch (e) {
      console.warn('MLKitDirect napaka:', e);
      return null;
    }
  }

  // 2. Sekundarno: Strojni TextDetector v brskalniku
  if (nativeTextDetector) {
    try {
      const blocks = await nativeTextDetector.detect(canvas);
      if (!blocks || blocks.length === 0) return null;
      const fullText = blocks.map(b => b.rawValue).join('\n');
      return WarehouseLabelParser.parse(fullText, blocks, {
        knownSkus: getKnownSKUsSet(),
        imageCenter: { x: canvas.width / 2, y: canvas.height / 2 },
        imageWidth: canvas.width,
        imageHeight: canvas.height
      });
    } catch (e) {
      return null;
    }
  }

  return null;
}

/**
 * Analiza sličice v realnem času z 3-Frame Burst konsenz točkovanjem in QR detekcijo regala
 */
async function processAutoScanFrame(video) {
  if (isProcessingFrame) return;
  isProcessingFrame = true;

  let parsed = null;
  window.parsed = null;

  const liveBadge = document.getElementById('scanLiveIndicator');

  try {
    // 1. Dvostopenjski zajem: nizkoresolucijska detekcija lokacije teksta + 1:1 polnoresolucijski izrez
    const cropResult = await getHierarchicalTextCrop(video);
    const frameCanvas = cropResult.canvas;
    const hasContent = cropResult.hasContent;
    const frameSharpness = cropResult.sharpness || 5.0;

    // Če ni dovolj kontrasta (premikanje telefona, prazna tla ali lepenka brez črk), takoj preskoči!
    if (!hasContent) {
      isProcessingFrame = false;
      return;
    }

    if (liveBadge) liveBadge.classList.add('analyzing');

    // 0. Preverjanje QR kode regala (če je usmerjeno v polico/regal)
    if (barcodeDetector) {
      try {
        const barcodes = await barcodeDetector.detect(frameCanvas);
        if (barcodes && barcodes.length > 0) {
          for (const b of barcodes) {
            const raw = b.rawValue || '';
            if (isShelfQRCode(raw)) {
              handleShelfQRScanned(raw);
              isProcessingFrame = false;
              return;
            }
          }
        }
      } catch (e) {}
    }

    // 2. Takojšnja analiza sličice z Google ML Kit (brez počasnih rotacij)
    let frameResult = await parseFrameWithOcr(frameCanvas);
    if (!frameResult || !frameResult.articleNumber) {
      isProcessingFrame = false;
      return;
    }

    // 3. Preverjanje zanesljivosti in semantični cross-check:
    // A) Artikel v lokalni bazi
    const knownItem = (Array.isArray(cachedItems) ? cachedItems : []).find(it => it && String(it.sku).trim() === frameResult.articleNumber);
    let semanticMatch = false;

    // B) Semantično ujemanje naziva: preveri ali OCR ime vsebuje ključne besede naziva iz baze
    if (knownItem && frameResult.name && !frameResult.name.startsWith('Artikel ')) {
      const dbWords = (knownItem.name || '').toLowerCase().split(/[\s,.\-\/]+/);
      const ocrWords = frameResult.name.toLowerCase().split(/[\s,.\-\/]+/);
      for (const w of ocrWords) {
        if (w.length >= 4 && dbWords.includes(w)) {
          semanticMatch = true;
          break;
        }
      }
    }

    // C) Matematična EAN Modulo-10 kontrolna vsota
    let validBarcode = false;
    if (frameResult.barcode) {
      validBarcode = WarehouseLabelParser.isValidEanChecksum?.(frameResult.barcode);
    }

    // D) Preverjanje nevarnosti zamenjave barvnih/dimenzijskih variant v bazi (1-mestna razlika v SKU)
    let hasCloseVariantInDB = false;
    if (Array.isArray(cachedItems)) {
      for (const it of cachedItems) {
        const otherSku = String(it.sku || '').trim();
        if (otherSku && otherSku !== frameResult.articleNumber && otherSku.length === frameResult.articleNumber.length) {
          let diffs = 0;
          for (let k = 0; k < otherSku.length; k++) {
            if (otherSku[k] !== frameResult.articleNumber[k]) diffs++;
          }
          if (diffs === 1) {
            hasCloseVariantInDB = true;
            break;
          }
        }
      }
    }

    // Pogoji za takojšnjo potrditev (brez potrebe po drugem kadru):
    // - Semantično potrjen naziv z bazo ALI veljavna matematična EAN črtna koda
    // - Zadostna optična ostrina (>= 4.2)
    // - Ni nevarnosti 1-mestne barvne zamenjave v skladišču
    const isRockSolid = (semanticMatch || validBarcode) && frameSharpness >= 4.2 && !hasCloseVariantInDB;
    let confirmed = isRockSolid;

    // Če ni 100% nesporno, izvedemo hitri 2-kaderni konsenz (temporal burst consensus):
    if (!confirmed) {
      if (lastCandidateSKU === frameResult.articleNumber && lastCandidateBox === (frameResult.boxCurrent || 1)) {
        confirmed = true;
      } else {
        lastCandidateSKU = frameResult.articleNumber;
        lastCandidateBox = frameResult.boxCurrent || 1;
        await new Promise(r => setTimeout(r, 35));
        if (!isScanLoopRunning) {
          isProcessingFrame = false;
          return;
        }
        const nextCrop = await getHierarchicalTextCrop(video);
        if (nextCrop && nextCrop.canvas && nextCrop.hasContent) {
          const nextRes = await parseFrameWithOcr(nextCrop.canvas);
          if (nextRes && nextRes.articleNumber === frameResult.articleNumber && (nextRes.boxCurrent || 1) === (frameResult.boxCurrent || 1)) {
            confirmed = true;
            if ((nextRes.score || 0) + (nextCrop.sharpness || 0) * 2 > (frameResult.score || 0) + frameSharpness * 2) {
              frameResult = nextRes;
            }
          }
        }
      }
    }

    if (!confirmed) {
      isProcessingFrame = false;
      return;
    }

    parsed = frameResult;

    // 4. Če je artikel potrjen, sproži prepoznavo!
    if (parsed && parsed.articleNumber) {
      const now = performance.now();
      const currentBoxKey = `${parsed.articleNumber}_b${parsed.boxCurrent || 1}`;
      // Če gre za natanko isto škatlo (isti SKU in isti box), počakaj kratek premor 1.2s.
      // Če pa gre za DRUGI BOX istega artikla (npr. škatla 2/2 po škatli 1/2) ali nov artikel, se sproži TAKOJ brez čakanja!
      if (currentBoxKey === lastDetectedBoxKey && (now - lastDetectedTime) < 1200) {
        isProcessingFrame = false;
        return;
      }

      lastDetectedBoxKey = currentBoxKey;
      lastDetectedSKU = parsed.articleNumber;
      lastDetectedTime = now;
      lastCandidateSKU = null;
      lastCandidateBox = null;

      // Zvočni in haptični signal
      playSuccessBeep();
      triggerDetectionFlash();

      // Prikaz kartice za potrditev
      await handleBoxScanResult(parsed);
    }

  } catch (err) {
    console.debug('Napaka pri analizi sličice:', err);
  } finally {
    isProcessingFrame = false;
    if (liveBadge) liveBadge.classList.remove('analyzing');
  }
}

let isTorchActive = false;
let currentZoomFactor = 1.0;

function updateCameraCapabilityControls() {
  if (!cameraStream) return;
  const track = cameraStream.getVideoTracks()[0];
  if (!track) return;
  const caps = (typeof track.getCapabilities === 'function') ? track.getCapabilities() : {};

  const btnTorch = document.getElementById('btnToggleTorch');
  if (btnTorch) {
    btnTorch.style.display = caps.torch ? 'inline-flex' : 'none';
  }

  const btnZoom = document.getElementById('btnToggleZoom');
  if (btnZoom) {
    btnZoom.style.display = 'inline-flex';
  }
}

window.toggleCameraTorch = async function() {
  if (!cameraStream) return;
  const track = cameraStream.getVideoTracks()[0];
  if (!track) return;
  isTorchActive = !isTorchActive;
  try {
    await track.applyConstraints({ advanced: [{ torch: isTorchActive }] });
    const btn = document.getElementById('btnToggleTorch');
    if (btn) {
      btn.style.background = isTorchActive ? '#f59e0b' : 'rgba(15,23,42,0.85)';
      btn.style.color = isTorchActive ? '#000' : '#fff';
      btn.innerHTML = isTorchActive ? '🔦 Luč: VKLOP' : '🔦 Luč';
    }
    showToast(isTorchActive ? 'Svetilka vklopljena' : 'Svetilka izklopljena');
  } catch (e) {
    console.warn('Torch napaka:', e);
    isTorchActive = !isTorchActive;
  }
};

window.cycleCameraZoom = async function() {
  const levels = [1.0, 1.5, 2.0];
  const idx = levels.indexOf(currentZoomFactor);
  currentZoomFactor = levels[(idx + 1) % levels.length];

  if (cameraStream) {
    const track = cameraStream.getVideoTracks()[0];
    const caps = (typeof track?.getCapabilities === 'function') ? track.getCapabilities() : {};
    if (caps.zoom) {
      try {
        const val = Math.min(caps.zoom.max || 2.0, Math.max(caps.zoom.min || 1.0, currentZoomFactor));
        await track.applyConstraints({ advanced: [{ zoom: val }] });
      } catch (e) {}
    }
  }

  const btn = document.getElementById('btnToggleZoom');
  if (btn) {
    btn.innerHTML = `🔍 ${currentZoomFactor}x`;
    btn.style.borderColor = (currentZoomFactor > 1.0) ? '#38bdf8' : 'rgba(255,255,255,0.25)';
    btn.style.color = (currentZoomFactor > 1.0) ? '#38bdf8' : '#fff';
  }
  showToast(`Povečava: ${currentZoomFactor}x`);
};

window.triggerTapToFocus = async function(event) {
  if (!cameraStream) return;
  const track = cameraStream.getVideoTracks()[0];
  if (!track) return;

  const container = document.querySelector('.camera-preview-container');
  if (container && event) {
    const rect = container.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const y = event.clientY - rect.top;

    let ring = document.getElementById('cameraFocusRing');
    if (!ring) {
      ring = document.createElement('div');
      ring.id = 'cameraFocusRing';
      ring.className = 'focus-ring';
      container.appendChild(ring);
    }
    ring.style.left = `${x}px`;
    ring.style.top = `${y}px`;
    ring.classList.remove('active');
    void ring.offsetWidth;
    ring.classList.add('active');
  }

  try {
    await track.applyConstraints({
      advanced: [{ focusMode: 'continuous' }]
    });
  } catch (e) {}
};

window.triggerHighResSensorCapture = async function() {
  const overlay = document.getElementById('ocrLoadingOverlay');
  const loadingText = document.getElementById('ocrLoadingText');
  if (overlay) overlay.style.display = 'flex';
  if (loadingText) loadingText.textContent = 'Ultra-HD zajem (12.6 MP Leica)...';

  try {
    const video = document.getElementById('cameraVideo');
    if (!cameraStream) {
      await startCamera();
      if (overlay) overlay.style.display = 'none';
      return;
    }

    const track = cameraStream.getVideoTracks()[0];
    if (window.ImageCapture && track) {
      const ic = new ImageCapture(track);
      // Posnemi neposredno s senzorja z maksimalno ločljivostjo 4096x3072
      const blob = await ic.takePhoto({ imageWidth: 4096, imageHeight: 3072 });
      const imgBitmap = await createImageBitmap(blob);

      // Hierarhični izrez: najdi lokacijo teksta na nizki ločljivosti in izreži iz polne 4K ločljivosti
      const { canvas } = await getHierarchicalTextCrop(imgBitmap);
      await processImageWithOCR(canvas);
      return;
    }

    // Fallback: zajem okvirja iz videa z naravno ločljivostjo
    const { canvas } = await getHierarchicalTextCrop(video);
    await processImageWithOCR(canvas);
  } catch (err) {
    console.warn('ImageCapture napaka, fallback na video:', err);
    const video = document.getElementById('cameraVideo');
    const { canvas } = await getHierarchicalTextCrop(video);
    await processImageWithOCR(canvas);
  } finally {
    if (overlay) overlay.style.display = 'none';
  }
};

async function startCamera() {
  const video = document.getElementById('cameraVideo');

  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: {
        facingMode: { ideal: 'environment' },
        width: { ideal: 3840, min: 1920 },
        height: { ideal: 2160, min: 1080 },
        focusMode: { ideal: 'continuous' },
        exposureMode: { ideal: 'continuous' },
        whiteBalanceMode: { ideal: 'continuous' }
      }
    });

    cameraStream = stream;
    video.srcObject = stream;

    video.onloadedmetadata = () => {
      video.play().catch(() => {});
      startAutoScanLoop();
      updateCameraCapabilityControls();
    };
  } catch (err) {
    console.warn('Kamere ni bilo mogoče zagnati z naprednimi nastavitvami, poskušam standardno:', err);
    try {
      const fallbackStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: 'environment' } }
      });
      cameraStream = fallbackStream;
      video.srcObject = fallbackStream;
      video.onloadedmetadata = () => {
        video.play().catch(() => {});
        startAutoScanLoop();
        updateCameraCapabilityControls();
      };
    } catch (e) {
      console.warn('Kamere ni bilo mogoče zagnati:', e);
      showToast('Dostop do kamere ni na voljo. Uporabite gumb za fotografijo spodaj.');
    }
  }
}

function stopCamera() {
  stopAutoScanLoop();
  if (cameraStream) {
    cameraStream.getTracks().forEach(track => track.stop());
    cameraStream = null;
    const video = document.getElementById('cameraVideo');
    if (video) video.srcObject = null;
  }
}

/**
 * DVOSTOPENJSKI HIERARHIČNI ZAJEM (Coarse-to-Fine Text Detection & Recognition):
 * 1. Nizkoresolucijski prehod (Coarse frame na 480px širine):
 *    - Poišče položaj BESEDILA (črn tisk na rjavi lepenki ALI beli etiketi)
 *    - Primarno uporabi nativni ML Kit detektor blokov, sekundarno gradientno gostoto potez črk
 * 2. 1:1 polnoresolucijski izrez (Fine crop):
 *    - Iz izvornega video toka izreže samo natančno območje besedila v 1:1 naravnih pikslih senzorja
 *    - Odreže nepomembno ozadje (tla, regali, stropne luči), ohrani 100% optično ostrino številk
 */
async function getHierarchicalTextCrop(source) {
  const fullW = source.videoWidth || source.naturalWidth || source.width || 1920;
  const fullH = source.videoHeight || source.naturalHeight || source.height || 1080;

  // 1. KORAK: Priprava nizkoresolucijskega okvirja (Coarse frame na 720px širine)
  const lowScale = Math.min(1.0, 720 / fullW);
  const lowW = Math.round(fullW * lowScale);
  const lowH = Math.round(fullH * lowScale);

  const lowCanvas = document.createElement('canvas');
  lowCanvas.width = lowW;
  lowCanvas.height = lowH;
  const lowCtx = lowCanvas.getContext('2d', { willReadFrequently: true });
  lowCtx.drawImage(source, 0, 0, lowW, lowH);

  let targetBox = null;

  // A) Namenska strojna ML detekcija tekstovnih blokov (Google ML Kit na ravni nativne Jave z minimalno zakasnitvijo)
  if (window.Capacitor?.Plugins?.MLKitDirect?.detectTextBoundsML) {
    try {
      const lowB64 = lowCanvas.toDataURL('image/jpeg', 0.85);
      const mlRes = await window.Capacitor.Plugins.MLKitDirect.detectTextBoundsML({
        base64: lowB64,
        fullW: fullW,
        fullH: fullH
      });
      if (mlRes && mlRes.hasText && mlRes.targetBox) {
        targetBox = mlRes.targetBox;
        updateLiveTextTracker(targetBox.normLeft, targetBox.normTop, targetBox.normWidth, targetBox.normHeight);
      }
    } catch (e) {
      console.warn('detectTextBoundsML opozorilo:', e);
    }
  } else if (window.Capacitor?.Plugins?.MLKitDirect) {
    try {
      const lowB64 = lowCanvas.toDataURL('image/jpeg', 0.85);
      const lowRes = await window.Capacitor.Plugins.MLKitDirect.recognizeBase64({ base64: lowB64 });
      if (lowRes && Array.isArray(lowRes.blocks) && lowRes.blocks.length > 0) {
        targetBox = extractBoundingBoxFromTextBlocks(lowRes.blocks, lowScale, fullW, fullH);
        if (targetBox && targetBox.normLeft != null) {
          updateLiveTextTracker(targetBox.normLeft, targetBox.normTop, targetBox.normWidth, targetBox.normHeight);
        }
      }
    } catch (e) {
      console.warn('Coarse MLKit text detection opozorilo:', e);
    }
  } else if (nativeTextDetector) {
    try {
      const blocks = await nativeTextDetector.detect(lowCanvas);
      if (blocks && blocks.length > 0) {
        targetBox = extractBoundingBoxFromTextBlocks(blocks, lowScale, fullW, fullH);
        if (targetBox && targetBox.normLeft != null) {
          updateLiveTextTracker(targetBox.normLeft, targetBox.normTop, targetBox.normWidth, targetBox.normHeight);
        }
      }
    } catch (e) {}
  }

  // B) Računalniški vid: gradientna detekcija potez besedila (fallback brez vezave na barvo)
  if (!targetBox) {
    targetBox = detectTextClusterBounds(lowCanvas, lowCtx, lowScale, fullW, fullH);
    if (targetBox && targetBox.normLeft != null) {
      updateLiveTextTracker(targetBox.normLeft, targetBox.normTop, targetBox.normWidth, targetBox.normHeight);
    }
  }

  // C) Če na kadru ni zaznanega besedila, skrijemo sledilnik in uporabimo varnostni sredinski vizir
  if (!targetBox) {
    hideLiveTextTracker();
    const zoomFactor = currentZoomFactor || 1.0;
    const baseW = Math.round(fullW * 0.78 / zoomFactor);
    const baseH = Math.round(fullH * 0.52 / zoomFactor);
    const cropX = Math.round((fullW - baseW) / 2);
    const cropY = Math.round((fullH - baseH) / 2);
    targetBox = { cropX, cropY, cropW: baseW, cropH: baseH };
  }

  // 2. KORAK: Izrez iz POLNE izvorne ločljivosti senzorja (1:1 optični piksli brez degradacije)
  const highCanvas = document.createElement('canvas');
  highCanvas.width = targetBox.cropW;
  highCanvas.height = targetBox.cropH;
  const highCtx = highCanvas.getContext('2d', { willReadFrequently: true });

  const angle = targetBox.angleDegrees || 0;
  if (Math.abs(angle) >= 2.5 && Math.abs(angle) <= 45.0) {
    // Poravnava naklona (deskewing) - rotiramo platno nazaj v vodoravni položaj
    highCtx.save();
    highCtx.translate(targetBox.cropW / 2, targetBox.cropH / 2);
    highCtx.rotate(-angle * Math.PI / 180);
    highCtx.drawImage(
      source,
      targetBox.cropX, targetBox.cropY, targetBox.cropW, targetBox.cropH,
      -targetBox.cropW / 2, -targetBox.cropH / 2, targetBox.cropW, targetBox.cropH
    );
    highCtx.restore();
  } else {
    highCtx.drawImage(source, targetBox.cropX, targetBox.cropY, targetBox.cropW, targetBox.cropH, 0, 0, targetBox.cropW, targetBox.cropH);
  }

  // Lokalna prilagodljiva normalizacija kontrasta in odprava odsevov folije
  applyAdaptiveContrastNormalization(highCanvas, highCtx);

  const check = preprocessAndCheckContrast(highCanvas, highCtx);

  return { canvas: highCanvas, hasContent: check.hasContent, sharpness: check.sharpness, targetBox };
}

/**
 * Posodobi vizualni HUD okvirček na zaslonu, ki v živo sledi besedilu
 */
function updateLiveTextTracker(normLeft, normTop, normWidth, normHeight) {
  try {
    const tracker = document.getElementById('mlLiveTextTracker');
    if (!tracker) return;
    tracker.style.display = 'block';
    tracker.style.left = Math.max(2, Math.min(95, normLeft * 100)).toFixed(1) + '%';
    tracker.style.top = Math.max(2, Math.min(95, normTop * 100)).toFixed(1) + '%';
    tracker.style.width = Math.max(8, Math.min(96, normWidth * 100)).toFixed(1) + '%';
    tracker.style.height = Math.max(6, Math.min(96, normHeight * 100)).toFixed(1) + '%';
    tracker.style.opacity = '1';
  } catch (e) {}
}

function hideLiveTextTracker() {
  try {
    const tracker = document.getElementById('mlLiveTextTracker');
    if (tracker && tracker.style.opacity !== '0') {
      tracker.style.opacity = '0';
      setTimeout(() => {
        if (tracker && tracker.style.opacity === '0') {
          tracker.style.display = 'none';
        }
      }, 150);
    }
  } catch (e) {}
}

/**
 * Sinhroni fallback za zajem izreza (uporablja gradientno zaznavo teksta)
 */
function extractHierarchicalLabelCrop(source) {
  const fullW = source.videoWidth || source.naturalWidth || source.width || 1920;
  const fullH = source.videoHeight || source.naturalHeight || source.height || 1080;

  const lowScale = Math.min(1.0, 480 / fullW);
  const lowW = Math.round(fullW * lowScale);
  const lowH = Math.round(fullH * lowScale);

  const lowCanvas = document.createElement('canvas');
  lowCanvas.width = lowW;
  lowCanvas.height = lowH;
  const lowCtx = lowCanvas.getContext('2d', { willReadFrequently: true });
  lowCtx.drawImage(source, 0, 0, lowW, lowH);

  let targetBox = detectTextClusterBounds(lowCanvas, lowCtx, lowScale, fullW, fullH);
  if (!targetBox) {
    const zoomFactor = currentZoomFactor || 1.0;
    const baseW = Math.round(fullW * 0.78 / zoomFactor);
    const baseH = Math.round(fullH * 0.52 / zoomFactor);
    const cropX = Math.round((fullW - baseW) / 2);
    const cropY = Math.round((fullH - baseH) / 2);
    targetBox = { cropX, cropY, cropW: baseW, cropH: baseH };
  }

  const highCanvas = document.createElement('canvas');
  highCanvas.width = targetBox.cropW;
  highCanvas.height = targetBox.cropH;
  const highCtx = highCanvas.getContext('2d', { willReadFrequently: true });

  const angle = targetBox.angleDegrees || 0;
  if (Math.abs(angle) >= 2.5 && Math.abs(angle) <= 45.0) {
    highCtx.save();
    highCtx.translate(targetBox.cropW / 2, targetBox.cropH / 2);
    highCtx.rotate(-angle * Math.PI / 180);
    highCtx.drawImage(
      source,
      targetBox.cropX, targetBox.cropY, targetBox.cropW, targetBox.cropH,
      -targetBox.cropW / 2, -targetBox.cropH / 2, targetBox.cropW, targetBox.cropH
    );
    highCtx.restore();
  } else {
    highCtx.drawImage(source, targetBox.cropX, targetBox.cropY, targetBox.cropW, targetBox.cropH, 0, 0, targetBox.cropW, targetBox.cropH);
  }

  applyAdaptiveContrastNormalization(highCanvas, highCtx);

  const check = preprocessAndCheckContrast(highCanvas, highCtx);
  return { canvas: highCanvas, hasContent: check.hasContent, sharpness: check.sharpness, targetBox };
}

/**
 * Združi zaznane tekstovne bloke v skupni bounding box z 18% varnostnim robom
 */
function extractBoundingBoxFromTextBlocks(blocks, scale, fullW, fullH) {
  try {
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    let count = 0;
    let angleSum = 0;
    let angleCount = 0;

    for (const blk of blocks) {
      const b = blk.boundingBox || blk;
      const l = b.left ?? b.x ?? 0;
      const t = b.top ?? b.y ?? 0;
      const w = b.width ?? (b.right != null ? b.right - l : 0);
      const h = b.height ?? (b.bottom != null ? b.bottom - t : 0);
      const txt = (blk.rawValue || blk.text || '').trim();

      if (txt.length >= 2 && /[a-zA-Z0-9]/.test(txt)) {
        minX = Math.min(minX, l);
        minY = Math.min(minY, t);
        maxX = Math.max(maxX, l + w);
        maxY = Math.max(maxY, t + h);
        count++;

        const corners = blk.cornerPoints || blk.corners;
        if (corners && corners.length >= 2) {
          const dx = corners[1].x - corners[0].x;
          const dy = corners[1].y - corners[0].y;
          if (Math.abs(dx) > 15) {
            const ang = Math.atan2(dy, dx) * 180 / Math.PI;
            if (Math.abs(ang) <= 45.0) {
              angleSum += ang;
              angleCount++;
            }
          }
        }
      }
    }

    if (count > 0 && minX < maxX && minY < maxY) {
      const bw = maxX - minX;
      const bh = maxY - minY;
      const padX = Math.max(bw * 0.28, 40 * scale);
      const padY = Math.max(bh * 0.50, 90 * scale);

      const cropX = Math.max(0, Math.round((minX - padX) / scale));
      const cropY = Math.max(0, Math.round((minY - padY) / scale));
      const cropW = Math.min(fullW - cropX, Math.round((bw + 2 * padX) / scale));
      const cropH = Math.min(fullH - cropY, Math.round((bh + 2 * padY) / scale));

      const normLeft = Math.max(0, (minX - padX) / (fullW * scale));
      const normTop = Math.max(0, (minY - padY) / (fullH * scale));
      const normWidth = Math.min(1.0 - normLeft, (bw + 2 * padX) / (fullW * scale));
      const normHeight = Math.min(1.0 - normTop, (bh + 2 * padY) / (fullH * scale));
      const dominantAngle = angleCount > 0 ? (angleSum / angleCount) : 0;

      if (cropW > 100 && cropH > 60) {
        return { cropX, cropY, cropW, cropH, normLeft, normTop, normWidth, normHeight, angleDegrees: dominantAngle };
      }
    }
  } catch (e) {}
  return null;
}

/**
 * Detekcija gostote potez besedila na rjavi lepenki ali beli etiketi (brez vezanosti na barvo)
 */
function detectTextClusterBounds(canvas, ctx, scale, fullW, fullH) {
  try {
    const w = canvas.width;
    const h = canvas.height;
    const imgData = ctx.getImageData(0, 0, w, h);
    const data = imgData.data;

    const cellW = 16;
    const cellH = 12;
    const gridCols = Math.floor(w / cellW);
    const gridRows = Math.floor(h / cellH);
    const textGrid = new Uint8Array(gridCols * gridRows);

    // Vzorčenje gradientov potez črk
    for (let gy = 0; gy < gridRows; gy++) {
      const y0 = gy * cellH;
      for (let gx = 0; gx < gridCols; gx++) {
        const x0 = gx * cellW;
        let strokeCount = 0;

        for (let y = y0 + 1; y < y0 + cellH - 1; y += 2) {
          for (let x = x0 + 2; x < x0 + cellW - 2; x += 2) {
            const idxL = (y * w + (x - 2)) * 4;
            const idxR = (y * w + (x + 2)) * 4;
            const lumL = (data[idxL] * 2 + data[idxL + 1] * 5 + data[idxL + 2]) >> 3;
            const lumR = (data[idxR] * 2 + data[idxR + 1] * 5 + data[idxR + 2]) >> 3;
            const diff = Math.abs(lumL - lumR);
            if (diff > 26) {
              strokeCount++;
            }
          }
        }

        if (strokeCount >= 3) {
          textGrid[gy * gridCols + gx] = 1;
        }
      }
    }

    let minGx = gridCols, maxGx = 0, minGy = gridRows, maxGy = 0;
    let textCellsCount = 0;

    for (let gy = 0; gy < gridRows; gy++) {
      for (let gx = 0; gx < gridCols; gx++) {
        if (textGrid[gy * gridCols + gx] === 1) {
          const hasNeighbor = 
            (gx > 0 && textGrid[gy * gridCols + gx - 1] === 1) ||
            (gx < gridCols - 1 && textGrid[gy * gridCols + gx + 1] === 1) ||
            (gy > 0 && textGrid[(gy - 1) * gridCols + gx] === 1) ||
            (gy < gridRows - 1 && textGrid[(gy + 1) * gridCols + gx] === 1);

          if (hasNeighbor) {
            textCellsCount++;
            if (gx < minGx) minGx = gx;
            if (gx > maxGx) maxGx = gx;
            if (gy < minGy) minGy = gy;
            if (gy > maxGy) maxGy = gy;
          }
        }
      }
    }

    const totalCells = gridCols * gridRows;
    const ratio = textCellsCount / totalCells;

    if (textCellsCount >= 4 && ratio <= 0.85 && maxGx >= minGx + 1 && maxGy >= minGy) {
      const minX = minGx * cellW;
      const maxX = (maxGx + 1) * cellW;
      const minY = minGy * cellH;
      const maxY = (maxGy + 1) * cellH;

      const bw = maxX - minX;
      const bh = maxY - minY;
      const padX = Math.max(bw * 0.28, 40 * scale);
      const padY = Math.max(bh * 0.50, 90 * scale);

      const cropX = Math.max(0, Math.round((minX - padX) / scale));
      const cropY = Math.max(0, Math.round((minY - padY) / scale));
      const cropW = Math.min(fullW - cropX, Math.round((bw + 2 * padX) / scale));
      const cropH = Math.min(fullH - cropY, Math.round((bh + 2 * padY) / scale));

      if (cropW > 120 && cropH > 80) {
        const normLeft = Math.max(0, (minX - padX) / w);
        const normTop = Math.max(0, (minY - padY) / h);
        const normWidth = Math.min(1.0 - normLeft, (bw + 2 * padX) / w);
        const normHeight = Math.min(1.0 - normTop, (bh + 2 * padY) / h);
        return { cropX, cropY, cropW, cropH, normLeft, normTop, normWidth, normHeight };
      }
    }
  } catch (e) {}
  return null;
}

/**
 * Izreže območje vizirja za škatlo v POLNI IZVORNI LOČLJIVOSTI senzorja
 */
function getCroppedCanvasFromVideo(video) {
  return extractHierarchicalLabelCrop(video);
}

/**
 * Prilagodljiva lokalna normalizacija kontrasta in zmanjševanje bleščanja na izrezu etikete
 * (odpravi odseve stropnih luči na prozorni zaščitni foliji in zviša kontrast črnega tiska na rjavi lepenki)
 */
function applyAdaptiveContrastNormalization(canvas, ctx) {
  try {
    const w = canvas.width;
    const h = canvas.height;
    if (w < 40 || h < 40) return;
    const imgData = ctx.getImageData(0, 0, w, h);
    const d = imgData.data;
    const len = d.length;

    // 1. Hitri histogram za določitev 2. in 98. percentila svetlosti
    const hist = new Uint32Array(256);
    for (let i = 0; i < len; i += 4) {
      const y = (d[i] * 77 + d[i + 1] * 150 + d[i + 2] * 29) >> 8;
      hist[y]++;
    }

    const totalPixels = w * h;
    const lowCut = Math.floor(totalPixels * 0.02);
    const highCut = Math.floor(totalPixels * 0.98);

    let acc = 0;
    let minLum = 0;
    for (let i = 0; i < 256; i++) {
      acc += hist[i];
      if (acc >= lowCut) { minLum = i; break; }
    }

    acc = 0;
    let maxLum = 255;
    for (let i = 255; i >= 0; i--) {
      acc += hist[i];
      if (acc >= (totalPixels - highCut)) { maxLum = i; break; }
    }

    const range = maxLum - minLum;
    if (range >= 180 || range <= 15) return;

    let sumLum = 0;
    for (let i = 0; i < 256; i++) {
      sumLum += i * hist[i];
    }
    const avgLum = sumLum / (totalPixels || 1);

    // Če je slika temna (avgLum < 105), uporabimo gama ekspanzijo (dvig senc in lepenke)
    const gamma = (avgLum < 105) ? 0.70 : 1.0;

    // LUT tabela za raztegovanje dinamike z gama korekcijo
    const lut = new Uint8Array(256);
    for (let i = 0; i < 256; i++) {
      if (i <= minLum) lut[i] = 0;
      else if (i >= maxLum) lut[i] = 255;
      else {
        const norm = (i - minLum) / range;
        lut[i] = (gamma === 1.0) ? Math.round(norm * 255) : Math.round(Math.pow(norm, gamma) * 255);
      }
    }

    for (let i = 0; i < len; i += 4) {
      d[i] = lut[d[i]];
      d[i + 1] = lut[d[i + 1]];
      d[i + 2] = lut[d[i + 2]];
    }

    ctx.putImageData(imgData, 0, 0);
  } catch (e) {
    console.warn('applyAdaptiveContrastNormalization opozorilo:', e);
  }
}

/**
 * Hiter pregled prisotnosti vsebine in ostrine v kadru (preprečevanje zamegljenih posnetkov med zamahom)
 */
function preprocessAndCheckContrast(canvas, ctx) {
  try {
    const w = canvas.width;
    const h = canvas.height;
    if (w < 60 || h < 60) return { hasContent: true, sharpness: 10.0 };

    // Hiter vzorec osrednjega območja
    const sampleW = Math.min(160, Math.floor(w * 0.45));
    const sampleH = Math.min(100, Math.floor(h * 0.45));
    const startX = Math.floor((w - sampleW) / 2);
    const startY = Math.floor((h - sampleH) / 2);
    const data = ctx.getImageData(startX, startY, sampleW, sampleH).data;

    let edgeDiff = 0;
    let count = 0;
    for (let i = 0; i < data.length - 8; i += 8) {
      const l1 = (data[i] * 77 + data[i + 1] * 150 + data[i + 2] * 29) >> 8;
      const l2 = (data[i + 4] * 77 + data[i + 5] * 150 + data[i + 6] * 29) >> 8;
      edgeDiff += Math.abs(l1 - l2);
      count++;
    }
    const avgEnergy = edgeDiff / (count || 1);
    // Če je energija robov premajhna (< 1.8), je slika prazna lepenka/tla ali povsem zamegljena med zamahom
    return { hasContent: avgEnergy >= 1.8, sharpness: avgEnergy };
  } catch (e) {
    return { hasContent: true, sharpness: 5.0 };
  }
}

/**
 * Obdelava zajete slike prek Google ML Kit
 */
async function processImageWithOCR(canvas) {
  const overlay = document.getElementById('ocrLoadingOverlay');
  const loadingText = document.getElementById('ocrLoadingText');
  if (overlay) overlay.style.display = 'flex';
  if (loadingText) loadingText.textContent = 'Obdelava slike z Google ML Kit...';

  try {
    const parsed = await parseFrameWithOcr(canvas);

    if (parsed && (parsed.articleNumber || parsed.isReceipt)) {
      playSuccessBeep();
      triggerDetectionFlash();
      await handleBoxScanResult(parsed);
    } else {
      showToast('Na sliki ni bilo zaznane veljavne skladiščne etikete.');
    }

  } catch (err) {
    console.error('Napaka pri OCR prepoznavi:', err);
    showToast('Napaka pri branju slike: ' + (err.message || err));
  } finally {
    if (overlay) overlay.style.display = 'none';
  }
}

/**
 * Prikaz in obdelava rezultatov prepoznane škatle
 */
async function handleBoxScanResult(parsed) {
  if (navigator.vibrate) navigator.vibrate([70, 40, 70]);

  // 1. Če je prepoznan račun kupca, zaženi asistenta za vodenje po regalih
  if (parsed && parsed.isReceipt) {
    await openReceiptDispatchModal(parsed);
    return;
  }

  currentScannedBox = parsed;
  currentMultiplier = 1;

  const resultCard = document.getElementById('scanResultCard');
  if (resultCard) {
    resultCard.style.display = 'flex';
    resultCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  const sku = parsed.articleNumber;
  let existingItem = null;
  if (sku) {
    existingItem = await DB.getItemBySKU(sku);
  }

  renderBoxResultCard(existingItem);
}
window.handleBoxScanResult = handleBoxScanResult;

/* =========================================================================
   POMOČ PRI IZDAJI BLAGA PO RAČUNU (VODENJE PO REGALIH)
   ========================================================================= */

let currentDispatchData = null;
let currentDispatchItems = [];

window.showReceiptDispatchPicker = async function showReceiptDispatchPicker() {
  if (DB.ensureReceiptDemoItems) {
    await DB.ensureReceiptDemoItems();
  }

  const sampleReceiptData = {
    isReceipt: true,
    receiptNo: '0519-101-264866',
    receiptDate: '09.10.26 11:04',
    barcode: '00105190910261010370',
    items: [
      { sku: '3618930', name: 'Predalnik LIMFJORDEN 4 predali bela', qty: 1, price: '95.00' },
      { sku: '3670619', name: 'DIVERSES', qty: 1, price: '315.00' }
    ]
  };

  if (isCapacitorNative()) {
    const choice = confirm("Želite poskenirati račun kupca s kamero?\n\n[V redu (OK)] = Slikaj račun s kamero\n[Prekliči (Cancel)] = Naloži sliko iz galerije ali uporabi vzorčni račun");
    if (choice) {
      await triggerNativeMLKitScan();
    } else {
      const useSample = confirm("Želite odpreti vzorčni račun JYSK (LIMFJORDEN + DIVERSES)?\n\n[V redu (OK)] = Odpri vzorčni račun\n[Prekliči (Cancel)] = Odpri galerijo slik");
      if (useSample) {
        await openReceiptDispatchModal(sampleReceiptData);
      } else {
        await triggerNativeMLKitGallery();
      }
    }
  } else {
    const useSample = confirm("Odprem pomoč pri izdaji z vzorčnim računom JYSK (Predalnik LIMFJORDEN + DIVERSES)?\n\n[V redu (OK)] = Takojšnji preizkus z vzorčnim računom\n[Prekliči (Cancel)] = Naloži svojo sliko računa");
    if (useSample) {
      await openReceiptDispatchModal(sampleReceiptData);
    } else {
      const fi = document.getElementById('receiptFileInput') || document.getElementById('ocrFileInput');
      if (fi) fi.click();
    }
  }
}

async function openReceiptDispatchModal(receiptData) {
  if (DB.ensureReceiptDemoItems) {
    await DB.ensureReceiptDemoItems();
  }

  currentDispatchData = receiptData;
  const modal = document.getElementById('modalReceiptDispatch');
  if (!modal) return;

  const receiptNoEl = document.getElementById('dispatchReceiptNo');
  const receiptDateEl = document.getElementById('dispatchReceiptDate');
  const barcodeRow = document.getElementById('dispatchBarcodeRow');
  const barcodeEl = document.getElementById('dispatchReceiptBarcode');

  if (receiptNoEl) receiptNoEl.textContent = receiptData.receiptNo || 'Račun kupca';
  if (receiptDateEl) receiptDateEl.textContent = receiptData.receiptDate || new Date().toLocaleDateString('sl-SI');
  if (barcodeRow && barcodeEl) {
    if (receiptData.barcode) {
      barcodeRow.style.display = 'block';
      barcodeEl.textContent = receiptData.barcode;
    } else {
      barcodeRow.style.display = 'none';
    }
  }

  currentDispatchItems = [];
  const rawItems = receiptData.items || [];

  for (let i = 0; i < rawItems.length; i++) {
    const rawIt = rawItems[i];
    const dbItem = await DB.getItemBySKU(rawIt.sku);
    currentDispatchItems.push({
      index: i,
      sku: rawIt.sku,
      name: (dbItem && dbItem.name) ? dbItem.name : rawIt.name,
      orderedQty: Number(rawIt.qty) || 1,
      price: rawIt.price || '',
      dbItem: dbItem,
      isPicked: false
    });
  }

  renderDispatchRouteList();
  updateDispatchProgress();

  modal.classList.add('active');
  modal.style.display = 'flex';
  playSuccessBeep();
}

function renderDispatchRouteList() {
  const container = document.getElementById('dispatchRouteBody');
  if (!container) return;

  if (currentDispatchItems.length === 0) {
    container.innerHTML = `
      <div style="text-align: center; padding: 24px; color: var(--text-muted);">
        Na računu ni bilo zaznanih artiklov za izdajo.
      </div>
    `;
    return;
  }

  let html = '';

  currentDispatchItems.forEach((item, idx) => {
    const dbIt = item.dbItem;
    const isPicked = item.isPicked;

    let shelfBadges = '';
    let multiBoxSection = '';
    let stockSummary = '';

    if (dbIt) {
      const stats = DB.getBoxStats(dbIt);
      const boxTotal = stats.boxTotal;

      if (boxTotal > 1) {
        const badgeList = [];
        stats.breakdown.forEach(b => {
          const locs = b.locations || [{ name: b.location, count: b.count, isPrimary: true }];
          const activeLoc = locs.find(l => l.count > 0) || locs[0];
          const hasMultiple = locs.length > 1;

          if (hasMultiple) {
            locs.forEach(l => {
              if (l.count > 0) {
                const isTarget = (l === activeLoc);
                badgeList.push(`
                  <div class="dispatch-shelf-pill ${isTarget ? 'highlight' : ''}" style="${!isTarget ? 'border: 1.5px dashed #f59e0b; background: rgba(245, 158, 11, 0.08);' : ''}">
                    <span>Šk.${b.boxNum}: ${escapeHtml(l.name)}</span>
                    <span style="font-size:0.75rem; opacity:0.9; margin-left:4px;">(${l.count} kos)</span>
                  </div>
                `);
              } else {
                badgeList.push(`
                  <div class="dispatch-shelf-pill" style="border-color: #ef4444; color: #f87171; background: rgba(239, 68, 68, 0.1);">
                    <span>Šk.${b.boxNum}: ${escapeHtml(l.name)} (prazno)</span>
                  </div>
                `);
              }
            });
          } else {
            badgeList.push(`
              <div class="dispatch-shelf-pill highlight">
                <span>Šk.${b.boxNum}: ${escapeHtml(activeLoc.name)}</span>
              </div>
            `);
          }
        });

        shelfBadges = badgeList.join(' ');

        multiBoxSection = `
          <div class="dispatch-multibox-box">
            <div style="font-size: 0.78rem; font-weight: 700; color: #fbbf24;">
              Artikel sestavljata ${boxTotal} škatli:
            </div>
            ${stats.breakdown.map(b => {
              const locs = b.locations || [{ name: b.location, count: b.count, isPrimary: true }];
              const activeLoc = locs.find(l => l.count > 0) || locs[0];

              return `
                <div class="dispatch-box-row" style="flex-direction: column; align-items: stretch; gap: 4px; padding: 6px 8px;">
                  <div style="display:flex; justify-content:space-between; align-items:center;">
                    <div class="dispatch-box-name">
                      Škatla ${b.boxNum}/${boxTotal}
                    </div>
                    <div class="dispatch-box-stock">
                      (Skupaj zaloga: <strong>${b.count}</strong> kos)
                    </div>
                  </div>
                  
                  <div style="display:flex; flex-direction:column; gap:4px; margin-top:2px;">
                    ${locs.map(l => {
                      const isTarget = (l === activeLoc && l.count > 0);
                      const isZero = (l.count === 0);
                      return `
                        <div style="display:flex; justify-content:space-between; align-items:center; font-size:0.8rem; background:${isTarget ? 'rgba(245, 158, 11, 0.12)' : (isZero ? 'rgba(239, 68, 68, 0.08)' : 'rgba(255,255,255,0.03)')}; padding:4px 8px; border-radius:4px; border:1px solid ${isTarget ? 'rgba(245, 158, 11, 0.35)' : 'transparent'};">
                          <div>
                            <span style="font-weight:700; color:${isTarget ? '#fbbf24' : (isZero ? '#ef4444' : '#cbd5e1')};">
                              ${isZero ? 'Prazen regal:' : (isTarget ? 'Glavni regal:' : 'Preliv / rezerva:')}
                            </span>
                            <span style="color:#fff; margin-left:4px; font-weight:600;">${escapeHtml(l.name)}</span>
                          </div>
                          <span style="font-weight:700; color:${l.count > 0 ? 'var(--primary)' : 'var(--text-dim)'};">${l.count} kos</span>
                        </div>
                      `;
                    }).join('')}
                  </div>
                </div>
              `;
            }).join('')}
          </div>
        `;

        stockSummary = `
          <div style="font-size: 0.8rem; color: var(--text-muted); display: flex; justify-content: space-between; align-items:center;">
            <span>Celotnih kompletov na zalogi:</span>
            <strong style="color: ${stats.completeSets >= item.orderedQty ? 'var(--success)' : 'var(--warning)'}; font-size: 0.95rem;">
              ${stats.completeSets} garnitur
            </strong>
          </div>
        `;
      } else {
        const singleLocs = dbIt.boxes?.[1]?.locations || [{ name: dbIt.location || 'Regal D-01', count: dbIt.quantity, isPrimary: true }];
        const activeLoc = singleLocs.find(l => l.count > 0) || singleLocs[0];

        shelfBadges = singleLocs.map(l => `
          <div class="dispatch-shelf-pill ${l === activeLoc ? 'highlight' : ''}" style="${l !== activeLoc ? 'border: 1.5px dashed #f59e0b; background: rgba(245, 158, 11, 0.08);' : ''}">
            <span>${escapeHtml(l.name)}</span>
            <span style="font-size:0.75rem; opacity:0.9; margin-left:4px;">(${l.count} kos)</span>
          </div>
        `).join(' ');

        stockSummary = `
          <div style="font-size: 0.8rem; color: var(--text-muted); display: flex; justify-content: space-between; align-items:center;">
            <span>Zaloga na regalu:</span>
            <strong style="color: ${dbIt.quantity >= item.orderedQty ? 'var(--success)' : 'var(--warning)'}; font-size: 0.95rem;">
              ${dbIt.quantity} ${dbIt.unit || 'kos'}
            </strong>
          </div>
        `;
      }
    } else {
      shelfBadges = `
        <div class="dispatch-shelf-pill" style="border-color: #ef4444; color: #f87171; background: rgba(239, 68, 68, 0.15);">
          <span>REGAL NEZNAN (Ni v evidenci)</span>
        </div>
      `;

      stockSummary = `
        <div style="font-size: 0.8rem; color: var(--warning); background: rgba(245, 158, 11, 0.1); padding: 6px 10px; border-radius: var(--radius-sm);">
          Artikel še ni v sistemu zalog skladišča.
        </div>
      `;
    }

    const cardClass = isPicked ? 'dispatch-card picked-step' : (idx === 0 || currentDispatchItems[idx - 1]?.isPicked ? 'dispatch-card active-step' : 'dispatch-card');
    const badgeStatus = isPicked 
      ? '<span class="dispatch-step-badge done">PREVZETO</span>'
      : '<span class="dispatch-step-badge pending">ČAKA PREVZEM</span>';

    html += `
      <div class="${cardClass}" id="dispatchCard_${idx}">
        <div class="dispatch-card-top">
          <div style="display: flex; flex-wrap: wrap; gap: 6px; align-items: center;">
            ${shelfBadges}
          </div>
          <div>${badgeStatus}</div>
        </div>

        <div style="display: flex; flex-direction: column; gap: 3px;">
          <div class="dispatch-item-title">${escapeHtml(item.name)}</div>
          <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap;">
            <span class="dispatch-sku-pill">Šifra: ${escapeHtml(item.sku)}</span>
            <span style="font-size: 0.82rem; color: var(--text-muted);">Naročeno: <strong style="color: #fff;">${item.orderedQty}x</strong></span>
            ${item.price ? `<span style="font-size: 0.8rem; color: var(--primary);">(${item.price} €)</span>` : ''}
          </div>
        </div>

        ${multiBoxSection}
        ${stockSummary}

        <div style="margin-top: 4px;">
          ${isPicked ? `
            <button type="button" class="btn-pick-confirm" disabled style="background: rgba(16, 185, 129, 0.2); color: #10b981; border-color: rgba(16, 185, 129, 0.4); cursor: default;">
              Pobrano iz regala
            </button>
          ` : `
            <button type="button" class="btn-pick-confirm" onclick="confirmDispatchPick(${idx})">
              Potrdi prevzem (${item.orderedQty}x)
            </button>
          `}
        </div>
      </div>
    `;
  });

  container.innerHTML = html;
}

window.confirmDispatchPick = async function(index) {
  const item = currentDispatchItems[index];
  if (!item || item.isPicked) return;

  const receiptNo = currentDispatchData?.receiptNo || 'račun';
  const note = `Izdaja po računu ${receiptNo}`;

  try {
    if (item.dbItem) {
      if (item.dbItem.boxTotal > 1) {
        await DB.issueCompleteItem(item.dbItem.id, item.orderedQty, note);
      } else {
        await DB.adjustStock(item.dbItem.id, -item.orderedQty, note, 'out');
      }
      item.dbItem = await DB.getItemById(item.dbItem.id);
    }

    item.isPicked = true;
    if (navigator.vibrate) navigator.vibrate([100, 50, 100]);
    playSuccessBeep();
    showToast(`Prevzeto: ${item.name}`);

    renderDispatchRouteList();
    updateDispatchProgress();

    const nextIdx = currentDispatchItems.findIndex(it => !it.isPicked);
    if (nextIdx !== -1) {
      const nextEl = document.getElementById(`dispatchCard_${nextIdx}`);
      if (nextEl) {
        nextEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      }
    }
  } catch (err) {
    console.error('Napaka pri potrditvi prevzema:', err);
    showToast('Napaka pri posodobitvi zaloge: ' + err.message);
  }
};

function updateDispatchProgress() {
  const total = currentDispatchItems.length;
  const picked = currentDispatchItems.filter(it => it.isPicked).length;
  const percent = total > 0 ? Math.round((picked / total) * 100) : 0;

  const countEl = document.getElementById('dispatchProgressCount');
  const percentEl = document.getElementById('dispatchProgressPercent');
  const barEl = document.getElementById('dispatchProgressBar');
  const btnFinish = document.getElementById('btnFinishDispatch');

  if (countEl) countEl.textContent = `Prevzeto ${picked} / ${total} artiklov`;
  if (percentEl) percentEl.textContent = `${percent}%`;
  if (barEl) barEl.style.width = `${percent}%`;

  if (btnFinish) {
    if (picked === total && total > 0) {
      btnFinish.disabled = false;
      btnFinish.innerHTML = 'Zaključi izdajo';
    } else {
      btnFinish.disabled = (picked === 0);
      btnFinish.innerHTML = `Zaključi delno izdajo (${picked}/${total})`;
    }
  }
}

window.closeReceiptDispatchModal = function() {
  const modal = document.getElementById('modalReceiptDispatch');
  if (modal) {
    modal.classList.remove('active');
    modal.style.display = 'none';
  }
};

async function finishReceiptDispatch() {
  closeReceiptDispatchModal();

  const receiptNo = currentDispatchData?.receiptNo || '';
  showToast(`Izdaja po računu ${receiptNo} je zaključena.`);
  playSuccessBeep();

  await refreshInventory();
  await loadLogs();
}

window.closeBoxResultCard = function() {
  const resultCard = document.getElementById('scanResultCard');
  if (resultCard) resultCard.style.display = 'none';
  lastDetectedBoxKey = null;
  lastDetectedSKU = null;
  lastDetectedTime = 0;
  currentScannedBox = null;
  window.parsed = null;
};

function renderBoxResultCard(existingItem) {
  const resultCard = document.getElementById('scanResultCard');
  const parsed = currentScannedBox;
  if (!parsed) return;

  const sku = parsed.articleNumber || 'Ni zaznana';
  const name = (existingItem && existingItem.name) ? existingItem.name : (parsed.name || 'Neznan artikel');
  const boxNum = parsed.boxCurrent || 1;
  const boxTotal = Math.max(1, parsed.boxTotal || (existingItem ? existingItem.boxTotal : 1));
  const boxInfo = `Box ${boxNum}/${boxTotal}`;

  const existingBoxLoc = (existingItem && existingItem.boxes && existingItem.boxes[boxNum]) 
    ? existingItem.boxes[boxNum].location 
    : (existingItem ? existingItem.location : '');

  const activeScannerWh = getActiveScannerWarehouse();
  let currentSelectedWh = activeScannerWh;
  if (existingItem) {
    if (existingItem.primaryWarehouse) currentSelectedWh = existingItem.primaryWarehouse;
    else if (existingItem.warehouse) currentSelectedWh = existingItem.warehouse;
    else if (existingItem.categoryType === 'furniture' && activeScannerWh === 'zgornje') currentSelectedWh = 'spodnje';
  }

  // 1. Samodejno prekopiraj regal prejšnjega artikla:
  // Če je bil predhodno že vnesen/uporabljen regal (lastUsedShelf), ga samodejno prekopiramo v ta artikel.
  let currentBoxLoc = lastUsedShelf || existingBoxLoc || '';

  // 2. PAMETNO USMERJANJE NA ISTI REGAL:
  let smartShelfHintHtml = '';
  let suggestedShelf = '';
  let suggestedReason = '';

  if (existingItem && boxTotal > 1) {
    const otherBoxLocations = [];
    if (existingItem.boxes) {
      for (let b = 1; b <= boxTotal; b++) {
        if (b !== boxNum && existingItem.boxes[b]) {
          const bData = existingItem.boxes[b];
          const loc = bData.location || (bData.locations && bData.locations[0]?.name);
          const count = Number(bData.count) || 0;
          if (loc && loc !== 'Prevzemno območje' && loc !== 'Brez lokacije') {
            otherBoxLocations.push({ boxNum: b, location: loc, count });
          }
        }
      }
    }

    if (otherBoxLocations.length > 0) {
      const withStock = otherBoxLocations.find(o => o.count > 0) || otherBoxLocations[0];
      suggestedShelf = withStock.location;
      suggestedReason = `Škatla ${withStock.boxNum}/${boxTotal} je na regalu <strong>${escapeHtml(suggestedShelf)}</strong> (${withStock.count} kos).`;

      smartShelfHintHtml = `
        <div class="smart-shelf-banner" style="background: rgba(16, 185, 129, 0.14); border: 1px solid #10b981; border-radius: var(--radius-sm); padding: 8px 10px; margin: 4px 0; display: flex; align-items: center; justify-content: space-between; gap: 8px;">
          <div style="flex: 1;">
            <div style="font-size: 0.8rem; font-weight: 800; color: #fff;">
              Priporočeni regal: <span style="color: #34d399; font-size: 0.9rem;">${escapeHtml(suggestedShelf)}</span>
            </div>
            <div style="font-size: 0.72rem; color: #d1fae5; margin-top: 1px;">
              ${suggestedReason}
            </div>
          </div>
          <button type="button" class="btn-sm" onclick="applySuggestedShelf('${escapeHtml(suggestedShelf)}')" style="background: #10b981; color: #042f2e; border: none; border-radius: 6px; padding: 5px 9px; font-weight: 800; font-size: 0.75rem; cursor: pointer; white-space: nowrap;">
            Izberi
          </button>
        </div>
      `;

      // Če artikel še nima določene lokacije ali ni prejšnjega regala, predlagamo ta regal
      if (!currentBoxLoc || currentBoxLoc === 'Prevzemno območje' || currentBoxLoc === 'Brez lokacije') {
        currentBoxLoc = suggestedShelf;
      }
    }
  }

  let boxBreakdownHtml = '';
  let stockInfoHtml = '';

  if (existingItem) {
    const stats = DB.getBoxStats(existingItem);
    stockInfoHtml = `
      <div style="background: rgba(16, 185, 129, 0.12); border: 1px solid rgba(16, 185, 129, 0.3); border-radius: var(--radius-sm); padding: 6px 10px; font-size: 0.8rem; color: var(--success); display:flex; justify-content:space-between; align-items:center;">
        <span>Zaloga celih kompletov:</span>
        <strong style="font-size: 0.95rem;">${stats.completeSets} ${existingItem.unit || (stats.boxTotal > 1 ? 'garnitur' : 'kos')}</strong>
      </div>
    `;

    if (stats.boxTotal > 1) {
      boxBreakdownHtml = `
        <div class="box-breakdown-container" style="margin-top: 4px;">
          <div style="font-size: 0.7rem; color: var(--text-muted); font-weight: 600; text-transform: uppercase;">
            Stanje vseh škatel tega artikla:
          </div>
          ${stats.breakdown.map(b => `
            <div class="box-breakdown-row" style="${b.boxNum === boxNum ? 'border-color: rgba(56, 189, 248, 0.5); background: rgba(56, 189, 248, 0.1);' : ''}">
              <span>${b.boxNum === boxNum ? '> ' : ''}Škatla ${b.boxNum}/${stats.boxTotal}: <span class="box-breakdown-loc">${escapeHtml(b.location)}</span></span>
              <span class="box-breakdown-count">${b.count} kos</span>
            </div>
          `).join('')}
          <div style="font-size: 0.73rem; margin-top: 2px;">
            ${stats.hasIncomplete 
              ? `<span style="color: var(--warning); font-weight:600;">${escapeHtml(stats.missingSummary)} (celih: ${stats.completeSets})</span>` 
              : `<span style="color: var(--success); font-weight:600;">Vse škatle na voljo (${stats.completeSets} kompletov)</span>`}
          </div>
        </div>
      `;
    }
  } else {
    stockInfoHtml = `
      <div style="background: rgba(245, 158, 11, 0.12); border: 1px solid rgba(245, 158, 11, 0.3); border-radius: var(--radius-sm); padding: 6px 10px; font-size: 0.8rem; color: var(--warning);">
        Artikel s šifro <strong>${escapeHtml(sku)}</strong> še ni vpisan v zalogo.
      </div>
    `;
  }

  resultCard.innerHTML = `
    <div class="box-result-header">
      <div style="display:flex; align-items:baseline; gap: 8px;">
        <span style="font-size: 0.78rem; color: var(--text-muted); font-weight: 600;">ŠIFRA ARTIKLA:</span>
        <span class="box-sku-highlight">${escapeHtml(sku)}</span>
      </div>
      <button type="button" class="btn-card-close" onclick="closeBoxResultCard()" title="Zapri in nadaljuj skeniranje">✕</button>
    </div>

    <div style="display: flex; flex-direction: column; gap: 2px; margin: 2px 0;">
      <div style="display:flex; justify-content: space-between; align-items:center;">
        <span style="font-size: 0.7rem; color: var(--text-muted); text-transform: uppercase; font-weight: 600;">Naziv artikla:</span>
        <span style="font-size: 0.7rem; color: var(--primary);">uredi naziv</span>
      </div>
      <input type="text" id="boxResultNameInput" class="form-control" style="font-size: 1rem; font-weight: 700; color: #fff; background: rgba(255,255,255,0.08); border-color: rgba(56, 189, 248, 0.4); padding: 6px 10px;" value="${escapeHtml(name)}">
    </div>

    <div class="box-badges" style="display: flex; flex-wrap: wrap; gap: 5px; align-items: center;">
      <span class="box-badge highlight">${escapeHtml(boxInfo)}</span>
      ${boxTotal > 1 ? `<span class="box-badge" style="background: rgba(245, 158, 11, 0.15); color: #fbbf24; border: 1px solid rgba(245, 158, 11, 0.3);">Škatla ${boxNum} od ${boxTotal}</span>` : ''}
      
      <!-- Okenček za branje in vnos kosov / manjših škatel v škatli (iz OCR etikete) -->
      <div class="box-badge highlight" style="display: inline-flex; align-items: center; gap: 5px; padding: 3px 7px;">
        <span style="font-size: 0.74rem; font-weight: 700;">Kosov v škatli:</span>
        <input type="number" id="boxPiecesPerBoxInput" min="1" value="${Math.max(1, parseInt(parsed.quantityInBox, 10) || 1)}" style="width: 48px; background: rgba(0,0,0,0.5); border: 1px solid rgba(56, 189, 248, 0.5); color: #38bdf8; font-weight: 800; text-align: center; border-radius: 4px; padding: 2px 4px; font-size: 0.9rem;" oninput="updatePiecesPerBox(this.value)">
        <span style="font-size: 0.7rem; color: var(--text-muted);">(manjših škatel)</span>
      </div>
    </div>

    ${smartShelfHintHtml}

    <!-- Izbira skladišča za to posamezno škatlo / prevzem -->
    <div style="background: rgba(16, 185, 129, 0.08); border: 1px solid rgba(16, 185, 129, 0.3); border-radius: var(--radius-sm); padding: 6px 10px; margin: 3px 0;">
      <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom: 3px;">
        <span style="font-size: 0.72rem; color: #10b981; font-weight: 700; text-transform: uppercase;">
          🏢 Skladišče za prevzem:
        </span>
        <span style="font-size: 0.68rem; color: var(--text-muted);">kam gre zaloga</span>
      </div>
      <select id="boxResultWarehouseSelect" class="form-control" style="padding: 6px 10px; font-weight: 700; font-size: 0.88rem; background: rgba(0,0,0,0.45); border-color: rgba(16, 185, 129, 0.5); color: #fff;" onchange="onBoxResultWarehouseChange(this.value)">
        ${(cachedWarehouses.length > 0 ? cachedWarehouses : [
          { id: 'zgornje', name: 'Zgornje skladišče (Soft)' },
          { id: 'spodnje', name: 'Spodnje skladišče (Pohištvo)' },
          { id: 'trgovina', name: 'Prodajalna (Police)' }
        ]).map(w => `<option value="${escapeHtml(w.id)}" ${w.id === currentSelectedWh ? 'selected' : ''}>🏢 ${escapeHtml(w.name)}</option>`).join('')}
      </select>
    </div>

    <!-- Vnos / urejanje lokacije za to posamezno škatlo -->
    <div style="background: rgba(56, 189, 248, 0.08); border: 1px solid rgba(56, 189, 248, 0.25); border-radius: var(--radius-sm); padding: 6px 10px; margin: 3px 0;">
      <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom: 3px;">
        <span style="font-size: 0.72rem; color: var(--primary); font-weight: 700; text-transform: uppercase;">
          Lokacija za Škatlo ${boxNum}/${boxTotal}:
        </span>
        <span style="font-size: 0.68rem; color: var(--text-muted);">regal / polica</span>
      </div>
      <input type="text" id="boxResultLocationInput" class="form-control" style="padding: 6px 10px; font-weight: 600; font-size: 0.9rem; background: rgba(0,0,0,0.35); border-color: rgba(56, 189, 248, 0.4);" placeholder="npr. Regal A-01" value="${escapeHtml(currentBoxLoc || '')}" oninput="updateLastUsedShelf(this.value)">
      ${lastUsedShelf ? `
        <div style="font-size: 0.7rem; color: #94a3b8; margin-top: 3px; display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 4px;">
          <span>Prekopiran regal prejšnjega artikla: <strong style="color: #38bdf8;">${escapeHtml(lastUsedShelf)}</strong></span>
          ${(existingBoxLoc && existingBoxLoc !== lastUsedShelf && existingBoxLoc !== 'Prevzemno območje' && existingBoxLoc !== 'Brez lokacije') ? `
            <button type="button" class="btn-sm" onclick="applySuggestedShelf('${escapeHtml(existingBoxLoc)}')" style="padding: 2px 6px; font-size: 0.68rem; background: rgba(255,255,255,0.08); border: 1px solid var(--border); color: #cbd5e1; border-radius: 4px; cursor: pointer;">
              Uporabi obstoječi regal artikla (${escapeHtml(existingBoxLoc)})
            </button>
          ` : ''}
        </div>
      ` : ''}
    </div>

    <!-- Vnos števila škatel z namenskim okenčkom za ročni vpis ter hitrimi gumbi -->
    <div style="background: rgba(255,255,255,0.03); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 6px 10px; margin: 3px 0; display: flex; flex-direction: column; gap: 4px;">
      <div style="display:flex; justify-content:space-between; align-items:center;">
        <span style="font-size: 0.74rem; color: var(--text-muted); font-weight: 700; text-transform: uppercase;">
          Število škatel:
        </span>
        <div style="display: flex; align-items: center; gap: 4px;">
          <button type="button" class="btn-sm" onclick="changeBoxMultiplierDelta(-1)" style="padding: 3px 9px; font-weight: 800; background: rgba(255,255,255,0.08); border: 1px solid var(--border); color: #fff; border-radius: 6px; font-size: 0.9rem;">-</button>
          <input type="number" id="boxMultiplierInput" min="1" value="${currentMultiplier}" style="width: 56px; text-align: center; font-size: 1rem; font-weight: 800; color: #fff; background: rgba(0,0,0,0.5); border: 1px solid rgba(56, 189, 248, 0.4); border-radius: 6px; padding: 3px 5px;" oninput="setBoxMultiplierCustom(this.value)">
          <button type="button" class="btn-sm" onclick="changeBoxMultiplierDelta(1)" style="padding: 3px 9px; font-weight: 800; background: rgba(255,255,255,0.08); border: 1px solid var(--border); color: #fff; border-radius: 6px; font-size: 0.9rem;">+</button>
        </div>
      </div>

      <div class="box-multiplier-row" style="margin: 0; justify-content: flex-end; gap: 5px;">
        <span style="font-size: 0.7rem; color: var(--text-dim); margin-right: auto;">Hitri izbor:</span>
        <button type="button" class="mult-btn ${currentMultiplier === 1 ? 'active' : ''}" onclick="setBoxMultiplier(1)">1x</button>
        <button type="button" class="mult-btn ${currentMultiplier === 2 ? 'active' : ''}" onclick="setBoxMultiplier(2)">2x</button>
        <button type="button" class="mult-btn ${currentMultiplier === 5 ? 'active' : ''}" onclick="setBoxMultiplier(5)">5x</button>
        <button type="button" class="mult-btn ${currentMultiplier === 10 ? 'active' : ''}" onclick="setBoxMultiplier(10)">10x</button>
      </div>

      <!-- Izračun skupaj kosov / manjših škatel -->
      <div id="boxCalcSummary" style="font-size: 0.78rem; font-weight: 600; color: var(--primary); text-align: right; margin-top: 1px;">
        ${(parseInt(parsed.quantityInBox, 10) > 1) ? `Skupaj: ${currentMultiplier} škatel × ${parsed.quantityInBox} kos = ${currentMultiplier * parsed.quantityInBox} kosov (manjših škatel)` : `Skupaj: ${currentMultiplier} škatel`}
      </div>
    </div>

    <!-- Hitra skladiščna dejanja za prevzem in izdajo (NEPOSREDNO NA DOSEGU PALCA) -->
    <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 4px;">
      <button id="btnConfirmReceiveBox" class="btn-primary" onclick="executeBoxStockChange('in')" style="margin-top:0; padding: 10px 4px; font-weight: 800; font-size: 0.95rem; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px;">
        <span>${(parseInt(parsed.quantityInBox, 10) > 1) 
          ? `PREVZEMI (+${currentMultiplier * parseInt(parsed.quantityInBox, 10)} kos)` 
          : `PREVZEMI (+${currentMultiplier} škatel)`}</span>
        <span style="font-size: 0.68rem; font-weight: 700; opacity: 0.9; background: rgba(0,0,0,0.25); padding: 1px 6px; border-radius: 4px;">🔊 Tipka Glasnost GOR</span>
      </button>
      <button id="btnConfirmIssueBox" class="btn-secondary" onclick="executeBoxStockChange('out')" style="margin-top:0; padding: 10px 4px; font-weight: 800; font-size: 0.95rem;">
        ${(parseInt(parsed.quantityInBox, 10) > 1) 
          ? `IZDAJ (-${currentMultiplier * parseInt(parsed.quantityInBox, 10)} kos)` 
          : `IZDAJ (-${currentMultiplier} škatel)`}
      </button>
    </div>

    <!-- Dodatne informacije o stanju zaloge (nameščene spodaj za nemoten potek) -->
    ${stockInfoHtml}
    ${boxBreakdownHtml}

    ${!existingItem && sku ? `
      <button class="btn-secondary" onclick="openAddFromScannedBox()" style="margin-top: 3px; border-color: var(--primary); color: var(--primary); padding: 8px; font-size: 0.82rem;">
        + Dodaj ta artikel v skladišče
      </button>
    ` : ''}
    <button type="button" class="btn-secondary" onclick="closeBoxResultCard()" style="margin-top: 3px; padding: 8px; font-size: 0.8rem; border-color: rgba(255,255,255,0.15); display: flex; align-items: center; justify-content: center; gap: 6px;">
      <span>Zavrzi / Zapri in skeniraj naslednjo</span>
      <span style="font-size: 0.68rem; font-weight: 700; opacity: 0.85; background: rgba(255,255,255,0.12); padding: 1px 6px; border-radius: 4px;">🔉 Tipka Glasnost DOL</span>
    </button>
  `;
}

window.applySuggestedShelf = function(shelf) {
  const input = document.getElementById('boxResultLocationInput');
  if (input) {
    input.value = shelf;
    lastUsedShelf = shelf;
    try { localStorage.setItem('skladisce_last_shelf', shelf); } catch(e){}
    showToast(`Izbran regal: ${shelf}`);
  }
};

window.updateLastUsedShelf = function(val) {
  const clean = String(val || '').trim();
  if (clean) {
    lastUsedShelf = clean;
    try { localStorage.setItem('skladisce_last_shelf', clean); } catch(e){}
  }
};

window.updatePiecesPerBox = function(val) {
  const num = Math.max(1, parseInt(val, 10) || 1);
  if (currentScannedBox) {
    currentScannedBox.quantityInBox = num;
  }
  updateActionButtons();
};

window.setBoxMultiplierCustom = function(val) {
  currentMultiplier = Math.max(1, parseInt(val, 10) || 1);
  updateActionButtons();
  updateMultiplierButtons();
};

window.changeBoxMultiplierDelta = function(delta) {
  currentMultiplier = Math.max(1, (parseInt(currentMultiplier, 10) || 1) + delta);
  const input = document.getElementById('boxMultiplierInput');
  if (input) input.value = currentMultiplier;
  updateActionButtons();
  updateMultiplierButtons();
};

window.setBoxMultiplier = function(m) {
  currentMultiplier = Math.max(1, parseInt(m, 10) || 1);
  const input = document.getElementById('boxMultiplierInput');
  if (input) input.value = currentMultiplier;
  updateActionButtons();
  updateMultiplierButtons();
};

function updateMultiplierButtons() {
  const btns = document.querySelectorAll('.mult-btn');
  btns.forEach(b => {
    const val = parseInt(b.textContent.replace('x', ''), 10);
    if (val === currentMultiplier) {
      b.classList.add('active');
    } else {
      b.classList.remove('active');
    }
  });
}

function updateActionButtons() {
  const piecesEl = document.getElementById('boxPiecesPerBoxInput');
  const pieces = Math.max(1, parseInt(piecesEl ? piecesEl.value : (currentScannedBox?.quantityInBox || 1), 10) || 1);
  const boxes = Math.max(1, parseInt(currentMultiplier, 10) || 1);
  const totalAmount = pieces * boxes;

  const inBtn = document.getElementById('btnConfirmReceiveBox');
  const outBtn = document.getElementById('btnConfirmIssueBox');
  const summaryEl = document.getElementById('boxCalcSummary');

  if (pieces > 1) {
    if (inBtn) inBtn.textContent = `PREVZEMI (+${totalAmount} kos • ${boxes}x šk.)`;
    if (outBtn) outBtn.textContent = `IZDAJ (-${totalAmount} kos • ${boxes}x šk.)`;
    if (summaryEl) summaryEl.textContent = `Skupaj: ${boxes} škatel × ${pieces} kos = ${totalAmount} kosov (manjših škatel)`;
  } else {
    if (inBtn) inBtn.textContent = `PREVZEMI (+${boxes} škatel)`;
    if (outBtn) outBtn.textContent = `IZDAJ (-${boxes} škatel)`;
    if (summaryEl) summaryEl.textContent = `Skupaj: ${boxes} škatel`;
  }
}

window.executeBoxStockChange = async function(direction) {
  if (!currentScannedBox) return;

  const sku = currentScannedBox.articleNumber;
  const piecesPerBox = Math.max(1, parseInt(document.getElementById('boxPiecesPerBoxInput')?.value, 10) || currentScannedBox.quantityInBox || 1);
  const boxCount = Math.max(1, parseInt(document.getElementById('boxMultiplierInput')?.value, 10) || currentMultiplier || 1);
  const totalAmount = piecesPerBox * boxCount;

  const chosenName = document.getElementById('boxResultNameInput')?.value?.trim() || currentScannedBox.name || `Artikel ${sku}`;
  const chosenWarehouse = document.getElementById('boxResultWarehouseSelect')?.value || getActiveScannerWarehouse() || 'zgornje';
  const boxLocation = document.getElementById('boxResultLocationInput')?.value?.trim() || '';
  if (boxLocation) {
    lastUsedShelf = boxLocation;
    try { localStorage.setItem('skladisce_last_shelf', boxLocation); } catch(e){}
  }
  const boxNum = currentScannedBox.boxCurrent || 1;
  const boxTotal = Math.max(1, currentScannedBox.boxTotal || 1);

  let item = await DB.getItemBySKU(sku);

  // Če artikel še ne obstaja, ga avtomatsko ustvari s prepoznanim ali popravljenim imenom
  if (!item) {
    const boxes = {};
    for (let b = 1; b <= boxTotal; b++) {
      boxes[b] = {
        count: 0,
        warehouse: chosenWarehouse,
        location: (b === boxNum && boxLocation) ? boxLocation : (boxLocation || 'Prevzemno območje')
      };
    }

    const res = await DB.addItem({
      sku: sku || 'ART-' + Math.floor(1000 + Math.random() * 9000),
      name: chosenName,
      quantity: 0,
      minQuantity: 2,
      boxInfo: `Box ${boxNum}/${boxTotal}`,
      boxTotal: boxTotal,
      boxes: boxes,
      unit: (boxTotal > 1) ? 'garnitura' : 'kos',
      category: 'Splošno',
      warehouse: chosenWarehouse,
      location: boxLocation || 'Prevzemno območje'
    });
    const newId = (typeof res === 'object' && res.sku) ? res.sku : (res.id || res);
    item = await DB.getItemById(newId);
  } else if (item.name !== chosenName) {
    item.name = chosenName;
    await DB.updateItem(item);
  }

  // Če je prevzem in je aktivna prevzemna pošiljka, zabeleži vnos
  if (direction === 'in') {
    await recordReceivingEntry(item.sku, chosenName, boxNum, boxTotal, boxLocation || 'Glavni regal', totalAmount, chosenWarehouse);
  }

  const note = (piecesPerBox > 1)
    ? `${boxCount}x škatla (${boxNum}/${boxTotal}), ${piecesPerBox} manjših škatel/kosov v škatli`
    : `${boxCount}x škatla (${boxNum}/${boxTotal})`;
  const delta = (direction === 'in') ? totalAmount : -totalAmount;

  await DB.adjustStock(item.sku, delta, note, direction, boxNum, boxLocation, chosenWarehouse);
  if (navigator.vibrate) navigator.vibrate([100, 50, 100]);

  const unitDesc = (piecesPerBox > 1) ? `${totalAmount} kos (${boxCount} škat.)` : `${totalAmount} škatel`;
  const whObj = cachedWarehouses.find(w => w.id === chosenWarehouse);
  const whName = whObj ? whObj.name : chosenWarehouse;
  showToast(direction === 'in' ? `Prevzeto: +${unitDesc} (${chosenName}) v ${whName}` : `Izdano: -${unitDesc} (${chosenName})`);

  // Takoj ponastavi cooldown, da je kamera v trenutku pripravljena na naslednjo škatlo
  lastDetectedBoxKey = null;
  lastDetectedSKU = null;
  lastDetectedTime = 0;

  // Osveži prikaz
  const updatedItem = await DB.getItemById(item.sku);
  renderBoxResultCard(updatedItem);
  await refreshInventory();

  // 1-KLIK SISTEM: Samodejno zapri kartico po 350ms, da lahko skladiščnik takoj skenira naslednjo škatlo
  setTimeout(() => {
    closeBoxResultCard();
  }, 350);
};

window.openAddFromScannedBox = function() {
  if (!currentScannedBox) return;

  const chosenName = document.getElementById('boxResultNameInput')?.value?.trim() || currentScannedBox.name || '';
  const chosenLoc = document.getElementById('boxResultLocationInput')?.value?.trim() || lastUsedShelf || '';
  const piecesPerBox = Math.max(1, parseInt(document.getElementById('boxPiecesPerBoxInput')?.value, 10) || currentScannedBox.quantityInBox || 1);
  const boxCount = Math.max(1, parseInt(document.getElementById('boxMultiplierInput')?.value, 10) || currentMultiplier || 1);
  const totalAmount = piecesPerBox * boxCount;

  const modalItem = document.getElementById('modalItem');
  const formItem = document.getElementById('formItem');
  formItem.reset();
  document.getElementById('itemId').value = '';
  document.getElementById('itemSku').value = currentScannedBox.articleNumber || '';
  document.getElementById('itemName').value = chosenName;
  document.getElementById('itemLocation').value = chosenLoc;
  document.getElementById('itemBoxInfo').value = `Box ${currentScannedBox.boxCurrent}/${currentScannedBox.boxTotal}`;
  document.getElementById('itemQuantity').value = totalAmount;
  document.getElementById('modalItemTitle').textContent = 'Nov artikel iz škatle';
  document.getElementById('btnDeleteItem').style.display = 'none';
  modalItem.style.display = 'flex';
};

async function handleManual7DigitLookup(code) {
  const item = await DB.getItemBySKU(code);
  const parsed = WarehouseLabelParser.parse(code) || {};
  parsed.articleNumber = code;
  if (!parsed.name || parsed.name === `Artikel ${code}`) {
    parsed.name = item ? item.name : `Artikel ${code}`;
  }
  parsed.boxCurrent = 1;
  parsed.boxTotal = item ? item.boxTotal : 1;
  parsed.quantityInBox = parsed.quantityInBox || 1;
  parsed.rawText = code;
  await handleBoxScanResult(parsed);
}

/* =========================================================================
   6. DNEVNIK PREMIKOV (ZGODOVINA)
   ========================================================================= */
async function refreshLogs() {
  const listEl = document.getElementById('logsList');
  const logs = await DB.getAllLogs();

  if (logs.length === 0) {
    listEl.innerHTML = `
      <div class="empty-box">
        <p>Ni zabeleženih premikov zaloge.</p>
      </div>
    `;
    return;
  }

  listEl.innerHTML = logs.map(log => {
    let typeClass = 'log-adjust';
    let typeLabel = 'Popravek';
    let sign = (log.changeAmount > 0) ? '+' : '';

    if (log.changeType === 'in') {
      typeClass = 'log-in';
      typeLabel = 'Prevzem';
    } else if (log.changeType === 'out') {
      typeClass = 'log-out';
      typeLabel = 'Izdaja';
    } else if (log.changeType === 'create') {
      typeClass = 'log-in';
      typeLabel = 'Nov vnos';
    }

    const d = new Date(log.timestamp);
    const dateFormatted = d.toLocaleDateString('sl-SI') + ' ' + d.toLocaleTimeString('sl-SI', { hour: '2-digit', minute: '2-digit' });

    return `
      <div class="log-item">
        <div>
          <div style="display:flex; align-items:center; gap:6px; margin-bottom: 4px;">
            <span class="log-type-tag ${typeClass}">${typeLabel}</span>
            <span style="font-weight:600; font-size: 0.95rem;">${escapeHtml(log.itemName)}</span>
          </div>
          <div style="font-size: 0.78rem; color: var(--text-dim);">
            ${dateFormatted} &bull; ${escapeHtml(log.note || 'Brez opombe')}
          </div>
        </div>
        <div style="text-align: right;">
          <div style="font-weight: 800; font-size: 1.1rem; color: ${log.changeAmount >= 0 ? 'var(--success)' : 'var(--danger)'}">
            ${sign}${log.changeAmount}
          </div>
          <div style="font-size: 0.72rem; color: var(--text-muted);">Skupaj: ${log.resultingQty}</div>
        </div>
      </div>
    `;
  }).join('');
}

/* =========================================================================
   6b. MODUL ZA INVENTURO (STOCKTAKING AUDIT)
   ========================================================================= */
let auditSession = {
  entries: [],
  shelfFilter: 'all',
  statusFilter: 'all'
};
const AUDIT_STORAGE_KEY = 'skladisce_audit_session_v1';

function setupAuditModule() {
  // 1. Filter po regalu
  const shelfFilterEl = document.getElementById('auditShelfFilter');
  if (shelfFilterEl) {
    shelfFilterEl.addEventListener('change', (e) => {
      auditSession.shelfFilter = e.target.value;
      saveAuditSession();
      renderAuditList();
      updateAuditStats();
    });
  }

  // 2. Filter po statusu (Vse / Odstopanja / Še neprešteto / Usklajeno)
  const filterChipsEl = document.getElementById('auditFilterChips');
  if (filterChipsEl) {
    filterChipsEl.addEventListener('click', (e) => {
      const chip = e.target.closest('.chip');
      if (!chip) return;
      filterChipsEl.querySelectorAll('.chip').forEach(c => c.classList.remove('active'));
      chip.classList.add('active');
      auditSession.statusFilter = chip.dataset.auditFilter || 'all';
      saveAuditSession();
      renderAuditList();
    });
  }

  // 3. Hitro skeniranje / iskanje po 7-mestni šifri
  const scanInput = document.getElementById('auditScanInput');
  const btnQuickCount = document.getElementById('btnAuditQuickCount');

  const triggerAuditScan = () => {
    const rawVal = scanInput ? scanInput.value.trim() : '';
    if (!rawVal) return;
    handleAuditScan(rawVal);
    if (scanInput) scanInput.value = '';
  };

  if (scanInput) {
    scanInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        triggerAuditScan();
      }
    });
  }
  if (btnQuickCount) {
    btnQuickCount.addEventListener('click', triggerAuditScan);
  }

  // 4. Ponastavitev popisa
  const btnReset = document.getElementById('btnResetAuditSession');
  if (btnReset) {
    btnReset.addEventListener('click', resetAuditSession);
  }

  // 5. Izvoz PDF Zapisnika
  const btnPdf = document.getElementById('btnExportAuditPDF');
  if (btnPdf) {
    btnPdf.addEventListener('click', exportAuditPDF);
  }

  // 6. Uveljavitev popisa v zalogo
  const btnApply = document.getElementById('btnApplyAudit');
  if (btnApply) {
    btnApply.addEventListener('click', applyAuditToInventory);
  }
}

async function loadAuditSession(forceReset = false) {
  try {
    const allItems = await DB.getAllItems();
    const newEntries = [];
    const shelfSet = new Set();

    for (const item of allItems) {
      const norm = DB.normalizeItemBoxes(item);
      const boxTotal = norm.boxTotal || 1;

      for (let b = 1; b <= boxTotal; b++) {
        const bObj = norm.boxes[b];
        if (!bObj) continue;

        const locs = (bObj.locations && bObj.locations.length > 0)
          ? bObj.locations
          : [{ name: bObj.location || norm.location || 'Brez lokacije', count: bObj.count || 0 }];

        for (const loc of locs) {
          const locName = String(loc.name || 'Brez lokacije').trim();
          shelfSet.add(locName);

          const key = `${item.id}_b${b}_${encodeURIComponent(locName)}`;
          newEntries.push({
            key,
            itemId: item.id,
            sku: item.sku,
            name: item.name,
            category: item.category || '',
            boxNum: b,
            boxTotal: boxTotal,
            locName: locName,
            bookQty: Number(loc.count) || 0,
            countedQty: 0,
            isCounted: false
          });
        }
      }
    }

    // Obnovi predhodno shranjene štetje iz localStorage
    if (!forceReset) {
      try {
        const savedRaw = localStorage.getItem(AUDIT_STORAGE_KEY);
        if (savedRaw) {
          const savedData = JSON.parse(savedRaw);
          if (savedData && Array.isArray(savedData.entries)) {
            const savedMap = new Map();
            savedData.entries.forEach(e => savedMap.set(e.key, e));

            newEntries.forEach(e => {
              const prev = savedMap.get(e.key);
              if (prev && prev.isCounted) {
                e.countedQty = Number(prev.countedQty) || 0;
                e.isCounted = true;
              }
            });
            if (savedData.shelfFilter) auditSession.shelfFilter = savedData.shelfFilter;
            if (savedData.statusFilter) auditSession.statusFilter = savedData.statusFilter;
          }
        }
      } catch (err) {
        console.warn('Napaka pri branju shranjene inventurne seje:', err);
      }
    }

    auditSession.entries = newEntries;

    // Posodobi spustni seznam regalov
    const shelfSelect = document.getElementById('auditShelfFilter');
    if (shelfSelect) {
      const currentSelected = auditSession.shelfFilter || 'all';
      const sortedShelves = Array.from(shelfSet).sort((a, b) => a.localeCompare(b, 'sl-SI'));

      let optionsHtml = '<option value="all">Vsi regali</option>';
      sortedShelves.forEach(shelf => {
        const isSel = (shelf === currentSelected) ? 'selected' : '';
        optionsHtml += `<option value="${escapeHtml(shelf)}" ${isSel}>${escapeHtml(shelf)}</option>`;
      });
      shelfSelect.innerHTML = optionsHtml;
    }

    // Posodobi statusne gumbe
    const filterChips = document.querySelectorAll('#auditFilterChips .chip');
    filterChips.forEach(chip => {
      chip.classList.toggle('active', chip.dataset.auditFilter === auditSession.statusFilter);
    });

    updateAuditStats();
    renderAuditList();
  } catch (err) {
    console.error('Napaka pri nalaganju inventurne seje:', err);
    showToast('Napaka pri nalaganju inventure: ' + err.message);
  }
}

function saveAuditSession() {
  try {
    localStorage.setItem(AUDIT_STORAGE_KEY, JSON.stringify(auditSession));
  } catch (e) {
    console.warn('Ne morem shraniti inventurne seje v localStorage:', e);
  }
}

function updateAuditStats() {
  const entries = auditSession.entries || [];
  const activeShelf = auditSession.shelfFilter || 'all';
  const filtered = (activeShelf === 'all')
    ? entries
    : entries.filter(e => e.locName.toLowerCase() === activeShelf.toLowerCase());

  const total = filtered.length;
  const counted = filtered.filter(e => e.isCounted).length;
  const match = filtered.filter(e => e.isCounted && e.countedQty === e.bookQty).length;
  const diff = filtered.filter(e => e.isCounted && e.countedQty !== e.bookQty).length;

  const totalEl = document.getElementById('auditStatTotal');
  const countedEl = document.getElementById('auditStatCounted');
  const matchEl = document.getElementById('auditStatMatch');
  const diffEl = document.getElementById('auditStatDiff');

  if (totalEl) totalEl.textContent = total;
  if (countedEl) countedEl.textContent = counted;
  if (matchEl) matchEl.textContent = match;
  if (diffEl) diffEl.textContent = diff;
}

function getAuditEntryStatus(entry) {
  if (!entry.isCounted) return 'uncounted';
  if (entry.countedQty === entry.bookQty) return 'match';
  return 'diff';
}

function renderAuditList() {
  const container = document.getElementById('auditItemsList');
  if (!container) return;

  const entries = auditSession.entries || [];
  const shelf = auditSession.shelfFilter || 'all';
  const statusFilter = auditSession.statusFilter || 'all';

  const list = entries.filter(entry => {
    // Filter regala
    if (shelf !== 'all' && entry.locName.toLowerCase() !== shelf.toLowerCase()) {
      return false;
    }
    // Filter statusa
    const status = getAuditEntryStatus(entry);
    if (statusFilter === 'diff' && status !== 'diff') return false;
    if (statusFilter === 'uncounted' && status !== 'uncounted') return false;
    if (statusFilter === 'match' && status !== 'match') return false;
    return true;
  });

  if (list.length === 0) {
    container.innerHTML = `
      <div class="empty-box" style="padding: 32px 16px; text-align: center;">
        <p style="color: var(--text-muted); font-size: 0.95rem;">Ni postavk za izbrane filtre.</p>
      </div>
    `;
    return;
  }

  container.innerHTML = list.map(entry => {
    const status = getAuditEntryStatus(entry);
    const diff = entry.countedQty - entry.bookQty;
    const keyAttr = escapeHtml(entry.key);

    let diffBadgeHtml = '';
    if (!entry.isCounted) {
      diffBadgeHtml = `<span class="audit-diff-badge uncounted">Neprešteto</span>`;
    } else if (diff === 0) {
      diffBadgeHtml = `<span class="audit-diff-badge match">Usklajeno (0)</span>`;
    } else if (diff < 0) {
      diffBadgeHtml = `<span class="audit-diff-badge deficit">Manjko (${diff})</span>`;
    } else {
      diffBadgeHtml = `<span class="audit-diff-badge surplus">Višek (+${diff})</span>`;
    }

    return `
      <div class="audit-card status-${status}" id="auditCard_${keyAttr}">
        <div style="display: flex; justify-content: space-between; align-items: flex-start; gap: 8px;">
          <div style="flex: 1;">
            <div style="display: flex; align-items: center; gap: 6px; margin-bottom: 4px; flex-wrap: wrap;">
              <span class="item-sku-badge">${escapeHtml(entry.sku)}</span>
              <span class="chip" style="padding: 2px 7px; font-size: 0.72rem; border-color: rgba(56, 189, 248, 0.3); color: #38bdf8;">
                Škatla ${entry.boxNum}/${entry.boxTotal}
              </span>
              <span class="location-badge" style="font-size: 0.75rem;">
                ${escapeHtml(entry.locName)}
              </span>
            </div>
            <div style="font-weight: 700; font-size: 0.95rem; color: #fff; line-height: 1.3;">
              ${escapeHtml(entry.name)}
            </div>
          </div>
        </div>

        <div class="audit-counter-row">
          <div style="font-size: 0.85rem; color: var(--text-muted);">
            Knjižno: <strong style="color: #fff; font-size: 0.95rem;">${entry.bookQty}</strong> kos
          </div>

          <div class="audit-counter-ctrl">
            <button type="button" class="btn-audit-step" onclick="changeAuditCount('${keyAttr}', -1)">-</button>
            <input type="number" class="audit-count-input" id="auditInput_${keyAttr}" value="${entry.isCounted ? entry.countedQty : ''}" placeholder="0" min="0" onchange="setAuditCountExact('${keyAttr}', this.value)" onfocus="this.select()">
            <button type="button" class="btn-audit-step" onclick="changeAuditCount('${keyAttr}', 1)">+</button>
            <button type="button" class="chip" style="margin-left: 4px; padding: 6px 9px; font-size: 0.75rem; border-color: rgba(16, 185, 129, 0.4); color: #10b981; font-weight: 700;" onclick="matchAuditBook('${keyAttr}')" title="Nastavi enako kot knjižno">
              = Knjižno
            </button>
          </div>

          <div id="auditDiff_${keyAttr}">
            ${diffBadgeHtml}
          </div>
        </div>
      </div>
    `;
  }).join('');
}

function updateAuditCardDOM(entry) {
  const keyAttr = escapeHtml(entry.key);
  const card = document.getElementById(`auditCard_${keyAttr}`);
  const input = document.getElementById(`auditInput_${keyAttr}`);
  const diffDiv = document.getElementById(`auditDiff_${keyAttr}`);

  if (input) {
    input.value = entry.isCounted ? entry.countedQty : '';
  }

  const status = getAuditEntryStatus(entry);
  if (card) {
    card.className = `audit-card status-${status}`;
  }

  if (diffDiv) {
    const diff = entry.countedQty - entry.bookQty;
    if (!entry.isCounted) {
      diffDiv.innerHTML = `<span class="audit-diff-badge uncounted">Neprešteto</span>`;
    } else if (diff === 0) {
      diffDiv.innerHTML = `<span class="audit-diff-badge match">Usklajeno (0)</span>`;
    } else if (diff < 0) {
      diffDiv.innerHTML = `<span class="audit-diff-badge deficit">Manjko (${diff})</span>`;
    } else {
      diffDiv.innerHTML = `<span class="audit-diff-badge surplus">Višek (+${diff})</span>`;
    }
  }
}

window.changeAuditCount = function(key, delta) {
  const entry = auditSession.entries.find(e => e.key === key);
  if (!entry) return;

  if (!entry.isCounted) {
    entry.countedQty = Math.max(0, delta > 0 ? 1 : 0);
  } else {
    entry.countedQty = Math.max(0, (Number(entry.countedQty) || 0) + delta);
  }
  entry.isCounted = true;

  saveAuditSession();
  updateAuditStats();
  updateAuditCardDOM(entry);
};

window.setAuditCountExact = function(key, val) {
  const entry = auditSession.entries.find(e => e.key === key);
  if (!entry) return;

  if (val === '' || val === null) {
    entry.isCounted = false;
    entry.countedQty = 0;
  } else {
    entry.isCounted = true;
    entry.countedQty = Math.max(0, parseInt(val, 10) || 0);
  }

  saveAuditSession();
  updateAuditStats();
  updateAuditCardDOM(entry);
};

window.matchAuditBook = function(key) {
  const entry = auditSession.entries.find(e => e.key === key);
  if (!entry) return;

  entry.countedQty = entry.bookQty;
  entry.isCounted = true;

  saveAuditSession();
  updateAuditStats();
  updateAuditCardDOM(entry);
  playSuccessBeep();
  showToast(`Potrjeno: ${entry.sku} = ${entry.bookQty} kos`);
};

function handleAuditScan(skuInput) {
  const cleanSku = String(skuInput).trim().replace(/\D/g, '').substring(0, 7) || String(skuInput).trim();
  const entries = auditSession.entries || [];

  // Poišči vse postavke s tem SKU
  let matches = entries.filter(e => e.sku === cleanSku || e.sku.includes(cleanSku));
  if (matches.length === 0) {
    showToast(`Artikel s šifro "${skuInput}" ni bil najden.`);
    return;
  }

  // Če je aktiven filter regala, zožimo
  const activeShelf = auditSession.shelfFilter || 'all';
  if (activeShelf !== 'all') {
    const shelfMatches = matches.filter(e => e.locName.toLowerCase() === activeShelf.toLowerCase());
    if (shelfMatches.length > 0) {
      matches = shelfMatches;
    }
  }

  const target = matches[0];
  window.changeAuditCount(target.key, 1);

  // Pomakni se do kartice in jo označi
  const card = document.getElementById(`auditCard_${escapeHtml(target.key)}`);
  if (card) {
    card.scrollIntoView({ behavior: 'smooth', block: 'center' });
    card.style.outline = '2px solid #38bdf8';
    setTimeout(() => { card.style.outline = ''; }, 1200);
  }

  playSuccessBeep();
  showToast(`Prešteto +1: ${target.name} (${target.countedQty} kos)`);
}

async function resetAuditSession() {
  if (confirm('Ali res želite ponastaviti popis? Vse preštete vrednosti se bodo izbrisale.')) {
    localStorage.removeItem(AUDIT_STORAGE_KEY);
    await loadAuditSession(true);
    showToast('Inventurni popis ponastavljen.');
  }
}

async function applyAuditToInventory() {
  const entries = auditSession.entries || [];
  if (entries.length === 0) {
    showToast('Ni postavk za uveljavitev.');
    return;
  }

  const countedEntries = entries.filter(e => e.isCounted);
  if (countedEntries.length === 0) {
    showToast('Še niste prešteli nobene postavke.');
    return;
  }

  const diffEntries = countedEntries.filter(e => e.countedQty !== e.bookQty);
  const uncountedCount = entries.filter(e => !e.isCounted).length;

  if (diffEntries.length === 0) {
    showToast('Vse preštete postavke se ujemajo s knjižno zalogo. Spremembe niso potrebne.');
    return;
  }

  let confirmMsg = `Uveljavitev inventurnega popisa:\n\n` +
    `• Število postavk z odstopanjem: ${diffEntries.length}\n` +
    `• Preštetih brez razlik: ${countedEntries.length - diffEntries.length}\n`;

  if (uncountedCount > 0) {
    confirmMsg += `• Še nepreštetih: ${uncountedCount} (njihova zaloga se NE bo spreminjala)\n`;
  }
  confirmMsg += `\nAli želite uveljaviti novo stanje v zalogo? To bo zabeleženo v dnevnik premikov.`;

  if (!confirm(confirmMsg)) return;

  try {
    showToast('Usklajujem zalogo...');
    const result = await DB.applyAuditResults(entries);

    showToast(`Zaloga usklajena. Posodobljenih ${result.totalAdjustments} postavk.`);
    playSuccessBeep();

    // Počistimo sejo in naložimo sveže podatke
    localStorage.removeItem(AUDIT_STORAGE_KEY);
    await refreshInventory();
    await refreshLogs();
    await loadAuditSession(true);
  } catch (err) {
    console.error('Napaka pri uveljavljanju inventure:', err);
    showToast('Napaka pri uveljavljanju: ' + err.message);
  }
}

window.exportAuditPDF = async function exportAuditPDF() {
  try {
    showToast('Pripravljam PDF zapisnik inventure...');
    if (!auditSession.entries || auditSession.entries.length === 0) {
      await loadAuditSession();
    }
    const entries = auditSession.entries || [];
    if (entries.length === 0) {
      showToast('Ni podatkov za izpis.');
      return;
    }

    if (typeof window.jspdf === 'undefined' || !window.jspdf.jsPDF) {
      showToast('jsPDF knjižnica ni na voljo.');
      return;
    }

    const { jsPDF } = window.jspdf;
    const doc = new jsPDF({
      orientation: 'portrait',
      unit: 'mm',
      format: 'a4'
    });

    const pageWidth = 210;
    const pageHeight = 297;
    const margin = 12;
    const contentWidth = pageWidth - (margin * 2); // 186mm

    let currentPage = 1;
    let y = 12;

    const activeShelf = auditSession.shelfFilter || 'all';
    const filteredEntries = (activeShelf === 'all')
      ? entries
      : entries.filter(e => (e.locName || '').toLowerCase() === activeShelf.toLowerCase());

    const totalPositions = filteredEntries.length;
    const countedPositions = filteredEntries.filter(e => e.isCounted).length;
    const matchPositions = filteredEntries.filter(e => e.isCounted && e.countedQty === e.bookQty).length;
    const diffPositions = filteredEntries.filter(e => e.isCounted && e.countedQty !== e.bookQty).length;

    let totalDeficit = 0;
    let totalSurplus = 0;
    filteredEntries.forEach(e => {
      if (e.isCounted) {
        const d = e.countedQty - e.bookQty;
        if (d < 0) totalDeficit += Math.abs(d);
        if (d > 0) totalSurplus += d;
      }
    });

    function drawPageHeader(pageNo) {
      if (pageNo === 1) {
        // Glavni pas naslova
        doc.setFillColor(15, 23, 42); // #0f172a
        doc.rect(margin, y, contentWidth, 22, 'F');

        doc.setTextColor(255, 255, 255);
        doc.setFont('helvetica', 'bold');
        doc.setFontSize(13);
        doc.text('INVENTURNI ZAPISNIK SKLADISCA', margin + 6, y + 8.5);

        doc.setFont('helvetica', 'normal');
        doc.setFontSize(8.2);
        doc.setTextColor(148, 163, 184);
        const nowStr = new Date().toLocaleDateString('sl-SI') + ' ob ' + new Date().toLocaleTimeString('sl-SI', { hour: '2-digit', minute: '2-digit' });
        const shelfText = (activeShelf === 'all') ? 'Vsi regali' : activeShelf;
        doc.text(`Uradni popis zaloge | Datum: ${nowStr} | Lokacija: ${shelfText}`, margin + 6, y + 16);

        y += 25;

        // Povzetek v glavi (samo 1. stran)
        doc.setFillColor(241, 245, 249);
        doc.rect(margin, y, contentWidth, 10, 'F');
        doc.setDrawColor(203, 213, 225);
        doc.rect(margin, y, contentWidth, 10, 'S');

        doc.setFont('helvetica', 'bold');
        doc.setFontSize(7.5);
        doc.setTextColor(30, 41, 59);

        const colW = contentWidth / 5;
        doc.text(`POZICIJ: ${totalPositions}`, margin + 4, y + 6.5);
        doc.text(`PRESTETO: ${countedPositions}/${totalPositions}`, margin + colW + 2, y + 6.5);
        doc.text(`USKLAJENO: ${matchPositions}`, margin + (colW * 2) + 2, y + 6.5);
        doc.setTextColor(185, 28, 28);
        doc.text(`MANJKO: -${totalDeficit} kos`, margin + (colW * 3) + 2, y + 6.5);
        doc.setTextColor(217, 119, 6);
        doc.text(`VISEK: +${totalSurplus} kos`, margin + (colW * 4) + 2, y + 6.5);

        y += 13;
      } else {
        // Kompaktna glava za nadaljnje strani
        doc.setFillColor(15, 23, 42);
        doc.rect(margin, y, contentWidth, 10, 'F');

        doc.setTextColor(255, 255, 255);
        doc.setFont('helvetica', 'bold');
        doc.setFontSize(9.5);
        doc.text('INVENTURNI ZAPISNIK SKLADISCA (Nadaljevanje)', margin + 6, y + 6.5);

        y += 12;
      }

      // Glava tabele z jasnimi, ločenimi stolpci
      doc.setFillColor(30, 41, 59);
      doc.rect(margin, y, contentWidth, 8, 'F');

      doc.setFont('helvetica', 'bold');
      doc.setFontSize(7.6);
      doc.setTextColor(255, 255, 255);

      doc.text('CRTNA KODA & SIFRA', margin + 3, y + 5.5);
      doc.text('NAZIV ARTIKLA', margin + 42, y + 5.5);
      doc.text('SKATLA', margin + 94, y + 5.5);
      doc.text('REGAL / LOKACIJA', margin + 110, y + 5.5);
      doc.text('KNJIZNO', margin + 144, y + 5.5);
      doc.text('PRESTETO', margin + 158, y + 5.5);
      doc.text('RAZLIKA', margin + 172, y + 5.5);

      y += 8.5;
    }

    function drawFooter(pageNo) {
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(7.5);
      doc.setTextColor(148, 163, 184);
      doc.text(`Skladisce - Inventurni zapisnik - Stran ${pageNo}`, margin, pageHeight - 6);
    }

    drawPageHeader(1);

    let rowIndex = 0;
    for (const entry of filteredEntries) {
      const rowHeight = 15;
      if (y + rowHeight > pageHeight - 22) {
        drawFooter(currentPage);
        doc.addPage();
        currentPage++;
        y = 12;
        drawPageHeader(currentPage);
      }

      const isDiff = entry.isCounted && (entry.countedQty !== entry.bookQty);
      if (isDiff) {
        doc.setFillColor(254, 242, 242);
        doc.rect(margin, y - 0.5, contentWidth, rowHeight, 'F');
      } else if (rowIndex % 2 === 1) {
        doc.setFillColor(248, 250, 252);
        doc.rect(margin, y - 0.5, contentWidth, rowHeight, 'F');
      }

      doc.setDrawColor(226, 232, 240);
      doc.line(margin, y + rowHeight - 0.5, margin + contentWidth, y + rowHeight - 0.5);

      // 1. Črtna koda
      const barcodeData = generateBarcodeDataUrl(entry.sku);
      if (barcodeData) {
        doc.addImage(barcodeData, 'PNG', margin + 2, y + 0.5, 36, 12);
      } else {
        doc.setFont('helvetica', 'bold');
        doc.setFontSize(8.5);
        doc.setTextColor(15, 23, 42);
        doc.text(String(entry.sku), margin + 3, y + 7);
      }

      // 2. Naziv artikla (z omejitvijo širine)
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(7.8);
      doc.setTextColor(15, 23, 42);
      const cleanName = (entry.name || '').replace(/[\u010d\u010c]/g, 'c').replace(/[\u0161\u0160]/g, 's').replace(/[\u017e\u017d]/g, 'z');
      const splitName = doc.splitTextToSize(cleanName, 49);
      doc.text(splitName, margin + 42, y + 5);

      // 3. Škatla
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(7.5);
      doc.setTextColor(100, 116, 139);
      doc.text(`${entry.boxNum}/${entry.boxTotal}`, margin + 95, y + 6);

      // 4. Regal / Lokacija (z omejitvijo širine)
      const cleanLoc = (entry.locName || 'Brez lokacije').replace(/[\u010d\u010c]/g, 'c').replace(/[\u0161\u0160]/g, 's').replace(/[\u017e\u017d]/g, 'z');
      const splitLoc = doc.splitTextToSize(cleanLoc, 31);
      doc.text(splitLoc, margin + 110, y + 5);

      // 5. Knjižno
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(8);
      doc.setTextColor(15, 23, 42);
      doc.text(`${entry.bookQty}`, margin + 146, y + 6);

      // 6. Prešteto
      doc.setFont('helvetica', 'bold');
      if (!entry.isCounted) {
        doc.setTextColor(148, 163, 184);
        doc.text('-', margin + 160, y + 6);
      } else {
        doc.setTextColor(15, 23, 42);
        doc.text(`${entry.countedQty}`, margin + 160, y + 6);
      }

      // 7. Razlika
      doc.setFontSize(7.6);
      if (!entry.isCounted) {
        doc.setFont('helvetica', 'normal');
        doc.setTextColor(148, 163, 184);
        doc.text('Neprest.', margin + 172, y + 6);
      } else {
        const diff = entry.countedQty - entry.bookQty;
        if (diff === 0) {
          doc.setTextColor(16, 185, 129);
          doc.text('0 (Uskl.)', margin + 172, y + 6);
        } else if (diff < 0) {
          doc.setTextColor(220, 38, 38);
          doc.text(`${diff} kos`, margin + 172, y + 6);
        } else {
          doc.setTextColor(217, 119, 6);
          doc.text(`+${diff} kos`, margin + 172, y + 6);
        }
      }

      y += rowHeight;
      rowIndex++;
    }

    // Podpisna komisija na dnu
    if (y + 32 > pageHeight - 16) {
      drawFooter(currentPage);
      doc.addPage();
      currentPage++;
      y = 16;
      drawPageHeader(currentPage);
    } else {
      y += 6;
    }

    doc.setFillColor(248, 250, 252);
    doc.rect(margin, y, contentWidth, 22, 'F');
    doc.setDrawColor(203, 213, 225);
    doc.rect(margin, y, contentWidth, 22, 'S');

    doc.setFont('helvetica', 'bold');
    doc.setFontSize(7.8);
    doc.setTextColor(30, 41, 59);
    doc.text('POTRDITEV INVENTURNE KOMISIJE:', margin + 4, y + 5);

    const sigW = contentWidth / 3;
    doc.setFont('helvetica', 'normal');
    doc.setFontSize(7.2);
    doc.setTextColor(100, 116, 139);

    doc.text('Vodja komisije: ____________________', margin + 4, y + 15);
    doc.text('Clan komisije: ____________________', margin + sigW + 4, y + 15);
    doc.text('Vodja skladisca: ____________________', margin + (sigW * 2) + 4, y + 15);

    drawFooter(currentPage);

    const fileName = `Inventurni_zapisnik_${getDateStamp()}.pdf`;
    downloadFile(doc.output('blob'), fileName, 'application/pdf');
    showToast('Inventurni zapisnik PDF prenesen.');
  } catch (err) {
    console.error('Napaka pri generiranju inventurnega PDF:', err);
    showToast('Napaka pri PDF: ' + err.message);
  }
}

/* =========================================================================
   7. NASTAVITVE, IZVOZ & UVOZ PODATKOV
   ========================================================================= */
function setupSettingsAndBackup() {
  document.getElementById('btnClearLogs').addEventListener('click', async () => {
    if (confirm('Ali res želite počistiti zgodovino premikov?')) {
      await DB.clearLogs();
      await refreshLogs();
      showToast('Zgodovina očiščena.');
    }
  });

  // Ročno sprožena deduplikacija (združitev dvojnikov)
  const btnDeduplicate = document.getElementById('btnDeduplicate');
  if (btnDeduplicate) {
    btnDeduplicate.addEventListener('click', async () => {
      try {
        showToast('Preverjam dvojnike v bazi...');
        const res = await DB.deduplicateItems();
        if (res.removedDuplicatesCount > 0) {
          showToast(`Združeni ${res.mergedGroupsCount} artikli (odstranjenih ${res.removedDuplicatesCount} dvojnikov).`);
        } else {
          showToast('V bazi ni podvojenih artiklov.');
        }
        await refreshInventory();
      } catch (err) {
        console.error('Napaka pri deduplikaciji:', err);
        showToast('Napaka pri preverjanju dvojnikov: ' + err.message);
      }
    });
  }

  // Izvoz v CSV
  document.getElementById('btnExportCSV').addEventListener('click', async () => {
    const items = await DB.getAllItems();
    if (items.length === 0) {
      showToast('Zaloga je prazna.');
      return;
    }

    let csv = '\uFEFF'; // UTF-8 BOM za pravilen prikaz č, š, ž v Excelu
    csv += 'Koda;Naziv;Kategorija;Lokacija;Kolicina;MinimalnaZaloga;Enota;Opombe;ZadnjaPosodobitev\n';

    items.forEach(i => {
      const line = [
        sanitizeCSV(i.sku),
        sanitizeCSV(i.name),
        sanitizeCSV(i.category),
        sanitizeCSV(i.location),
        i.quantity,
        i.minQuantity,
        sanitizeCSV(i.unit),
        sanitizeCSV(i.notes),
        i.lastUpdated
      ].join(';');
      csv += line + '\n';
    });

    downloadFile(csv, `skladisce-zaloga-${getDateStamp()}.csv`, 'text/csv;charset=utf-8;');
    showToast('CSV datoteka pripravljena za prenos.');
  });

  // Izvoz v JSON
  document.getElementById('btnExportJSON').addEventListener('click', async () => {
    const items = await DB.getAllItems();
    const logs = await DB.getAllLogs();
    const backup = {
      version: 1,
      createdAt: new Date().toISOString(),
      items,
      logs
    };

    downloadFile(JSON.stringify(backup, null, 2), `skladisce-backup-${getDateStamp()}.json`, 'application/json');
    showToast('Varnostna kopija shranjena.');
  });

  // Uvoz JSON
  const importInput = document.getElementById('importFileInput');
  document.getElementById('btnTriggerImport').addEventListener('click', () => {
    importInput.click();
  });

  importInput.addEventListener('change', async (e) => {
    const file = e.target.files[0];
    if (!file) return;

    try {
      const text = await file.text();
      const data = JSON.parse(text);

      if (data && Array.isArray(data.items)) {
        if (confirm(`Uvozil boš ${data.items.length} artiklov. Želiš nadaljevati?`)) {
          for (const item of data.items) {
            delete item.id;
            await DB.addItem(item);
          }
          showToast(`Uvoženih ${data.items.length} artiklov.`);
          await refreshInventory();
        }
      } else {
        alert('Neveljavna datoteka varnostne kopije.');
      }
    } catch (err) {
      alert('Napaka pri branju datoteke: ' + err.message);
    }
    importInput.value = '';
  });

  // Hitri PDF izpisi (Kompleti vs Nepopolne škatle)
  const btnPdfComplete = document.getElementById('btnPdfComplete');
  if (btnPdfComplete) btnPdfComplete.addEventListener('click', exportCompleteSetsPDF);

  const btnQuickPdfComplete = document.getElementById('btnQuickPdfComplete');
  if (btnQuickPdfComplete) btnQuickPdfComplete.addEventListener('click', exportCompleteSetsPDF);

  const btnPdfIncomplete = document.getElementById('btnPdfIncomplete');
  if (btnPdfIncomplete) btnPdfIncomplete.addEventListener('click', exportIncompleteSetsPDF);

  const btnQuickPdfIncomplete = document.getElementById('btnQuickPdfIncomplete');
  if (btnQuickPdfIncomplete) btnQuickPdfIncomplete.addEventListener('click', exportIncompleteSetsPDF);

  // Naloži Demo
  document.getElementById('btnLoadDemo').addEventListener('click', () => {
    loadDemoData();
  });

  // Izbris vseh artiklov (Počisti bazo)
  const btnClearAll = document.getElementById('btnClearAllItems');
  if (btnClearAll) {
    btnClearAll.addEventListener('click', async () => {
      const confirmed = confirm(
        'Ali ste prepričani, da želite izbrisati vse artikle in zalogo iz skladišča?'
      );
      if (!confirmed) return;

      try {
        await DB.clearAllItems();
        localStorage.setItem('skladisce_demo_seeded', 'true'); // Da se demo artikli ne naložijo ponovno
        showToast('Vsi artikli in zaloge so bili izbrisani.');
        await refreshInventory();
        await refreshLogs();
      } catch (err) {
        console.error('Napaka pri brisanju artiklov:', err);
        showToast('Napaka pri brisanju artiklov: ' + err.message);
      }
    });
  }
}

window.loadDemoData = async function() {
  await DB.seedDemoData();
  localStorage.setItem('skladisce_demo_seeded', 'true');
  showToast('Vzorčni artikli naloženi.');
  await refreshInventory();
};

/* =========================================================================
   7b. BREZZICNA BLE SINHRONIZACIJA
   ========================================================================= */
let autoSyncDebounceTimer = null;
let lastAutoSyncPeerInfo = null;

function updateAutoSyncHeaderUI(status, peerId = '', details = null) {
  const dot = document.getElementById('bleHeaderDot');
  const text = document.getElementById('btnHeaderBLESyncText');
  const devId = (typeof DB !== 'undefined' && DB.getDeviceId) ? DB.getDeviceId().toUpperCase() : 'BLE';

  if (!dot || !text) return;

  if (status === 'syncing') {
    dot.className = 'ble-dot ble-dot-syncing';
    text.textContent = 'Usklajujem...';
  } else if (status === 'synced') {
    dot.className = 'ble-dot ble-dot-synced';
    text.textContent = `Usklajeno (#${peerId ? peerId.toUpperCase() : 'OK'})`;
    lastAutoSyncPeerInfo = {
      peerId: peerId ? peerId.toUpperCase() : '',
      timestamp: new Date().toLocaleTimeString('sl-SI')
    };
    setTimeout(() => {
      dot.className = 'ble-dot ble-dot-ready';
      text.textContent = `Mesh #${devId}`;
    }, 7000);
  } else {
    dot.className = 'ble-dot ble-dot-ready';
    text.textContent = `Mesh #${devId}`;
  }

  if (details && (details.updatedCount > 0 || details.addedLogsCount > 0 || details.addedCount > 0)) {
    showToast(`🟢 Samodejna uskladitev s sodelavcem: ${details.updatedCount} stanj, ${details.addedLogsCount} premikov.`);
  }
}

window.triggerAutoSyncPayloadUpdate = function() {
  if (!isCapacitorNative() || !window.Capacitor?.Plugins?.BLESync) return;
  clearTimeout(autoSyncDebounceTimer);
  autoSyncDebounceTimer = setTimeout(async () => {
    try {
      const dbVersion = DB.getDbVersion();
      const stateHash = DB.getSyncHash();
      const payload = await DB.getSyncPayload();
      await window.Capacitor.Plugins.BLESync.updateSyncPayload({
        dbVersion: dbVersion,
        stateHash: stateHash,
        data: JSON.stringify(payload)
      });
    } catch (e) {
      console.warn('Update BLE payload error:', e);
    }
  }, 300);
};
window.onLocalDatabaseMutated = window.triggerAutoSyncPayloadUpdate;

async function initAutoBLESync() {
  if (!isCapacitorNative() || !window.Capacitor?.Plugins?.BLESync) {
    updateAutoSyncHeaderUI('ready');
    return;
  }

  try {
    const deviceId = DB.getDeviceId();
    const dbVersion = DB.getDbVersion();
    const stateHash = DB.getSyncHash();
    const payload = await DB.getSyncPayload();
    const jsonStr = JSON.stringify(payload);

    await window.Capacitor.Plugins.BLESync.startAutoSync({
      deviceId: deviceId,
      dbVersion: dbVersion,
      stateHash: stateHash,
      data: jsonStr
    });

    window.Capacitor.Plugins.BLESync.addListener('autoSyncReceived', async (event) => {
      if (!event || !event.json) return;
      try {
        const incomingData = JSON.parse(event.json);
        const result = await DB.mergeSyncData(incomingData);

        await refreshInventory();
        await refreshLogs();
        if (typeof renderReplenishmentList === 'function') renderReplenishmentList();
        if (typeof renderMixedBoxesList === 'function') renderMixedBoxesList();

        if (navigator.vibrate) navigator.vibrate([40, 30, 40]);
        playSuccessBeep();

        updateAutoSyncHeaderUI('synced', event.peerId, result);

        window.triggerAutoSyncPayloadUpdate();
      } catch (err) {
        console.warn('Napaka pri obdelavi BLE uskladitve:', err);
      }
    });

    window.Capacitor.Plugins.BLESync.addListener('autoSyncStatus', (st) => {
      updateAutoSyncHeaderUI(st.status, st.peerId);
    });

    updateAutoSyncHeaderUI('ready');
  } catch (err) {
    console.warn('Auto BLE Sync init error:', err);
  }
}

function setupBLESync() {
  const btnHeader = document.getElementById('btnHeaderBLESync');
  const btnSend = document.getElementById('btnOpenBLESend');
  const btnReceive = document.getElementById('btnOpenBLEReceive');
  const btnCancel = document.getElementById('btnCancelBLESync');
  const btnClose = document.getElementById('btnCloseBLESyncModal');

  if (btnHeader) {
    btnHeader.addEventListener('click', () => {
      openBLESyncChoice();
    });
  }

  if (btnSend) {
    btnSend.addEventListener('click', () => {
      startBLESending();
    });
  }

  if (btnReceive) {
    btnReceive.addEventListener('click', () => {
      startBLEReceiving();
    });
  }

  if (btnCancel) {
    btnCancel.addEventListener('click', () => {
      cancelBLESync();
    });
  }

  if (btnClose) {
    btnClose.addEventListener('click', () => {
      cancelBLESync();
    });
  }

  // Zaženi avtomatsko BLE MESH sinhronizacijo v ozadju
  setTimeout(() => {
    initAutoBLESync();
    checkNativeIncomingSync();
  }, 1200);
}

function openBLESyncChoice() {
  const modal = document.getElementById('modalBLESync');
  const title = document.getElementById('bleSyncStatusTitle');
  const desc = document.getElementById('bleSyncStatusDesc');
  const summary = document.getElementById('bleSyncSummaryBox');
  const radar = document.getElementById('bleRadarPulse');
  const cancelBtn = document.getElementById('btnCancelBLESync');

  if (!modal) return;

  const devId = DB.getDeviceId().toUpperCase();
  const v = DB.getDbVersion();
  const lastSyncStr = lastAutoSyncPeerInfo
    ? `Zadnja uskladitev: z napravo #${lastAutoSyncPeerInfo.peerId} ob ${lastAutoSyncPeerInfo.timestamp}`
    : 'Čakam na zaznavo prve naprave v bližini...';

  radar.style.display = 'none';
  summary.style.display = 'block';
  summary.innerHTML = `
    <div style="background: rgba(16, 185, 129, 0.1); border: 1px solid rgba(16, 185, 129, 0.3); border-radius: 10px; padding: 12px; margin-bottom: 12px; text-align: left;">
      <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">
        <span class="ble-dot ble-dot-ready"></span>
        <strong style="color: #34d399; font-size: 0.95rem;">Samodejna sinhronizacija je AKTIVNA</strong>
      </div>
      <div style="font-size: 0.8rem; color: var(--text-muted); line-height: 1.4;">
        Ko prideta dva delavca v bližino (~10–15 m), se podatki samodejno uskladijo v ozadju (0 klikov, brez Wi-Fi).
      </div>
      <div style="margin-top: 8px; font-size: 0.76rem; color: #94a3b8; display: flex; justify-content: space-between;">
        <span>ID naprave: <strong style="color:#fff;">#${devId}</strong></span>
        <span>Verzija baze: <strong style="color:#fff;">${v}</strong></span>
      </div>
      <div style="margin-top: 4px; font-size: 0.74rem; color: #38bdf8;">
        ${lastSyncStr}
      </div>
    </div>

    <div style="display: flex; flex-direction: column; gap: 10px; margin-top: 6px;">
      <button class="btn-primary" onclick="forceTriggerBLESync()" style="margin: 0; padding: 12px; font-weight: 700; font-size: 0.9rem; background: #0284c7;">
        ⚡ Prisilno uskladi zdaj (Osveži paket)
      </button>
      <div style="display: flex; gap: 8px;">
        <button class="btn-secondary" onclick="startBLESending()" style="margin: 0; flex: 1; padding: 10px; font-size: 0.82rem;">
          Pošlji (ročno)
        </button>
        <button class="btn-secondary" onclick="startBLEReceiving()" style="margin: 0; flex: 1; padding: 10px; font-size: 0.82rem; color: #38bdf8; border-color: rgba(56, 189, 248, 0.5);">
          Prejmi (ročno)
        </button>
      </div>
    </div>
  `;

  title.textContent = 'Bluetooth Mesh sinhronizacija';
  desc.textContent = 'Aplikacija samodejno sinhronizira podatke med telefoni v ozadju brez Wi-Fi omrežja.';
  cancelBtn.textContent = 'Zapri';
  modal.style.display = 'flex';
}

async function forceTriggerBLESync() {
  if (window.triggerAutoSyncPayloadUpdate) {
    window.triggerAutoSyncPayloadUpdate();
    showToast('Paket za BLE oddajanje je bil osvežen. Čakam bližnje naprave...');
  }
}
window.forceTriggerBLESync = forceTriggerBLESync;

async function startBLESending() {
  const modal = document.getElementById('modalBLESync');
  const title = document.getElementById('bleSyncStatusTitle');
  const desc = document.getElementById('bleSyncStatusDesc');
  const summary = document.getElementById('bleSyncSummaryBox');
  const radar = document.getElementById('bleRadarPulse');
  const radarIcon = document.getElementById('bleRadarIcon');
  const cancelBtn = document.getElementById('btnCancelBLESync');

  modal.style.display = 'flex';
  radar.style.display = 'flex';
  radarIcon.textContent = 'BLE';
  summary.style.display = 'none';
  cancelBtn.textContent = 'Prekliči';

  title.textContent = 'Oddajam zalogo prek BLE...';
  desc.textContent = 'Na drugi napravi izberite "Prejmi zalogo". Čakam povezavo...';

  try {
    const payload = await DB.getSyncPayload();
    const jsonStr = JSON.stringify(payload);

    if (isCapacitorNative() && window.Capacitor?.Plugins?.BLESync) {
      const res = await window.Capacitor.Plugins.BLESync.startSending({ data: jsonStr });
      if (res && res.success) {
        radar.style.display = 'none';
        summary.style.display = 'block';
        title.textContent = 'Podatki poslani';
        desc.textContent = 'Druga naprava je prejela stanje zaloge in premike.';
        summary.innerHTML = `
          <div style="color: #34d399; font-weight: 700; margin-bottom: 4px;">Prenos zaključen</div>
          <div>Oddanih ${payload.articles?.length || 0} artiklov in ${payload.logs?.length || 0} zapisov premikov.</div>
        `;
        playSuccessBeep();
        cancelBtn.textContent = 'Zaključi';
      }
    } else {
      radar.style.display = 'none';
      summary.style.display = 'block';
      title.textContent = 'BLE zahteva nameščen APK';
      desc.textContent = 'Bluetooth povezava deluje na Android napravi (APK).';
    }
  } catch (err) {
    console.warn('BLE Send error:', err);
    radar.style.display = 'none';
    title.textContent = 'Oddajanje prekinjeno';
    desc.textContent = err.message || String(err);
  }
}

async function startBLEReceiving() {
  const modal = document.getElementById('modalBLESync');
  const title = document.getElementById('bleSyncStatusTitle');
  const desc = document.getElementById('bleSyncStatusDesc');
  const summary = document.getElementById('bleSyncSummaryBox');
  const radar = document.getElementById('bleRadarPulse');
  const radarIcon = document.getElementById('bleRadarIcon');
  const cancelBtn = document.getElementById('btnCancelBLESync');

  modal.style.display = 'flex';
  radar.style.display = 'flex';
  radarIcon.textContent = 'BLE';
  summary.style.display = 'none';
  cancelBtn.textContent = 'Prekliči';

  title.textContent = 'Iščem napravo v bližini...';
  desc.textContent = 'Približajte napravi (1–5 metrov).';

  try {
    if (isCapacitorNative() && window.Capacitor?.Plugins?.BLESync) {
      const res = await window.Capacitor.Plugins.BLESync.startReceiving();
      if (res && res.json) {
        title.textContent = 'Usklajujem bazo zaloge...';
        desc.textContent = 'Prejeti podatki se obdelujejo...';

        const parsed = JSON.parse(res.json);
        const mergeResult = await DB.mergeSyncData(parsed);

        await refreshInventory();
        await refreshLogs();
        if (typeof renderReplenishmentList === 'function') renderReplenishmentList();
        if (typeof renderMixedBoxesList === 'function') renderMixedBoxesList();
        playSuccessBeep();

        radar.style.display = 'none';
        summary.style.display = 'block';
        title.textContent = 'Zaloga usklajena';
        desc.textContent = 'Podatki o zalogi in premikih so posodobljeni.';

        let newNamesHtml = '';
        if (mergeResult.addedItemNames && mergeResult.addedItemNames.length > 0) {
          newNamesHtml = `<div style="margin-top: 6px; font-size: 0.78rem; color: #38bdf8;">Novi artikli: ${mergeResult.addedItemNames.slice(0, 3).join(', ')}${mergeResult.addedItemNames.length > 3 ? '...' : ''}</div>`;
        }

        summary.innerHTML = `
          <div style="font-weight: 700; color: #34d399; margin-bottom: 6px;">Posodobljeno:</div>
          <div><strong>${mergeResult.addedCount}</strong> novih artiklov dodanih v bazo</div>
          <div><strong>${mergeResult.updatedCount}</strong> stanj na policah posodobljenih</div>
          <div><strong>${mergeResult.addedLogsCount}</strong> novih zapisov v zgodovino premikov</div>
          ${newNamesHtml}
        `;
        cancelBtn.textContent = 'Zapri';
      }
    } else {
      radar.style.display = 'none';
      summary.style.display = 'block';
      title.textContent = 'BLE zahteva nameščen APK';
      desc.textContent = 'Bluetooth povezava deluje na Android napravi (APK).';
    }
  } catch (err) {
    console.warn('BLE Receive error:', err);
    radar.style.display = 'none';
    title.textContent = 'Iskanje prekinjeno';
    desc.textContent = err.message || String(err);
  }
}

function cancelBLESync() {
  if (isCapacitorNative() && window.Capacitor?.Plugins?.BLESync) {
    try {
      window.Capacitor.Plugins.BLESync.cancel();
    } catch (e) {}
  }
  const modal = document.getElementById('modalBLESync');
  if (modal) modal.style.display = 'none';
}

async function checkNativeIncomingSync() {
  if (isCapacitorNative() && window.Capacitor?.Plugins?.NativeFileSaver) {
    try {
      const res = await window.Capacitor.Plugins.NativeFileSaver.checkIncomingSync();
      if (res && res.hasData && res.jsonData) {
        const parsed = JSON.parse(res.jsonData);
        if (parsed) {
          showToast('Prejet nov paket zaloge. Usklajujem...');
          const result = await DB.mergeSyncData(parsed);
          await refreshInventory();
          await refreshLogs();
          playSuccessBeep();
          showToast(`Zaloga usklajena. Posodobljenih ${result.updatedCount} stanj.`);
        }
      }
    } catch (e) {
      console.warn('Napaka pri preverjanju dohodnega paketa:', e);
    }
  }
}

window.startBLESending = startBLESending;
window.startBLEReceiving = startBLEReceiving;
window.cancelBLESync = cancelBLESync;

/* =========================================================================
   8. GENERIRANJE PDF IZPISOV
   ========================================================================= */
function generateBarcodeDataUrl(sku) {
  try {
    if (typeof JsBarcode === 'undefined') return null;
    const canvas = document.createElement('canvas');
    JsBarcode(canvas, String(sku).trim(), {
      format: 'CODE128',
      width: 2,
      height: 42,
      displayValue: true,
      fontSize: 13,
      fontOptions: 'bold',
      margin: 4,
      background: '#ffffff',
      lineColor: '#000000'
    });
    return canvas.toDataURL('image/png');
  } catch (e) {
    console.warn('Napaka pri generiranju črtne kode za', sku, e);
    return null;
  }
}

window.exportCompleteSetsPDF = async function exportCompleteSetsPDF() {
  try {
    showToast('Pripravljam PDF s popolnimi kompleti...');
    const allItems = await DB.getAllItems();

    // V ta izpis gredo SAMO POPOLNI KOMPLETI (kjer so vse škatle na voljo)
    const items = allItems.filter(item => {
      const stats = DB.getBoxStats(item);
      return stats.completeSets > 0;
    });

    if (typeof window.jspdf === 'undefined' || !window.jspdf.jsPDF) {
      showToast('jsPDF knjižnica ni naložena.');
      return;
    }

    const { jsPDF } = window.jspdf;
    const doc = new jsPDF({
      orientation: 'portrait',
      unit: 'mm',
      format: 'a4'
    });

    const pageWidth = 210;
    const pageHeight = 297;
    const margin = 14;
    const contentWidth = pageWidth - (margin * 2); // 182mm

    let currentPage = 1;
    let y = 16;

    function drawHeader() {
      // Header pas
      doc.setFillColor(15, 23, 42); // Temno modra
      doc.rect(margin, y, contentWidth, 22, 'F');

      doc.setTextColor(255, 255, 255);
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(13);
      doc.text('SKLADISCE - CELOTNA ZALOGA (POPOLNI KOMPLETI)', margin + 6, y + 9);

      doc.setFont('helvetica', 'normal');
      doc.setFontSize(8.5);
      doc.setTextColor(148, 163, 184);
      const nowStr = new Date().toLocaleDateString('sl-SI') + ' ob ' + new Date().toLocaleTimeString('sl-SI', { hour: '2-digit', minute: '2-digit' });
      doc.text(`Izpis samo celih kompletov (vse sestavne skatle na zalogi) | Datum: ${nowStr}`, margin + 6, y + 16);

      y += 26;

      // Glava tabele: Stolpci (Koda s crtno kodo, Ime, Lokacija skatel, Kolicina)
      doc.setFillColor(241, 245, 249);
      doc.rect(margin, y, contentWidth, 8, 'F');
      doc.setDrawColor(203, 213, 225);
      doc.rect(margin, y, contentWidth, 8, 'S');

      doc.setFont('helvetica', 'bold');
      doc.setFontSize(8.5);
      doc.setTextColor(30, 41, 59);

      doc.text('CRTNA KODA & SIFRA', margin + 3, y + 5.5);
      doc.text('IME ARTIKLA', margin + 50, y + 5.5);
      doc.text('LOKACIJA SKATEL', margin + 115, y + 5.5);
      doc.text('KOLICINA', margin + 158, y + 5.5);

      y += 9;
    }

    function drawFooter(pageNo) {
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(8);
      doc.setTextColor(148, 163, 184);
      doc.text(`Skladisce - Porocilo popolnih kompletov - Stran ${pageNo}`, margin, pageHeight - 8);
    }

    if (items.length === 0) {
      drawHeader();
      y += 8;
      doc.setFillColor(254, 242, 242);
      doc.rect(margin, y, contentWidth, 24, 'F');
      doc.setDrawColor(239, 68, 68);
      doc.rect(margin, y, contentWidth, 24, 'S');
      
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(11);
      doc.setTextColor(153, 27, 27);
      doc.text('NI POPOLNIH KOMPLETOV NA ZALOGI', margin + 8, y + 10);
      
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(8.5);
      doc.setTextColor(185, 28, 28);
      doc.text('Trenutno na zalogi v skladiscu ni nobenega artikla s popolnimi vsemi skatlami.', margin + 8, y + 18);
      
      drawFooter(currentPage);
      const fileName = `skladisce-popolni-kompleti-${getDateStamp()}.pdf`;
      await downloadFile(doc.output('blob'), fileName, 'application/pdf');
      showToast('PDF s popolnimi kompleti shranjen.');
      return;
    }

    drawHeader();

    let totalSetsSum = 0;
    let rowIndex = 0;

    for (const item of items) {
      const stats = DB.getBoxStats(item);
      const completeQty = stats.completeSets;
      totalSetsSum += completeQty;

      const boxLines = stats.breakdown.map(b => 
        (stats.boxTotal > 1) 
          ? `Skatla ${b.boxNum}/${stats.boxTotal}: ${b.location} (${b.count} kos)`
          : `${b.location}`
      );
      const rowHeight = Math.max(18, 8 + (boxLines.length * 4.5));

      if (y + rowHeight > pageHeight - 16) {
        drawFooter(currentPage);
        doc.addPage();
        currentPage++;
        y = 16;
        drawHeader();
      }

      if (rowIndex % 2 === 1) {
        doc.setFillColor(248, 250, 252);
        doc.rect(margin, y - 1, contentWidth, rowHeight, 'F');
      }
      doc.setDrawColor(226, 232, 240);
      doc.line(margin, y + rowHeight - 1, margin + contentWidth, y + rowHeight - 1);

      // 1. Crtna koda & Sifra
      const barcodeData = generateBarcodeDataUrl(item.sku);
      if (barcodeData) {
        doc.addImage(barcodeData, 'PNG', margin + 2, y + 1, 42, 13);
      } else {
        doc.setFont('helvetica', 'bold');
        doc.setFontSize(10);
        doc.setTextColor(15, 23, 42);
        doc.text(String(item.sku), margin + 4, y + 8);
      }

      // 2. Ime artikla
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(9);
      doc.setTextColor(15, 23, 42);
      const splitName = doc.splitTextToSize(item.name, 60);
      doc.text(splitName, margin + 50, y + 4.5);

      doc.setFont('helvetica', 'normal');
      doc.setFontSize(7.5);
      doc.setTextColor(100, 116, 139);
      const subInfo = `${item.category || 'Splosno'}${stats.boxTotal > 1 ? ` | ${stats.boxTotal}-delna garnitura` : ''}`;
      doc.text(subInfo, margin + 50, y + 4.5 + (splitName.length * 4));

      // 3. Lokacija skatel
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(7.8);
      doc.setTextColor(30, 41, 59);
      let locY = y + 4.5;
      boxLines.forEach(line => {
        const splitLine = doc.splitTextToSize(line, 41);
        doc.text(splitLine, margin + 115, locY);
        locY += splitLine.length * 4.0;
      });

      // 4. Kolicina
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(11);
      doc.setTextColor(16, 185, 129);
      doc.text(`${completeQty}`, margin + 158, y + 6);
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(7.5);
      doc.setTextColor(100, 116, 139);
      doc.text(`${item.unit || (stats.boxTotal > 1 ? 'kompletov' : 'kos')}`, margin + 158, y + 10.5);

      y += rowHeight;
      rowIndex++;
    }

    if (y + 18 > pageHeight - 16) {
      drawFooter(currentPage);
      doc.addPage();
      currentPage++;
      y = 16;
    }

    y += 4;
    doc.setFillColor(241, 245, 249);
    doc.rect(margin, y, contentWidth, 13, 'F');
    doc.setDrawColor(148, 163, 184);
    doc.rect(margin, y, contentWidth, 13, 'S');

    doc.setFont('helvetica', 'bold');
    doc.setFontSize(9.5);
    doc.setTextColor(15, 23, 42);
    doc.text(`SKUPAJ: ${items.length} artiklov z zalogo`, margin + 6, y + 8.5);
    doc.text(`Vseh celih kompletov: ${totalSetsSum} kos/garnitur`, margin + 100, y + 8.5);

    drawFooter(currentPage);

    const fileName = `skladisce-popolni-kompleti-${getDateStamp()}.pdf`;
    downloadFile(doc.output('blob'), fileName, 'application/pdf');
    showToast(`PDF s popolnimi kompleti shranjen.`);
  } catch (err) {
    console.error('Napaka pri generiranju PDF kompletov:', err);
    showToast('Napaka pri generiranju PDF: ' + err.message);
  }
}

window.exportIncompleteSetsPDF = async function exportIncompleteSetsPDF() {
  try {
    showToast('Pripravljam PDF z nepopolnimi škatlami...');
    const allItems = await DB.getAllItems();

    // V ta izpis gredo NEPOPOLNE ŠKATLE (kjer je samo en del škatel / manjkajo deli)
    const items = allItems.filter(item => {
      const stats = DB.getBoxStats(item);
      return stats.hasIncomplete;
    });

    if (typeof window.jspdf === 'undefined' || !window.jspdf.jsPDF) {
      showToast('jsPDF knjižnica ni naložena.');
      return;
    }

    const { jsPDF } = window.jspdf;
    const doc = new jsPDF({
      orientation: 'portrait',
      unit: 'mm',
      format: 'a4'
    });

    const pageWidth = 210;
    const pageHeight = 297;
    const margin = 12;
    const contentWidth = pageWidth - (margin * 2); // 186mm

    let currentPage = 1;
    let y = 14;

    function drawHeader() {
      // Header pas (Amber barva)
      doc.setFillColor(180, 83, 9); // #b45309
      doc.rect(margin, y, contentWidth, 22, 'F');

      doc.setTextColor(255, 255, 255);
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(13);
      doc.text('POROCILO: NEPOPOLNE SKATLE IN MANJKAJOCI DELI', margin + 6, y + 8.5);

      doc.setFont('helvetica', 'normal');
      doc.setFontSize(8.2);
      doc.setTextColor(254, 243, 199);
      const nowStr = new Date().toLocaleDateString('sl-SI') + ' ob ' + new Date().toLocaleTimeString('sl-SI', { hour: '2-digit', minute: '2-digit' });
      doc.text(`Artikli z manjkajocimi skatlami za sestavo celotnega kompleta | Datum: ${nowStr}`, margin + 6, y + 16);

      y += 26;

      // Glava tabele z jasnimi razmiki
      doc.setFillColor(254, 243, 199);
      doc.rect(margin, y, contentWidth, 8, 'F');
      doc.setDrawColor(245, 158, 11);
      doc.rect(margin, y, contentWidth, 8, 'S');

      doc.setFont('helvetica', 'bold');
      doc.setFontSize(8);
      doc.setTextColor(146, 64, 14);

      doc.text('CRTNA KODA & SIFRA', margin + 3, y + 5.5);
      doc.text('IME ARTIKLA', margin + 44, y + 5.5);
      doc.text('STANJE SKATEL & LOKACIJE', margin + 95, y + 5.5);
      doc.text('MANJKA / STANJE', margin + 148, y + 5.5);

      y += 8.5;
    }

    function drawFooter(pageNo) {
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(7.5);
      doc.setTextColor(148, 163, 184);
      doc.text(`Skladisce - Porocilo nepopolnih skatel - Stran ${pageNo}`, margin, pageHeight - 6);
    }

    if (items.length === 0) {
      drawHeader();
      y += 8;
      doc.setFillColor(240, 253, 244);
      doc.rect(margin, y, contentWidth, 24, 'F');
      doc.setDrawColor(34, 197, 94);
      doc.rect(margin, y, contentWidth, 24, 'S');
      
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(11);
      doc.setTextColor(22, 101, 52);
      doc.text('VSE SKATLE SO POPOLNE - NI MANJKAJOCIH DELOV', margin + 8, y + 10);
      
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(8.5);
      doc.setTextColor(21, 128, 61);
      doc.text('Vsi artikli na zalogi imajo usklajene vse sestavne dele skatel. Noben artikel nima primanjkljaja.', margin + 8, y + 18);
      
      drawFooter(currentPage);
      const fileName = `skladisce-nepopolne-skatle-${getDateStamp()}.pdf`;
      await downloadFile(doc.output('blob'), fileName, 'application/pdf');
      showToast('PDF z nepopolnimi škatlami shranjen.');
      return;
    }

    drawHeader();

    let rowIndex = 0;

    for (const item of items) {
      const stats = DB.getBoxStats(item);

      const boxLines = stats.breakdown.map(b => 
        `Skatla ${b.boxNum}/${stats.boxTotal}: ${b.count} kos (${b.location})`
      );

      // 2. Ime artikla (priprava vrstic za preračun višine)
      const cleanName = (item.name || '').replace(/[\u010d\u010c]/g, 'c').replace(/[\u0161\u0160]/g, 's').replace(/[\u017e\u017d]/g, 'z');
      const splitName = doc.splitTextToSize(cleanName, 48);

      // 3. Stanje škatel (ovite vrstice)
      const wrappedBoxLines = [];
      boxLines.forEach(line => {
        const cleanLine = line.replace(/[\u010d\u010c]/g, 'c').replace(/[\u0161\u0160]/g, 's').replace(/[\u017e\u017d]/g, 'z');
        const splitBox = doc.splitTextToSize(cleanLine, 50);
        splitBox.forEach(s => wrappedBoxLines.push(s));
      });

      // 4. Manjka / Stanje
      const cleanMissing = (stats.missingSummary || 'Nepopolno').replace(/[\u010d\u010c]/g, 'c').replace(/[\u0161\u0160]/g, 's').replace(/[\u017e\u017d]/g, 'z');
      const splitMissing = doc.splitTextToSize(cleanMissing, 35);

      // Dinamična višina vrstice, da se besedilo nikoli ne seka ali reže
      const nameLinesCount = splitName.length + 1;
      const boxLinesCount = wrappedBoxLines.length;
      const missingLinesCount = splitMissing.length + 1;
      const maxContentLines = Math.max(nameLinesCount, boxLinesCount, missingLinesCount, 3);
      const rowHeight = Math.max(18, 6 + (maxContentLines * 4.2));

      if (y + rowHeight > pageHeight - 20) {
        drawFooter(currentPage);
        doc.addPage();
        currentPage++;
        y = 14;
        drawHeader();
      }

      if (rowIndex % 2 === 1) {
        doc.setFillColor(255, 251, 235);
        doc.rect(margin, y - 0.5, contentWidth, rowHeight, 'F');
      }
      doc.setDrawColor(253, 230, 138);
      doc.line(margin, y + rowHeight - 0.5, margin + contentWidth, y + rowHeight - 0.5);

      // 1. Crtna koda & Sifra
      const barcodeData = generateBarcodeDataUrl(item.sku);
      if (barcodeData) {
        doc.addImage(barcodeData, 'PNG', margin + 2, y + 1, 38, 12);
      } else {
        doc.setFont('helvetica', 'bold');
        doc.setFontSize(8.5);
        doc.setTextColor(15, 23, 42);
        doc.text(String(item.sku), margin + 4, y + 8);
      }

      // 2. Ime artikla
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(8.2);
      doc.setTextColor(15, 23, 42);
      doc.text(splitName, margin + 44, y + 4.5);

      doc.setFont('helvetica', 'normal');
      doc.setFontSize(7.2);
      doc.setTextColor(100, 116, 139);
      doc.text(`${item.category || 'Splosno'} | ${stats.boxTotal}-delni`, margin + 44, y + 4.5 + (splitName.length * 3.8));

      // 3. Stanje skatel & lokacije (strogo omejeno na širino 50mm, brez prekrivanja!)
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(7.5);
      doc.setTextColor(30, 41, 59);
      let locY = y + 4.5;
      wrappedBoxLines.forEach(line => {
        doc.text(line, margin + 95, locY);
        locY += 4.0;
      });

      // 4. Manjka / Stanje (pri margin + 148, ločeno od stolpca 3)
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(7.8);
      doc.setTextColor(217, 119, 6);
      doc.text(splitMissing, margin + 148, y + 4.5);

      doc.setFont('helvetica', 'normal');
      doc.setFontSize(7.2);
      doc.setTextColor(71, 85, 105);
      doc.text(`Celih: ${stats.completeSets} kpl`, margin + 148, y + 5.5 + (splitMissing.length * 3.8));

      y += rowHeight;
      rowIndex++;
    }

    if (y + 18 > pageHeight - 16) {
      drawFooter(currentPage);
      doc.addPage();
      currentPage++;
      y = 14;
      drawHeader();
    }

    y += 4;
    doc.setFillColor(254, 243, 199);
    doc.rect(margin, y, contentWidth, 12, 'F');
    doc.setDrawColor(245, 158, 11);
    doc.rect(margin, y, contentWidth, 12, 'S');

    doc.setFont('helvetica', 'bold');
    doc.setFontSize(8.5);
    doc.setTextColor(146, 64, 14);
    doc.text(`OPOZORILO: ${items.length} artiklov ima nepopolne skatle in potrebuje dopolnitev.`, margin + 6, y + 7.5);

    drawFooter(currentPage);

    const fileName = `skladisce-nepopolne-skatle-${getDateStamp()}.pdf`;
    downloadFile(doc.output('blob'), fileName, 'application/pdf');
    showToast(`PDF z nepopolnimi škatlami shranjen.`);
  } catch (err) {
    console.error('Napaka pri generiranju PDF nepopolnih škatel:', err);
    showToast('Napaka pri generiranju PDF: ' + err.message);
  }
}

/* =========================================================================
   POMOŽNE FUNKCIJE
   ========================================================================= */
function showToast(msg) {
  const toast = document.getElementById('toast');
  toast.textContent = msg;
  toast.classList.add('show');
  setTimeout(() => {
    toast.classList.remove('show');
  }, 2800);
}

function escapeHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

function sanitizeCSV(str) {
  if (!str) return '""';
  const clean = String(str).replace(/"/g, '""');
  return `"${clean}"`;
}

function getDateStamp() {
  const d = new Date();
  return d.toISOString().slice(0, 10);
}

function blobToBase64(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => resolve(reader.result);
    reader.onerror = reject;
    reader.readAsDataURL(blob);
  });
}

async function downloadFile(content, fileName, mimeType) {
  // Če tečemo v Capacitor APK (Android)
  if (isCapacitorNative() && window.Capacitor?.Plugins?.NativeFileSaver) {
    try {
      showToast('Shranjujem v Prenose (Downloads)...');
      let base64 = '';
      if (content instanceof Blob) {
        base64 = await blobToBase64(content);
      } else if (typeof content === 'string') {
        const textBlob = new Blob([content], { type: mimeType || 'text/plain;charset=utf-8;' });
        base64 = await blobToBase64(textBlob);
      }

      await window.Capacitor.Plugins.NativeFileSaver.saveAndOpenFile({
        fileName: fileName,
        base64: base64,
        mimeType: mimeType || 'application/pdf'
      });

      showToast(`Shranjeno v Prenose: ${fileName}`);
      playSuccessBeep();
      return;
    } catch (e) {
      console.warn('NativeFileSaver napaka:', e);
      showToast('Napaka pri shranjevanju: ' + (e.message || e));
    }
  }

  // Spletni način (PWA v brskalniku)
  const blob = (content instanceof Blob) ? content : new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 3000);
}

/* =========================================================================
   IZVOZ QR NALEPK ZA REGALE IN POLICE SKLADIŠČA (PDF - 48 LOKACIJ)
   ========================================================================= */
window.exportWarehouseShelfQRPDF = async function() {
  if (typeof window.jspdf === 'undefined' || !window.jspdf.jsPDF) {
    showToast('jsPDF knjižnica ni naložena.');
    return;
  }
  if (typeof qrcode === 'undefined') {
    showToast('QR knjižnica ni naložena.');
    return;
  }

  showToast('Pripravljam PDF z QR nalepkami za 48 polic...');

  try {
    const { jsPDF } = window.jspdf;
    const doc = new jsPDF({
      orientation: 'portrait',
      unit: 'mm',
      format: 'a4'
    });

    // Seznam vseh 48 lokacij po tlorisu skladišča
    const locations = [];

    // 1. Osrednji dvostranski regali R1 do R6
    for (let r = 1; r <= 6; r++) {
      ['A', 'B'].forEach(side => {
        const sideName = (side === 'A') ? 'Stran A (Sever / Zgoraj)' : 'Stran B (Jug / Spodaj)';
        for (let s = 1; s <= 3; s++) {
          const shelfName = (s === 1) ? 'Spodnja polica (P1)' : (s === 2) ? 'Srednja polica (P2)' : 'Zgornja polica (P3)';
          locations.push({
            code: `R${r}-${side}-P${s}`,
            rack: `Regal ${r}`,
            side: sideName,
            shelf: shelfName,
            category: 'Osrednji regal'
          });
        }
      });
    }

    // 2. Stenski enojni regali ob kletki
    const wallRacks = [
      { id: 'SZ', name: 'Stena Zgoraj (Severna stena)' },
      { id: 'SS', name: 'Stena Spodaj (Južna stena)' },
      { id: 'SL1', name: 'Stena Levo - Nad vrati' },
      { id: 'SL2', name: 'Stena Levo - Pod vrati' }
    ];

    wallRacks.forEach(w => {
      for (let s = 1; s <= 3; s++) {
        const shelfName = (s === 1) ? 'Spodnja polica (P1)' : (s === 2) ? 'Srednja polica (P2)' : 'Zgornja polica (P3)';
        locations.push({
          code: `${w.id}-P${s}`,
          rack: w.name,
          side: 'Enojni regal ob kletki',
          shelf: shelfName,
          category: 'Stenski regal'
        });
      }
    });

    // Helper za generiranje čiste PNG slike QR kode
    function makeQRPng(text, size = 180) {
      const qr = qrcode(0, 'M');
      qr.addData(text);
      qr.make();
      const count = qr.getModuleCount();
      const canvas = document.createElement('canvas');
      canvas.width = size;
      canvas.height = size;
      const ctx = canvas.getContext('2d');
      ctx.fillStyle = '#FFFFFF';
      ctx.fillRect(0, 0, size, size);
      ctx.fillStyle = '#000000';
      const cell = size / count;
      for (let r = 0; r < count; r++) {
        for (let c = 0; c < count; c++) {
          if (qr.isDark(r, c)) {
            ctx.fillRect(c * cell, r * cell, cell, cell);
          }
        }
      }
      return canvas.toDataURL('image/png');
    }

    // Postavitev: A4 (210 x 297 mm)
    // 6 nalepk na stran: 2 stolpca x 3 vrstice
    const cardW = 90;
    const cardH = 82;
    const marginX = 11;
    const marginY = 16;
    const gapX = 8;
    const gapY = 8;
    const cardsPerPage = 6;

    locations.forEach((loc, index) => {
      const posInPage = index % cardsPerPage;

      if (posInPage === 0 && index > 0) {
        doc.addPage();
      }

      const col = posInPage % 2;
      const row = Math.floor(posInPage / 2);

      const x = marginX + col * (cardW + gapX);
      const y = marginY + row * (cardH + gapY);

      // Okvir nalepke (siv rob)
      doc.setDrawColor(180, 185, 195);
      doc.setLineWidth(0.4);
      doc.roundedRect(x, y, cardW, cardH, 3, 3, 'S');

      // Zgornji pas (glava nalepke)
      doc.setFillColor(30, 41, 59);
      doc.roundedRect(x, y, cardW, 11, 3, 3, 'F');
      doc.rect(x, y + 6, cardW, 5, 'F');

      doc.setFont('helvetica', 'bold');
      doc.setFontSize(8);
      doc.setTextColor(255, 255, 255);
      doc.text('SKLADIŠČE • OZNAKA POLICE', x + cardW / 2, y + 7, { align: 'center' });

      // QR koda
      const qrPng = makeQRPng(`REGAL:${loc.code}`, 180);
      const qrSize = 36;
      const qrX = x + 5;
      const qrY = y + 17;
      doc.addImage(qrPng, 'PNG', qrX, qrY, qrSize, qrSize);

      // Desna stran: Glavna šifra in podatki
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(18);
      doc.setTextColor(15, 23, 42);
      doc.text(loc.code, x + 44, y + 25);

      doc.setFont('helvetica', 'bold');
      doc.setFontSize(9);
      doc.setTextColor(3, 105, 161);
      doc.text(loc.rack, x + 44, y + 33);

      doc.setFont('helvetica', 'normal');
      doc.setFontSize(7.5);
      doc.setTextColor(71, 85, 105);
      doc.text(loc.side, x + 44, y + 39);

      doc.setFont('helvetica', 'bold');
      doc.setFontSize(8.5);
      doc.setTextColor(15, 23, 42);
      doc.text(loc.shelf, x + 44, y + 46);

      // Tip regala
      doc.setFillColor(241, 245, 249);
      doc.roundedRect(x + 44, y + 50, 40, 5.5, 1.5, 1.5, 'F');
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(6.5);
      doc.setTextColor(100, 116, 139);
      doc.text(loc.category, x + 46, y + 54);

      // Spodnja črta in navodilo
      doc.setDrawColor(226, 232, 240);
      doc.setLineWidth(0.2);
      doc.line(x + 4, y + 66, x + cardW - 4, y + 66);

      doc.setFont('helvetica', 'italic');
      doc.setFontSize(6.2);
      doc.setTextColor(148, 163, 184);
      doc.text('Kamera samodejno zazna QR in nastavi regal za prevzem', x + cardW / 2, y + 74, { align: 'center' });
    });

    // Glava dokumenta na prvi strani zgoraj
    doc.setPage(1);
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(9);
    doc.setTextColor(100, 116, 139);
    doc.text('SKLADIŠČNI REGALI IN POLICE - TISK NALEPK (48 LOKACIJ)', 105, 9, { align: 'center' });

    const fileName = `Skladisce_QR_Regali_${getDateStamp()}.pdf`;
    await downloadFile(doc.output('blob'), fileName, 'application/pdf');
    showToast('PDF z QR nalepkami uspešno prenesen.');
  } catch (err) {
    console.error('Napaka pri generiranju QR nalepk regala:', err);
    showToast('Napaka pri generiranju PDF nalepk: ' + (err.message || err));
  }
};

/* =========================================================================
   POLNJENJE POLIC & NABIRANJE (STORE REPLENISHMENT)
   ========================================================================= */
let currentRepWarehouseFilter = 'all';
let currentRepScannedItem = null;

function setupReplenishmentListeners() {
  const repScanInput = document.getElementById('repScanInput');
  const btnRepLookup = document.getElementById('btnRepLookup');
  const repHitCapacity = document.getElementById('repHitCapacity');
  const repHitCurrent = document.getElementById('repHitCurrent');
  const btnRepAddToList = document.getElementById('btnRepAddToList');
  const repWhFilterChips = document.getElementById('repWhFilterChips');

  if (btnRepLookup && repScanInput) {
    btnRepLookup.addEventListener('click', () => {
      lookupArticleForReplenishment(repScanInput.value.trim());
    });
    repScanInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        lookupArticleForReplenishment(repScanInput.value.trim());
      }
    });
  }

  function updateNeededCalculation() {
    const cap = Math.max(0, parseInt(repHitCapacity?.value, 10) || 0);
    const curr = Math.max(0, parseInt(repHitCurrent?.value, 10) || 0);
    const needed = Math.max(0, cap - curr);
    const neededEl = document.getElementById('repHitNeeded');
    if (neededEl) neededEl.textContent = `${needed} kos`;
  }

  if (repHitCapacity) repHitCapacity.addEventListener('input', updateNeededCalculation);
  if (repHitCurrent) repHitCurrent.addEventListener('input', updateNeededCalculation);

  if (btnRepAddToList) {
    btnRepAddToList.addEventListener('click', async () => {
      if (!currentRepScannedItem) return;
      const cap = Math.max(0, parseInt(repHitCapacity?.value, 10) || 0);
      const curr = Math.max(0, parseInt(repHitCurrent?.value, 10) || 0);
      const needed = Math.max(0, cap - curr);

      if (needed <= 0) {
        showToast('Kapaciteta police je že dosežena.');
        return;
      }

      try {
        await DB.addToReplenishment({
          sku: currentRepScannedItem.sku,
          name: currentRepScannedItem.name,
          neededQty: needed,
          currentShelfQty: curr,
          targetShelf: currentRepScannedItem.shelfLocation || 'Prodajne police'
        });

        // Posodobi kapaciteto police na artiklu v bazi, če je spremenjena
        if (cap !== currentRepScannedItem.shelfCapacity) {
          currentRepScannedItem.shelfCapacity = cap;
          await DB.updateItem(currentRepScannedItem);
        }

        showToast(`Dodan na nabiralni seznam (${needed} kos)!`);
        if (navigator.vibrate) navigator.vibrate(50);
        document.getElementById('repHitCard').style.display = 'none';
        if (repScanInput) repScanInput.value = '';
        currentRepScannedItem = null;
        await refreshReplenishmentTab();
      } catch (err) {
        console.error('Napaka pri dodajanju na nabiralni seznam:', err);
        showToast('Napaka pri dodajanju.');
      }
    });
  }

  if (repWhFilterChips) {
    repWhFilterChips.addEventListener('click', (e) => {
      const btn = e.target.closest('.chip');
      if (!btn) return;
      repWhFilterChips.querySelectorAll('.chip').forEach(c => c.classList.remove('active'));
      btn.classList.add('active');
      currentRepWarehouseFilter = btn.dataset.repFilter || 'all';
      renderReplenishmentList();
    });
  }
}

window.refreshReplenishmentTab = async function() {
  await renderReplenishmentList();
  if (typeof updateMixedBoxesBadges === 'function') updateMixedBoxesBadges();
};

window.lookupArticleForReplenishment = async function(query) {
  if (!query) return;
  const cleanQ = query.trim();
  const card = document.getElementById('repHitCard');
  if (!card) return;

  let item = cachedItems.find(i => i.sku === cleanQ || (i.sku && i.sku.includes(cleanQ)));
  if (!item) {
    try {
      item = await DB.getItemById(cleanQ);
    } catch (e) {}
  }

  if (!item) {
    card.style.display = 'none';
    showToast(`Artikel s šifro "${cleanQ}" ni bil najden.`);
    return;
  }

  currentRepScannedItem = item;

  document.getElementById('repHitName').textContent = item.name;
  document.getElementById('repHitSku').textContent = `# ${item.sku}`;

  const storeQty = Number(item.storeQuantity) || 0;
  const zgornjeQty = Number(item.zgornjeQuantity) || 0;
  const spodnjeQty = Number(item.spodnjeQuantity) || 0;
  const cap = Number(item.shelfCapacity) || 10;
  const needed = Math.max(0, cap - storeQty);

  const locsContainer = document.getElementById('repHitLocations');
  if (locsContainer) {
    let locLines = [];
    locLines.push(`<div>🏬 <strong>Prodajna polica:</strong> ${storeQty} kos ${item.shelfLocation ? `(${item.shelfLocation})` : ''}</div>`);
    if (zgornjeQty > 0) {
      locLines.push(`<div>📦 <strong>Zgornje skladišče (Soft):</strong> ${zgornjeQty} kos ${item.location ? `[${item.location}]` : ''}</div>`);
    }
    if (spodnjeQty > 0) {
      locLines.push(`<div>🛋️ <strong>Spodnje skladišče:</strong> ${spodnjeQty} kos</div>`);
    }
    if (zgornjeQty <= 0 && spodnjeQty <= 0) {
      locLines.push(`<div style="color: #f87171;">⚠️ Ni zaloge v skladišču!</div>`);
    }
    locsContainer.innerHTML = locLines.join('');
  }

  const capInput = document.getElementById('repHitCapacity');
  const currInput = document.getElementById('repHitCurrent');
  if (capInput) capInput.value = cap;
  if (currInput) currInput.value = storeQty;

  const neededEl = document.getElementById('repHitNeeded');
  if (neededEl) neededEl.textContent = `${needed} kos`;

  const badge = document.getElementById('repHitStockBadge');
  if (badge) {
    if (zgornjeQty > 0 || spodnjeQty > 0) {
      badge.className = 'stock-status-badge status-ok';
      badge.textContent = 'Na zalogi v skladišču';
    } else {
      badge.className = 'stock-status-badge status-empty';
      badge.textContent = 'Ni zaloge v skladišču';
    }
  }

  card.style.display = 'block';
};

window.quickAddReplenishFromItem = function(sku) {
  window.switchTab('tab-replenishment');
  const repScanInput = document.getElementById('repScanInput');
  if (repScanInput) {
    repScanInput.value = sku;
    lookupArticleForReplenishment(sku);
  }
};

window.renderReplenishmentList = async function() {
  const container = document.getElementById('replenishmentItemsList');
  const badge = document.getElementById('repListCountBadge');
  if (!container) return;

  try {
    const list = await DB.getReplenishmentList('pending');
    if (badge) badge.textContent = list.length;

    let filtered = list;
    if (currentRepWarehouseFilter !== 'all') {
      filtered = list.filter(it => it.sourceWarehouse === currentRepWarehouseFilter);
    }

    if (filtered.length === 0) {
      container.innerHTML = `
        <div class="empty-box" style="padding: 24px;">
          <p style="font-size: 0.95rem; margin-bottom: 4px;">🎉 Ni artiklov za nabiranje!</p>
          <span style="font-size: 0.78rem; color: var(--text-muted);">Vse police so napolnjene. Skenirajte izdelek ali cenovko na polici za preverbo.</span>
        </div>
      `;
      return;
    }

    container.innerHTML = filtered.map(it => {
      const isZgornje = it.sourceWarehouse === 'zgornje';
      const whName = isZgornje ? '📦 Zgornje skladišče (Soft)' : '🛋️ Spodnje skladišče';
      const boxBadge = it.boxId ? `<span class="wh-pill wh-pill-mixed">📦 Mešana škatla #${escapeHtml(it.boxId)}</span>` : '';

      return `
        <div class="rep-card" data-rep-id="${it.id}">
          <div class="rep-card-header">
            <div>
              <div class="rep-item-title">${escapeHtml(it.name)}</div>
              <div style="display:flex; gap:6px; align-items:center; margin-top:3px;">
                <span class="rep-sku-tag"># ${escapeHtml(it.sku)}</span>
                ${boxBadge}
              </div>
            </div>
            <div class="rep-qty-badge">
              +${it.neededQty} kos
            </div>
          </div>

          <div class="rep-loc-info">
            <span>📍 <strong>Vzemi iz:</strong> ${whName} &bull; <span style="color:#38bdf8; font-weight:700;">${escapeHtml(it.sourceLocation || 'Brez police')}</span></span>
          </div>
          <div style="font-size: 0.76rem; color: var(--text-muted); display:flex; justify-content:space-between; align-items:center;">
            <span>🏬 Ciljna polica: <strong>${escapeHtml(it.targetShelf || 'Prodajne police')}</strong></span>
            <span>Trenutno na polici: ${it.currentShelfQty || 0}</span>
          </div>

          <div class="rep-actions-row">
            <button type="button" class="btn-transfer-shelf" onclick="completeReplenishment(${it.id})" title="Zmanjšaj iz skladišča in prenesi na police">
              ✓ Prenesi na polico
            </button>
            <button type="button" class="btn-sm-action" onclick="deleteReplenishmentItem(${it.id})" style="background: rgba(239, 68, 68, 0.12); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.3); border-radius: var(--radius-sm); padding: 0 12px; cursor: pointer;" title="Odstrani ta nalog">
              ✕
            </button>
          </div>
        </div>
      `;
    }).join('');
  } catch (err) {
    console.error('Napaka pri izrisu seznama za nabiranje:', err);
  }
};

window.completeReplenishment = async function(repId) {
  try {
    await DB.completeReplenishmentItem(repId);
    if (navigator.vibrate) navigator.vibrate([60, 40, 60]);
    showToast('Zaloga uspešno prenesena na polico!');
    await renderReplenishmentList();
    await refreshInventory();
  } catch (err) {
    console.error('Napaka pri prenosu na polico:', err);
    showToast('Napaka pri prenosu: ' + (err.message || err));
  }
};

window.deleteReplenishmentItem = async function(repId) {
  try {
    await DB.removeReplenishmentItem(repId);
    showToast('Postavka odstranjena s seznama.');
    await renderReplenishmentList();
  } catch (err) {
    console.error(err);
  }
};

window.clearCompletedReplenishments = async function() {
  try {
    const all = await DB.getReplenishmentList();
    const completed = all.filter(it => it.status === 'completed');
    for (const c of completed) {
      await DB.removeReplenishmentItem(c.id);
    }
    showToast('Zaključene postavke očiščene.');
    await renderReplenishmentList();
  } catch (err) {
    console.error(err);
  }
};

window.exportReplenishmentPDF = async function() {
  try {
    const list = await DB.getReplenishmentList('pending');
    if (!list || list.length === 0) {
      showToast('Nabiralni seznam je prazen.');
      return;
    }

    if (typeof jspdf === 'undefined' || !jspdf.jsPDF) {
      showToast('Knjižnica jsPDF ni naložena.');
      return;
    }

    const doc = new jspdf.jsPDF({ orientation: 'portrait', unit: 'mm', format: 'a4' });
    const pageWidth = 210;

    // Glava
    doc.setFillColor(15, 23, 42);
    doc.rect(0, 0, pageWidth, 28, 'F');
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(14);
    doc.setTextColor(255, 255, 255);
    doc.text('DELOVNI NALOG - NABIRANJE ZA POLICE TRGOVINE', 14, 13);
    doc.setFont('helvetica', 'normal');
    doc.setFontSize(8.5);
    doc.setTextColor(148, 163, 184);
    doc.text(`Ustvarjeno: ${new Date().toLocaleString('sl-SI')} • Skupaj postavk: ${list.length}`, 14, 21);

    // Tabela
    let y = 36;
    doc.setFillColor(241, 245, 249);
    doc.rect(14, y - 5, pageWidth - 28, 8, 'F');
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(8);
    doc.setTextColor(30, 41, 59);

    doc.text('[✓]', 16, y);
    doc.text('ŠIFRA', 26, y);
    doc.text('ARTIKEL', 50, y);
    doc.text('LOKACIJA V SKLADIŠČU', 115, y);
    doc.text('KOS', 165, y);
    doc.text('CILJ', 180, y);
    y += 7;

    // Razvrsti po skladišču in lokaciji za optimalno nabiranje
    const sorted = [...list].sort((a, b) => {
      const wA = a.sourceWarehouse || '';
      const wB = b.sourceWarehouse || '';
      if (wA !== wB) return wA.localeCompare(wB);
      return (a.sourceLocation || '').localeCompare(b.sourceLocation || '');
    });

    sorted.forEach((it, idx) => {
      if (y > 275) {
        doc.addPage();
        y = 20;
      }

      if (idx % 2 === 1) {
        doc.setFillColor(248, 250, 252);
        doc.rect(14, y - 4, pageWidth - 28, 7, 'F');
      }

      doc.setDrawColor(203, 213, 225);
      doc.rect(16, y - 3.5, 4, 4);

      doc.setFont('helvetica', 'bold');
      doc.setFontSize(8);
      doc.setTextColor(15, 23, 42);
      doc.text(String(it.sku), 26, y);

      doc.setFont('helvetica', 'normal');
      doc.setFontSize(7.5);
      const shortName = (it.name || '').substring(0, 32);
      doc.text(shortName, 50, y);

      const whLabel = it.sourceWarehouse === 'zgornje' ? 'Zgornje' : 'Spodnje';
      const locText = `${whLabel}: ${it.sourceLocation || '-'}${it.boxId ? ` (Škatla #${it.boxId})` : ''}`;
      doc.setFont('helvetica', 'bold');
      doc.text(locText.substring(0, 28), 115, y);

      doc.setFontSize(9);
      doc.setTextColor(2, 132, 199);
      doc.text(`${it.neededQty}x`, 165, y);

      doc.setFontSize(7.5);
      doc.setTextColor(71, 85, 105);
      doc.text((it.targetShelf || 'Police').substring(0, 16), 180, y);

      y += 6.5;
    });

    const fileName = `Nabiralni_Nalog_${getDateStamp()}.pdf`;
    await downloadFile(doc.output('blob'), fileName, 'application/pdf');
    showToast('Delovni nalog za nabiranje prenesen v PDF.');
  } catch (err) {
    console.error('Napaka pri izvozu PDF naloga:', err);
    showToast('Napaka pri izvozu PDF: ' + (err.message || err));
  }
};

/* =========================================================================
   MEŠANE ŠKATLE (MULTI-SKU BOXES & CITIZEN 6x6 NALEPKE)
   ========================================================================= */
let currentPackDraftItems = [];

function setupMixedBoxesListeners() {
  const mixedSearchInput = document.getElementById('mixedSearchInput');
  if (mixedSearchInput) {
    mixedSearchInput.addEventListener('input', () => {
      renderMixedBoxesList();
    });
  }

  const packAddSkuInput = document.getElementById('packAddSkuInput');
  if (packAddSkuInput) {
    packAddSkuInput.addEventListener('input', () => {
      const val = packAddSkuInput.value.trim();
      const prevEl = document.getElementById('packItemPreview');
      if (!val || val.length < 3) {
        if (prevEl) prevEl.textContent = '';
        return;
      }
      const match = cachedItems.find(i => i.sku === val || (i.sku && i.sku.includes(val)) || (i.name && i.name.toLowerCase().includes(val.toLowerCase())));
      if (prevEl) {
        prevEl.textContent = match ? `Najdeno: ${match.name} (#${match.sku})` : 'Artikel še ni prepoznan.';
      }
    });

    packAddSkuInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        addArticleToCurrentPackBox();
      }
    });
  }
}

window.openMixedBoxesModal = async function() {
  const modal = document.getElementById('modalMixedBoxes');
  if (!modal) return;
  modal.style.display = 'flex';
  switchMixedBoxSubTab('list');
  await renderMixedBoxesList();
  updateMixedBoxesBadges();
};

window.closeMixedBoxesModal = function() {
  const modal = document.getElementById('modalMixedBoxes');
  if (modal) modal.style.display = 'none';
};

window.switchMixedBoxSubTab = function(tab) {
  const tabList = document.getElementById('subTabMixedList');
  const tabPack = document.getElementById('subTabMixedPack');
  const btnList = document.getElementById('tabBtnMixedList');
  const btnPack = document.getElementById('tabBtnMixedPack');

  if (tab === 'list') {
    if (tabList) tabList.style.display = 'flex';
    if (tabPack) tabPack.style.display = 'none';
    if (btnList) btnList.classList.add('active');
    if (btnPack) btnPack.classList.remove('active');
    renderMixedBoxesList();
  } else {
    if (tabList) tabList.style.display = 'none';
    if (tabPack) tabPack.style.display = 'flex';
    if (btnList) btnList.classList.remove('active');
    if (btnPack) btnPack.classList.add('active');
    generateNextBoxNumber();
  }
};

window.updateMixedBoxesBadges = async function() {
  try {
    const boxes = await DB.getAllMixedBoxes();
    const count = boxes.length;
    const b1 = document.getElementById('mixedBoxesBadge');
    const b2 = document.getElementById('repMixedBoxesCount');
    const b3 = document.getElementById('mixedListTabBadge');
    if (b1) b1.textContent = count;
    if (b2) b2.textContent = count;
    if (b3) b3.textContent = count;
  } catch (e) {}
};

window.renderMixedBoxesList = async function() {
  const container = document.getElementById('mixedBoxesContainer');
  if (!container) return;

  try {
    const boxes = await DB.getAllMixedBoxes();
    const search = (document.getElementById('mixedSearchInput')?.value || '').trim().toLowerCase();

    let filtered = boxes;
    if (search) {
      filtered = boxes.filter(b => {
        const numMatch = String(b.boxNumber || b.boxId).toLowerCase().includes(search);
        const shelfMatch = (b.shelf || '').toLowerCase().includes(search);
        const itemMatch = (b.items || []).some(it => (it.name || '').toLowerCase().includes(search) || (it.sku || '').includes(search));
        return numMatch || shelfMatch || itemMatch;
      });
    }

    if (filtered.length === 0) {
      container.innerHTML = `
        <div class="empty-box" style="padding: 24px;">
          <p style="font-size:0.95rem;">Ni najdenih mešanih škatel.</p>
          <span style="font-size:0.78rem; color:var(--text-muted);">Kliknite na zavihek "+ Pakiraj novo škatlo" za ustvarjanje prve mešane škatle soft artiklov.</span>
        </div>
      `;
      return;
    }

    container.innerHTML = filtered.map(b => {
      const whName = b.warehouse === 'zgornje' ? '📦 Zgornje skladišče (Soft)' : '🛋️ Spodnje skladišče';
      const items = b.items || [];
      const boxTotal = b.totalItems || items.reduce((s, it) => s + Math.max(1, Number(it.qty || it.quantity || it.count) || 1), 0);

      return `
        <div class="mixed-box-card">
          <div class="mixed-box-header">
            <div class="mixed-box-number">
              <span>📦 Škatla #${escapeHtml(b.boxNumber || b.boxId)}</span>
              <span style="font-size: 0.75rem; background: rgba(56, 189, 248, 0.15); color: #38bdf8; padding: 2px 7px; border-radius: 4px; font-weight: 600;">
                ${boxTotal} kosov
              </span>
            </div>
            <button type="button" class="btn-primary" onclick="printCitizenLabel6x6('${escapeHtml(b.boxId)}')" style="margin:0; width:auto; padding: 6px 12px; font-size: 0.78rem; background: linear-gradient(135deg, #0284c7, #0369a1);" title="Natisni nalepko 6x6 cm na Citizen tiskalnik">
              🖨️ 6×6 nalepka
            </button>
          </div>

          <div style="font-size: 0.8rem; color: var(--text-muted);">
            Lokacija: <strong style="color: #fff;">${whName}</strong> &bull; Regal: <strong style="color: #38bdf8;">${escapeHtml(b.shelf || 'Brez police')}</strong>
          </div>

          <table class="mixed-box-items-table">
            <thead>
              <tr>
                <th>Artikel</th>
                <th style="width: 50px; text-align: center;">Kos</th>
              </tr>
            </thead>
            <tbody>
              ${items.map(it => {
                const itemQty = Math.max(1, Number(it.qty || it.quantity || it.count) || 1);
                return `
                <tr>
                  <td>
                    <div style="font-weight: 600; color: #fff;">${escapeHtml(it.name)}</div>
                    <div style="font-size: 0.72rem; color: var(--primary); font-family: monospace;"># ${escapeHtml(it.sku)}</div>
                  </td>
                  <td style="text-align: center; font-weight: 700; color: #fbbf24;">${itemQty}x</td>
                </tr>
              `;
              }).join('')}
            </tbody>
          </table>

          <div style="display: flex; justify-content: flex-end; gap: 8px; margin-top: 6px;">
            <button type="button" class="btn-sm-action" onclick="unpackMixedBox('${escapeHtml(b.boxId)}')" style="background: rgba(239, 68, 68, 0.12); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.3); border-radius: 6px; padding: 5px 10px; font-size: 0.75rem; cursor: pointer;">
              Razpakiraj škatlo
            </button>
          </div>
        </div>
      `;
    }).join('');
  } catch (err) {
    console.error(err);
  }
};

window.generateNextBoxNumber = async function() {
  try {
    const boxes = await DB.getAllMixedBoxes();
    let maxNum = 100;
    boxes.forEach(b => {
      const n = parseInt(b.boxNumber, 10);
      if (!isNaN(n) && n > maxNum) maxNum = n;
    });
    const nextNum = maxNum + 1;
    const input = document.getElementById('packBoxNumber');
    if (input) input.value = nextNum;
  } catch (e) {}
};

window.addArticleToCurrentPackBox = function() {
  const skuInput = document.getElementById('packAddSkuInput');
  const qtyInput = document.getElementById('packAddQtyInput');
  const preview = document.getElementById('packItemPreview');
  if (!skuInput || !qtyInput) return;

  const val = skuInput.value.trim();
  const qty = parseInt(qtyInput.value, 10) || 1;

  if (!val) {
    showToast('Vpišite ali poskenirajte šifro artikla.');
    return;
  }

  const match = cachedItems.find(i => i.sku === val || (i.sku && i.sku.includes(val)) || (i.name && i.name.toLowerCase().includes(val.toLowerCase())));
  const sku = match ? match.sku : val;
  const name = match ? match.name : `Artikel ${val}`;

  // Preveri, če je artikel že na seznamu
  const existing = currentPackDraftItems.find(i => i.sku === sku);
  if (existing) {
    existing.quantity = (Number(existing.quantity || existing.qty) || 0) + qty;
    existing.qty = existing.quantity;
  } else {
    currentPackDraftItems.push({ sku, name, quantity: qty, qty: qty });
  }

  skuInput.value = '';
  qtyInput.value = '1';
  if (preview) preview.textContent = '';
  renderPackDraftTable();
};

window.removeArticleFromPack = function(idx) {
  currentPackDraftItems.splice(idx, 1);
  renderPackDraftTable();
};

function renderPackDraftTable() {
  const tbody = document.getElementById('packBoxItemsTableBody');
  if (!tbody) return;

  if (currentPackDraftItems.length === 0) {
    tbody.innerHTML = `<tr><td colspan="3" style="text-align: center; color: var(--text-muted); padding: 12px;">Škatla je še prazna.</td></tr>`;
    return;
  }

  tbody.innerHTML = currentPackDraftItems.map((it, idx) => `
    <tr>
      <td>
        <div style="font-weight: 600; color: #fff;">${escapeHtml(it.name)}</div>
        <div style="font-size: 0.72rem; color: var(--primary); font-family: monospace;"># ${escapeHtml(it.sku)}</div>
      </td>
      <td style="text-align: center; font-weight: 700; color: #fbbf24;">${it.quantity}x</td>
      <td style="text-align: center;">
        <button type="button" onclick="removeArticleFromPack(${idx})" style="background:none; border:none; color:#ef4444; font-size:1.1rem; cursor:pointer;">✕</button>
      </td>
    </tr>
  `).join('');
}

window.saveMixedBoxFromForm = async function() {
  const boxNumber = document.getElementById('packBoxNumber')?.value?.trim();
  const warehouse = document.getElementById('packBoxWarehouse')?.value || 'zgornje';
  const shelf = document.getElementById('packBoxShelf')?.value?.trim();

  if (!boxNumber) {
    showToast('Vnesite številko škatle.');
    return;
  }
  if (!shelf) {
    showToast('Vnesite regal / lokacijo škatle.');
    return;
  }
  if (currentPackDraftItems.length === 0) {
    showToast('Dodajte vsaj en artikel v škatlo.');
    return;
  }

  try {
    const boxId = String(boxNumber);
    const boxData = {
      boxId: boxId,
      boxNumber: boxNumber,
      warehouse: warehouse,
      shelf: shelf,
      items: currentPackDraftItems,
      createdAt: new Date().toISOString()
    };

    await DB.saveMixedBox(boxData);
    if (navigator.vibrate) navigator.vibrate([80, 50, 80]);
    showToast(`Mešana škatla #${boxNumber} uspešno shranjena!`);

    // Natisni nalepko 6x6
    await printCitizenLabel6x6(boxId);

    // Ponastavi obrazec
    currentPackDraftItems = [];
    renderPackDraftTable();
    switchMixedBoxSubTab('list');
    await refreshInventory();
  } catch (err) {
    console.error('Napaka pri shranjevanju mešane škatle:', err);
    showToast('Napaka pri shranjevanju: ' + (err.message || err));
  }
};

window.unpackMixedBox = async function(boxId) {
  if (!confirm(`Ali ste prepričani, da želite razpakirati in izprazniti škatlo #${boxId}?`)) return;
  try {
    await DB.deleteMixedBox(boxId);
    showToast(`Škatla #${boxId} je bila razpakirana.`);
    await renderMixedBoxesList();
    await refreshInventory();
  } catch (err) {
    console.error(err);
    showToast('Napaka pri razpakiranju.');
  }
};

window.printCitizenLabel6x6 = async function(boxId) {
  try {
    const box = await DB.getMixedBoxById(boxId);
    if (!box) {
      showToast('Mešana škatla ne obstaja.');
      return;
    }

    if (typeof jspdf === 'undefined' || !jspdf.jsPDF) {
      showToast('Knjižnica jsPDF ni naložena.');
      return;
    }

    // 60mm x 60mm nalepka za Citizen tiskalnik
    const doc = new jspdf.jsPDF({
      orientation: 'portrait',
      unit: 'mm',
      format: [60, 60]
    });

    const whName = box.warehouse === 'zgornje' ? 'ZGORNJE SKLADIŠČE' : (box.warehouse === 'spodnje' ? 'SPODNJE SKLADIŠČE' : 'SKLADIŠČE');
    const shelfText = box.shelf ? box.shelf.toUpperCase() : 'BREZ POLICE';

    // Rob okoli nalepke
    doc.setDrawColor(30, 41, 59);
    doc.setLineWidth(0.6);
    doc.roundedRect(2, 2, 56, 56, 2, 2, 'S');

    // Glava
    doc.setFillColor(15, 23, 42);
    doc.roundedRect(2.5, 2.5, 55, 6.5, 1.5, 1.5, 'F');
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(8);
    doc.setTextColor(255, 255, 255);
    doc.text('MEŠANA ŠKATLA (SOFT)', 30, 7, { align: 'center' });

    // Številka škatle (zelo velika in vidna)
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(22);
    doc.setTextColor(15, 23, 42);
    doc.text(`#${box.boxNumber || box.boxId}`, 30, 16, { align: 'center' });

    // Lokacija
    doc.setFontSize(7);
    doc.setFont('helvetica', 'bold');
    doc.setTextColor(71, 85, 105);
    doc.text(`${whName} • ${shelfText}`, 30, 20.5, { align: 'center' });

    // Črtna koda za škatlo
    let barcodeDataUrl = null;
    try {
      const canvas = document.createElement('canvas');
      JsBarcode(canvas, `BOX-${box.boxNumber || box.boxId}`, {
        format: 'CODE128',
        width: 1.5,
        height: 28,
        displayValue: false,
        margin: 0
      });
      barcodeDataUrl = canvas.toDataURL('image/png');
    } catch (e) {
      console.warn('JsBarcode napaka:', e);
    }

    if (barcodeDataUrl) {
      doc.addImage(barcodeDataUrl, 'PNG', 10, 22, 40, 7.5);
    }

    // Seznam artiklov (tabela vsebine)
    doc.setDrawColor(203, 213, 225);
    doc.setLineWidth(0.2);
    doc.line(4, 30.5, 56, 30.5);

    doc.setFontSize(6);
    doc.setFont('helvetica', 'bold');
    doc.setTextColor(100, 116, 139);
    doc.text('ARTIKEL', 5, 33);
    doc.text('KOS', 55, 33, { align: 'right' });

    let currentY = 36;
    const items = box.items || [];
    const maxItemsToDisplay = Math.min(items.length, 5);

    doc.setFont('helvetica', 'normal');
    doc.setTextColor(30, 41, 59);

    for (let i = 0; i < maxItemsToDisplay; i++) {
      const it = items[i];
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(6.2);
      doc.text(`${it.sku}`, 5, currentY);

      doc.setFont('helvetica', 'normal');
      doc.setFontSize(5.8);
      const shortName = (it.name || '').substring(0, 18);
      doc.text(shortName, 17, currentY);

      doc.setFont('helvetica', 'bold');
      doc.setFontSize(6.2);
      const itemQty = Math.max(1, Number(it.qty || it.quantity || it.count) || 1);
      doc.text(`${itemQty}x`, 55, currentY, { align: 'right' });
      currentY += 3.2;
    }

    if (items.length > 5) {
      doc.setFont('helvetica', 'italic');
      doc.setFontSize(5.2);
      doc.setTextColor(100, 116, 139);
      doc.text(`+ še ${items.length - 5} drugih artiklov...`, 5, currentY);
    }

    // Spodnji povzetek
    doc.setDrawColor(203, 213, 225);
    doc.setLineWidth(0.2);
    doc.line(4, 52, 56, 52);

    doc.setFont('helvetica', 'bold');
    doc.setFontSize(6.8);
    doc.setTextColor(15, 23, 42);
    doc.text(`Skupaj v škatli: ${box.totalItems || 0} kos`, 5, 55);

    const dStr = box.createdAt ? new Date(box.createdAt).toLocaleDateString('sl-SI') : '';
    doc.setFont('helvetica', 'normal');
    doc.setFontSize(5.5);
    doc.setTextColor(100, 116, 139);
    doc.text(dStr, 55, 55, { align: 'right' });

    const fileName = `Citizen_Nalepka_6x6_Skatla_${box.boxNumber || box.boxId}.pdf`;
    await downloadFile(doc.output('blob'), fileName, 'application/pdf');
    showToast(`Nalepka 6x6 za škatlo #${box.boxNumber || box.boxId} prenesena.`);
  } catch (err) {
    console.error('Napaka pri tiskanju Citizen nalepke:', err);
    showToast('Napaka pri tiskanju: ' + (err.message || err));
  }
};

/* =========================================================================
   SKLADIŠČA V NASTAVITVAH (WAREHOUSES MANAGEMENT)
   ========================================================================= */
window.renderSettingsWarehouses = async function() {
  const container = document.getElementById('settingsWarehousesList');
  const countBadge = document.getElementById('settingsWhCountBadge');
  if (!container) return;

  try {
    const list = await DB.getWarehouses();
    if (countBadge) countBadge.textContent = `${list.length} lokacij`;

    container.innerHTML = list.map(wh => {
      const isDefault = ['trgovina', 'zgornje', 'spodnje'].includes(wh.id);
      return `
        <div style="display: flex; justify-content: space-between; align-items: center; background: rgba(255,255,255,0.03); border: 1px solid var(--border); border-radius: 6px; padding: 8px 12px;">
          <div>
            <div style="font-weight: 700; font-size: 0.88rem; color: #fff;">${escapeHtml(wh.name)}</div>
            <div style="font-size: 0.72rem; color: var(--text-muted);">${escapeHtml(wh.description || '')}</div>
          </div>
          <div style="display: flex; align-items: center; gap: 6px;">
            <span style="font-size: 0.72rem; background: rgba(56, 189, 248, 0.15); color: #38bdf8; padding: 2px 6px; border-radius: 4px; font-weight: 600;">
              ${isDefault ? 'Sistemsko' : 'Dodatno'}
            </span>
            ${!isDefault ? `
              <button type="button" onclick="deleteWarehouseFromSettings('${escapeHtml(wh.id)}')" style="background: none; border: none; color: #ef4444; cursor: pointer; font-size: 1rem; padding: 2px 6px;">✕</button>
            ` : ''}
          </div>
        </div>
      `;
    }).join('');
  } catch (err) {
    console.error(err);
  }
};

window.addNewWarehouseFromSettings = async function() {
  const input = document.getElementById('newWhNameInput');
  if (!input) return;
  const name = input.value.trim();
  if (!name) {
    showToast('Vnesite ime skladišča.');
    return;
  }

  const id = 'wh_' + Date.now();
  try {
    await DB.saveWarehouse({
      id: id,
      name: name,
      description: 'Po meri ustvarjeno skladišče'
    });
    input.value = '';
    showToast(`Skladišče "${name}" dodano.`);
    await renderSettingsWarehouses();
    await populateWarehouseSelectors();
  } catch (err) {
    console.error(err);
    showToast('Napaka pri dodajanju skladišča.');
  }
};

window.deleteWarehouseFromSettings = async function(id) {
  if (!confirm('Ali res želite odstraniti to skladišče?')) return;
  try {
    await DB.deleteWarehouse(id);
    showToast('Skladišče odstranjeno.');
    await renderSettingsWarehouses();
    await populateWarehouseSelectors();
  } catch (err) {
    console.error(err);
    showToast('Napaka pri brisanju skladišča.');
  }
};

/* =========================================================================
   HITRI KAMERSKI SKENER (QUICK CAMERA SCANNER FOR ALL TABS)
   ========================================================================= */
let activeQuickScanTarget = null; // 'rep' | 'audit' | 'pack' | 'search'
let quickScanStream = null;
let isQuickScanLoopRunning = false;
let quickScanTorchActive = false;
let quickScanZoomFactor = 1.0;
let lastQuickScannedSku = null;
let lastQuickScannedTime = 0;

window.openQuickCameraScanner = async function(target = 'rep') {
  activeQuickScanTarget = target;

  // 1. Če teče na Android napravi (APK), uporabi bliskoviti nativni Camera2 / CameraX zero-copy skener
  if (window.Capacitor && window.Capacitor.isPluginAvailable && window.Capacitor.isPluginAvailable('NativeCameraScanner')) {
    try {
      const prompts = {
        'rep': 'Usmeri v cenovko ali etiketo na polici',
        'audit': 'Usmeri v škatlo ali etiketo (Inventura)',
        'pack': 'Usmeri v artikel za mešano škatlo',
        'search': 'Usmeri v črtno kodo ali škatlo artikla'
      };
      const prompt = prompts[target] || 'Usmeri v kodo ali škatlo';
      const isContinuous = (target === 'audit' && document.getElementById('chkQuickScanContinuous')?.checked);

      const res = await window.Capacitor.Plugins.NativeCameraScanner.startScanner({
        prompt: prompt,
        continuous: !!isContinuous
      });

      if (res && res.value && !res.cancelled) {
        await handleQuickScanRecognized(res.value);
      }
      return;
    } catch (nativeErr) {
      console.warn('NativeCameraScanner ni uspel, uporabljam spletni WebRTC skener:', nativeErr);
    }
  }

  // 2. Nadomestni način za brskalnik (WebRTC)
  const modal = document.getElementById('modalQuickScanner');
  if (!modal) return;

  const ctxLabel = document.getElementById('quickScanContextLabel');
  const title = document.getElementById('quickScanTitle');
  const frameLabel = document.getElementById('quickScanFrameLabel');
  const auditRow = document.getElementById('quickScanAuditContinuousRow');
  const statusPill = document.getElementById('quickScanStatusPill');

  if (auditRow) auditRow.style.display = (target === 'audit') ? 'flex' : 'none';
  if (statusPill) statusPill.textContent = 'Iščem šifro artikla...';

  if (target === 'rep') {
    if (ctxLabel) ctxLabel.textContent = 'POLICE & NABIRANJE';
    if (title) title.textContent = 'Poskeniraj cenovko ali artikel';
    if (frameLabel) frameLabel.textContent = 'Usmeri v etiketo na polici';
  } else if (target === 'audit') {
    if (ctxLabel) ctxLabel.textContent = 'INVENTURNI POPIS';
    if (title) title.textContent = 'Poskeniraj artikel za štetje';
    if (frameLabel) frameLabel.textContent = 'Usmeri v škatlo / etiketo';
  } else if (target === 'pack') {
    if (ctxLabel) ctxLabel.textContent = 'MEŠANA ŠKATLA';
    if (title) title.textContent = 'Poskeniraj artikel za škatlo';
    if (frameLabel) frameLabel.textContent = 'Usmeri v artikel';
  } else {
    if (ctxLabel) ctxLabel.textContent = 'ISKANJE ZALOGE';
    if (title) title.textContent = 'Poskeniraj artikel za iskanje';
    if (frameLabel) frameLabel.textContent = 'Usmeri v etiketo';
  }

  modal.style.display = 'flex';
  await startQuickScanCamera();
};

window.closeQuickScanner = function() {
  stopQuickScanCamera();
  const modal = document.getElementById('modalQuickScanner');
  if (modal) modal.style.display = 'none';
  activeQuickScanTarget = null;
};

async function startQuickScanCamera() {
  const video = document.getElementById('quickScanVideo');
  if (!video) return;

  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: {
        facingMode: { ideal: 'environment' },
        width: { ideal: 1920, min: 1280 },
        height: { ideal: 1080, min: 720 },
        focusMode: { ideal: 'continuous' }
      }
    });

    quickScanStream = stream;
    video.srcObject = stream;

    video.onloadedmetadata = () => {
      video.play().catch(() => {});
      updateQuickScanCapabilityControls();
      startQuickScanLoop();
    };
  } catch (err) {
    console.warn('Hitrega skenerja ni bilo mogoče zagnati z naprednimi nastavitvami:', err);
    try {
      const fallbackStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: 'environment' } }
      });
      quickScanStream = fallbackStream;
      video.srcObject = fallbackStream;
      video.onloadedmetadata = () => {
        video.play().catch(() => {});
        updateQuickScanCapabilityControls();
        startQuickScanLoop();
      };
    } catch (e) {
      console.error('Kamere ni mogoče odpreti:', e);
      showToast('Kamera ni dostopna. Uporabite gumb Foto zajem.');
    }
  }
}

function stopQuickScanCamera() {
  isQuickScanLoopRunning = false;
  if (quickScanStream) {
    quickScanStream.getTracks().forEach(track => track.stop());
    quickScanStream = null;
    const video = document.getElementById('quickScanVideo');
    if (video) video.srcObject = null;
  }
}

function updateQuickScanCapabilityControls() {
  if (!quickScanStream) return;
  const track = quickScanStream.getVideoTracks()[0];
  if (!track) return;
  const caps = (typeof track.getCapabilities === 'function') ? track.getCapabilities() : {};
  const btnTorch = document.getElementById('btnQuickScanTorch');
  if (btnTorch) btnTorch.style.display = caps.torch ? 'inline-flex' : 'none';
}

window.toggleQuickScanTorch = async function() {
  if (!quickScanStream) return;
  const track = quickScanStream.getVideoTracks()[0];
  if (!track) return;
  quickScanTorchActive = !quickScanTorchActive;
  try {
    await track.applyConstraints({ advanced: [{ torch: quickScanTorchActive }] });
    const btn = document.getElementById('btnQuickScanTorch');
    if (btn) btn.style.background = quickScanTorchActive ? '#38bdf8' : 'rgba(15,23,42,0.85)';
  } catch (e) {}
};

window.cycleQuickScanZoom = async function() {
  if (!quickScanStream) return;
  const track = quickScanStream.getVideoTracks()[0];
  if (!track) return;
  const caps = (typeof track.getCapabilities === 'function') ? track.getCapabilities() : {};
  if (!caps.zoom) return;

  const maxZoom = Math.min(caps.zoom.max || 1, 4.0);
  const minZoom = caps.zoom.min || 1.0;
  if (quickScanZoomFactor >= maxZoom) {
    quickScanZoomFactor = minZoom;
  } else {
    quickScanZoomFactor = Math.min(quickScanZoomFactor + 1.0, maxZoom);
  }

  try {
    await track.applyConstraints({ advanced: [{ zoom: quickScanZoomFactor }] });
    const btn = document.getElementById('btnQuickScanZoom');
    if (btn) btn.textContent = `🔍 ${quickScanZoomFactor.toFixed(0)}x`;
  } catch (e) {}
};

window.triggerQuickScanTapToFocus = async function(event) {
  if (!quickScanStream) return;
  const track = quickScanStream.getVideoTracks()[0];
  if (!track || typeof track.applyConstraints !== 'function') return;

  const caps = (typeof track.getCapabilities === 'function') ? track.getCapabilities() : {};
  if (caps.focusMode && caps.focusMode.includes('continuous')) {
    try {
      await track.applyConstraints({ advanced: [{ focusMode: 'continuous' }] });
    } catch (e) {}
  }

  // Vizualni krogček za ostrenje
  const box = document.getElementById('quickScanPreviewBox');
  if (box) {
    const rect = box.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const y = event.clientY - rect.top;
    const ripple = document.createElement('div');
    ripple.style.cssText = `
      position: absolute; left: ${x - 25}px; top: ${y - 25}px; width: 50px; height: 50px;
      border: 2px solid #38bdf8; border-radius: 50%; pointer-events: none; z-index: 50;
      animation: tapFocusPulse 0.6s ease-out forwards;
    `;
    box.appendChild(ripple);
    setTimeout(() => ripple.remove(), 600);
  }
};

function startQuickScanLoop() {
  isQuickScanLoopRunning = true;
  requestAnimationFrame(processQuickScanFrame);
}

async function processQuickScanFrame() {
  if (!isQuickScanLoopRunning) return;

  const video = document.getElementById('quickScanVideo');
  const canvas = document.getElementById('quickScanCanvas');

  if (video && video.readyState >= 2 && !video.paused && canvas) {
    const vW = video.videoWidth;
    const vH = video.videoHeight;

    if (vW > 0 && vH > 0) {
      // Izreži sredinsko območje vizirja
      const cropW = Math.round(vW * 0.85);
      const cropH = Math.round(vH * 0.65);
      const startX = Math.round((vW - cropW) / 2);
      const startY = Math.round((vH - cropH) / 2);

      canvas.width = cropW;
      canvas.height = cropH;
      const ctx = canvas.getContext('2d');
      ctx.drawImage(video, startX, startY, cropW, cropH, 0, 0, cropW, cropH);

      try {
        const parsed = await parseFrameWithOcr(canvas);
        if (parsed && parsed.articleNumber) {
          const now = performance.now();
          const sku = String(parsed.articleNumber);

          if (sku !== lastQuickScannedSku || (now - lastQuickScannedTime) > 1500) {
            lastQuickScannedSku = sku;
            lastQuickScannedTime = now;
            await handleQuickScanRecognized(sku);
          }
        }
      } catch (e) {
        console.debug('Quick scan napaka:', e);
      }
    }
  }

  if (isQuickScanLoopRunning) {
    setTimeout(() => {
      if (isQuickScanLoopRunning) requestAnimationFrame(processQuickScanFrame);
    }, 180);
  }
}

async function handleQuickScanRecognized(sku) {
  playSuccessBeep();
  if (navigator.vibrate) navigator.vibrate(80);

  const statusPill = document.getElementById('quickScanStatusPill');
  if (statusPill) statusPill.textContent = `Prepoznano: #${sku}!`;

  if (activeQuickScanTarget === 'rep') {
    closeQuickScanner();
    const input = document.getElementById('repScanInput');
    if (input) input.value = sku;
    await lookupArticleForReplenishment(sku);
    showToast(`Artikel #${sku} poskeniran za police.`);
  } else if (activeQuickScanTarget === 'audit') {
    handleAuditScan(sku);
    const continuous = document.getElementById('chkQuickScanContinuous')?.checked;
    if (!continuous) {
      closeQuickScanner();
    } else {
      if (statusPill) statusPill.textContent = `Prešteto: #${sku} (+1)`;
      setTimeout(() => {
        if (statusPill && isQuickScanLoopRunning) statusPill.textContent = 'Iščem naslednjo škatlo...';
      }, 1200);
    }
  } else if (activeQuickScanTarget === 'pack') {
    closeQuickScanner();
    const input = document.getElementById('packAddSkuInput');
    if (input) {
      input.value = sku;
      const match = cachedItems.find(i => i.sku === sku);
      const preview = document.getElementById('packItemPreview');
      if (preview && match) preview.textContent = `Najdeno: ${match.name} (#${match.sku})`;
    }
    showToast(`Artikel #${sku} dodan v vnos škatle.`);
  } else if (activeQuickScanTarget === 'search') {
    closeQuickScanner();
    const input = document.getElementById('searchInput');
    if (input) {
      input.value = sku;
      renderItemsList();
    }
    showToast(`Iskanje za #${sku}`);
  }
}

window.triggerQuickScanNativePhoto = async function() {
  try {
    const plugins = window.Capacitor?.Plugins;
    if (!plugins || !plugins.Camera || !plugins.TextRecognition) {
      showToast('Kamera vtičnik ni na voljo.');
      return;
    }

    const photo = await plugins.Camera.getPhoto({
      quality: 95,
      allowEditing: false,
      resultType: 'uri',
      source: 'CAMERA'
    });

    if (!photo || !photo.path) return;

    const statusPill = document.getElementById('quickScanStatusPill');
    if (statusPill) statusPill.textContent = 'AI analiza slike...';

    const result = await plugins.TextRecognition.processImage({
      path: photo.path
    });

    const parsed = WarehouseLabelParser.parse(result.text || '', result.blocks || null, {
      knownSkus: getKnownSKUsSet()
    });

    if (parsed && parsed.articleNumber) {
      await handleQuickScanRecognized(String(parsed.articleNumber));
    } else {
      if (statusPill) statusPill.textContent = 'Šifra ni bila najdena.';
      showToast('Na posnetku ni bilo prepoznane 7-mestne šifre artikla.');
    }
  } catch (err) {
    if (err && err.message && (err.message.includes('cancel') || err.message.includes('User cancelled'))) return;
    console.error(err);
    showToast('Napaka pri zajemu fotografije.');
  }
};




