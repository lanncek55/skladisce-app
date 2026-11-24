package si.skladisce.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import si.skladisce.app.data.SyncPrefs
import si.skladisce.app.data.WarehouseRepository
import si.skladisce.app.data.model.SyncMergeResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

enum class BleMeshState {
    OFF,
    READY,
    SYNCING,
    SYNCED,
    MANUAL_SENDING,
    MANUAL_RECEIVING,
    ERROR
}

data class BleSyncStatus(
    val state: BleMeshState = BleMeshState.OFF,
    val statusMessage: String = "BLE Mesh pripravljen",
    val lastPeerId: String = "",
    val lastSyncTimeMs: Long = 0L,
    val isAutoRunning: Boolean = false
)

sealed interface BleSyncEvent {
    data class AutoMerged(val peerId: String, val result: SyncMergeResult) : BleSyncEvent
    data class ManualSent(val bytesSent: Int) : BleSyncEvent
    data class ManualReceived(val result: SyncMergeResult, val bytesReceived: Int) : BleSyncEvent
    data class Error(val message: String) : BleSyncEvent
}

@SuppressLint("MissingPermission")
class BleMeshSyncManager(
    private val context: Context,
    private val repository: WarehouseRepository,
    private val prefs: SyncPrefs
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow(BleSyncStatus())
    val status: StateFlow<BleSyncStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<BleSyncEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<BleSyncEvent> = _events.asSharedFlow()

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var autoScanner: BluetoothLeScanner? = null
    private var manualScanner: BluetoothLeScanner? = null
    private var activeGattClient: BluetoothGatt? = null
    private var manualClientGatt: BluetoothGatt? = null

    private var autoAdvertiseCallback: AdvertiseCallback? = null
    private var autoScanCallback: ScanCallback? = null
    private var manualAdvertiseCallback: AdvertiseCallback? = null
    private var manualScanCallback: ScanCallback? = null

    @Volatile
    private var myDeviceId: String = prefs.getDeviceId()
    @Volatile
    private var myStateHash: Int = prefs.getSyncHeaderInfo().stateHash
    @Volatile
    private var myPayloadCompressed: ByteArray = ByteArray(0)
    @Volatile
    private var manualSendPayload: ByteArray? = null

    private val isCurrentlySyncing = AtomicBoolean(false)
    private val lastSyncWithPeer = ConcurrentHashMap<String, Long>()
    private val lastPeerHash = ConcurrentHashMap<String, Int>()
    private val lastMyHash = ConcurrentHashMap<String, Int>()

    private val serverIncomingBuffer = ByteArrayOutputStream()
    private val clientIncomingBuffer = ByteArrayOutputStream()
    private val manualReceiveBuffer = ByteArrayOutputStream()

    @Volatile
    private var currentConnectedPeerId: String = ""
    @Volatile
    private var currentConnectedPeerHash: Int = 0
    @Volatile
    private var isAutoRunning: Boolean = false

    private var autoTimeoutRunnable: Runnable? = null
    private var manualTimeoutRunnable: Runnable? = null

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    fun hasBlePermissions(): Boolean {
        return requiredPermissions().all {
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun startAutoSync() {
        if (!hasBlePermissions()) {
            _status.value = BleSyncStatus(
                state = BleMeshState.OFF,
                statusMessage = "Dovoljenja za Bluetooth še niso potrjena.",
                isAutoRunning = false
            )
            return
        }

        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        val adapter = bluetoothAdapter
        if (bluetoothManager == null || adapter == null || !adapter.isEnabled) {
            _status.value = BleSyncStatus(
                state = BleMeshState.OFF,
                statusMessage = "Bluetooth je izklopljen.",
                isAutoRunning = false
            )
            return
        }

        myDeviceId = prefs.getDeviceId()
        myStateHash = prefs.getSyncHeaderInfo().stateHash

        scope.launch {
            runCatching {
                val json = repository.getSyncPayloadJson()
                myPayloadCompressed = compress(json)
            }
            mainHandler.post {
                setupGattServer()
                startAutoAdvertising()
                startAutoScanning()
                isAutoRunning = true
                _status.value = _status.value.copy(
                    state = BleMeshState.READY,
                    statusMessage = "BLE Mesh aktiven • Samodejno v ozadju",
                    isAutoRunning = true
                )
            }
        }
    }

    fun refreshPayloadAndAdvertise() {
        if (!isAutoRunning || !hasBlePermissions()) return
        val header = prefs.getSyncHeaderInfo()
        myDeviceId = header.deviceId
        myStateHash = header.stateHash

        scope.launch {
            runCatching {
                val json = repository.getSyncPayloadJson()
                myPayloadCompressed = compress(json)
            }
            mainHandler.post {
                stopAutoAdvertising()
                startAutoAdvertising()
            }
        }
    }

    fun forceSyncNow() {
        if (!hasBlePermissions()) {
            _events.tryEmit(BleSyncEvent.Error("Manjkajo Bluetooth dovoljenja."))
            return
        }
        // Počisti cooldown cache za takojšen ponovni stik s sosednjimi telefoni
        lastSyncWithPeer.clear()
        lastPeerHash.clear()
        lastMyHash.clear()
        if (!isAutoRunning) {
            startAutoSync()
        } else {
            refreshPayloadAndAdvertise()
        }
        _status.value = _status.value.copy(
            state = BleMeshState.READY,
            statusMessage = "Vsiljena BLE oddaja stanja (${prefs.getSyncHeaderInfo().hashHex}) ..."
        )
    }

    fun stopAutoSync() {
        isAutoRunning = false
        stopAutoAdvertising()
        stopAutoScanning()
        closeAutoClientGatt()
        _status.value = _status.value.copy(state = BleMeshState.OFF, isAutoRunning = false)
    }

    fun startManualSending() {
        if (!hasBlePermissions()) {
            _events.tryEmit(BleSyncEvent.Error("Dovoljenja za Bluetooth še niso potrjena."))
            return
        }
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (bm == null || adapter == null || !adapter.isEnabled) {
            _events.tryEmit(BleSyncEvent.Error("Bluetooth je izklopljen."))
            return
        }
        bluetoothManager = bm
        bluetoothAdapter = adapter

        cancelManual()
        _status.value = _status.value.copy(
            state = BleMeshState.MANUAL_SENDING,
            statusMessage = "Priprava podatkov za ročno oddajanje..."
        )

        scope.launch {
            val json = repository.getSyncPayloadJson()
            manualSendPayload = compress(json)
            mainHandler.post {
                setupGattServer()
                val adv = adapter.bluetoothLeAdvertiser
                if (adv == null) {
                    _status.value = _status.value.copy(state = BleMeshState.ERROR, statusMessage = "Ta naprava ne podpira BLE oddajanja.")
                    _events.tryEmit(BleSyncEvent.Error("Ta naprava ne podpira BLE oddajanja."))
                    return@post
                }
                advertiser = adv

                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(60_000)
                    .build()

                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(ParcelUuid(SERVICE_UUID))
                    .build()

                val cb = object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                        _status.value = _status.value.copy(
                            state = BleMeshState.MANUAL_SENDING,
                            statusMessage = "Oddajam zalogo (${manualSendPayload?.size ?: 0} B) ... Na drugem telefonu kliknite Prejmi."
                        )
                    }

                    override fun onStartFailure(errorCode: Int) {
                        _events.tryEmit(BleSyncEvent.Error("Oddajanje BLE ni uspelo (koda: $errorCode)"))
                        cancelManual()
                    }
                }
                manualAdvertiseCallback = cb
                runCatching { adv.startAdvertising(settings, data, cb) }
                    .onFailure { _events.tryEmit(BleSyncEvent.Error(it.message ?: "Napaka BLE")) }

                val timeout = Runnable {
                    if (_status.value.state == BleMeshState.MANUAL_SENDING) {
                        _events.tryEmit(BleSyncEvent.Error("Čas za ročno povezavo je potekel."))
                        cancelManual()
                    }
                }
                manualTimeoutRunnable = timeout
                mainHandler.postDelayed(timeout, 60_000L)
            }
        }
    }

    fun startManualReceiving() {
        if (!hasBlePermissions()) {
            _events.tryEmit(BleSyncEvent.Error("Dovoljenja za Bluetooth še niso potrjena."))
            return
        }
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (bm == null || adapter == null || !adapter.isEnabled) {
            _events.tryEmit(BleSyncEvent.Error("Bluetooth je izklopljen."))
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _events.tryEmit(BleSyncEvent.Error("BLE skener ni na voljo."))
            return
        }
        bluetoothManager = bm
        bluetoothAdapter = adapter
        manualScanner = scanner

        cancelManual()
        manualReceiveBuffer.reset()
        _status.value = _status.value.copy(
            state = BleMeshState.MANUAL_RECEIVING,
            statusMessage = "Iščem napravo za prenos zaloge..."
        )

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val dev = result?.device ?: return
                runCatching { manualScanner?.stopScan(this) }
                manualScanCallback = null
                connectManualReceiver(dev)
            }

            override fun onScanFailed(errorCode: Int) {
                _events.tryEmit(BleSyncEvent.Error("Skeniranje ni uspelo (koda: $errorCode)"))
                cancelManual()
            }
        }
        manualScanCallback = cb
        runCatching { scanner.startScan(listOf(filter), settings, cb) }

        val timeout = Runnable {
            if (_status.value.state == BleMeshState.MANUAL_RECEIVING) {
                _events.tryEmit(BleSyncEvent.Error("Ni bilo mogoče najti naprave v bližini."))
                cancelManual()
            }
        }
        manualTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, 40_000L)
    }

    fun cancelManual() {
        manualTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        manualTimeoutRunnable = null
        manualScanCallback?.let { runCatching { manualScanner?.stopScan(it) } }
        manualScanCallback = null
        manualAdvertiseCallback?.let { runCatching { advertiser?.stopAdvertising(it) } }
        manualAdvertiseCallback = null
        manualClientGatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        manualClientGatt = null
        manualSendPayload = null
        manualReceiveBuffer.reset()

        _status.value = _status.value.copy(
            state = if (isAutoRunning) BleMeshState.READY else BleMeshState.OFF,
            statusMessage = if (isAutoRunning) "BLE Mesh aktiven • Samodejno v ozadju" else "BLE Mesh pripravljen"
        )
    }

    private fun setupGattServer() {
        if (gattServer != null) return
        val bm = bluetoothManager ?: return
        runCatching {
            val server = bm.openGattServer(context, object : BluetoothGattServerCallback() {
                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    characteristic: BluetoothGattCharacteristic,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    if (responseNeeded) {
                        runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
                    }
                    val bytes = value ?: return
                    if (bytes.isEmpty()) return

                    when {
                        bytes.contentEquals(CLIENT_EOF) -> {
                            val rawBytes = synchronized(serverIncomingBuffer) {
                                serverIncomingBuffer.toByteArray().also { serverIncomingBuffer.reset() }
                            }
                            val peerJson = decompress(rawBytes)
                            handleServerReceivedJson(peerJson)
                            lastSyncWithPeer[device.address] = System.currentTimeMillis()
                            streamChunkedNotification(device, myPayloadCompressed, SERVER_EOF)
                        }

                        bytes.contentEquals(MANUAL_START) -> {
                            val payload = manualSendPayload ?: myPayloadCompressed
                            streamChunkedNotification(device, payload, EOF_MARKER) {
                                mainHandler.post {
                                    _status.value = _status.value.copy(
                                        state = if (isAutoRunning) BleMeshState.SYNCED else BleMeshState.READY,
                                        lastSyncTimeMs = System.currentTimeMillis(),
                                        statusMessage = "Zaloga uspešno poslana (${payload.size} B)."
                                    )
                                    _events.tryEmit(BleSyncEvent.ManualSent(payload.size))
                                    cancelManual()
                                }
                            }
                        }

                        else -> {
                            synchronized(serverIncomingBuffer) {
                                serverIncomingBuffer.write(bytes, 0, bytes.size)
                            }
                        }
                    }
                }

                override fun onDescriptorWriteRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    descriptor: BluetoothGattDescriptor,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    if (responseNeeded) {
                        runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
                    }
                }
            })

            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val dataChar = BluetoothGattCharacteristic(
                CHAR_DATA_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            dataChar.addDescriptor(
                BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
            )
            service.addCharacteristic(dataChar)

            val ctrlChar = BluetoothGattCharacteristic(
                CHAR_CTRL_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(ctrlChar)

            server?.addService(service)
            gattServer = server
        }
    }

    private fun startAutoAdvertising() {
        val adapter = bluetoothAdapter ?: return
        val adv = adapter.bluetoothLeAdvertiser ?: return
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val sData = ByteArray(8)
        val idBytes = myDeviceId.toByteArray(StandardCharsets.US_ASCII)
        for (i in 0..3) sData[i] = if (i < idBytes.size) idBytes[i] else '_'.code.toByte()
        sData[4] = ((myStateHash ushr 24) and 0xFF).toByte()
        sData[5] = ((myStateHash ushr 16) and 0xFF).toByte()
        sData[6] = ((myStateHash ushr 8) and 0xFF).toByte()
        sData[7] = (myStateHash and 0xFF).toByte()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), sData)
            .build()

        val cb = object : AdvertiseCallback() {}
        autoAdvertiseCallback = cb
        runCatching { adv.startAdvertising(settings, data, cb) }
    }

    private fun stopAutoAdvertising() {
        autoAdvertiseCallback?.let { runCatching { advertiser?.stopAdvertising(it) } }
        autoAdvertiseCallback = null
    }

    private fun startAutoScanning() {
        val adapter = bluetoothAdapter ?: return
        val sc = adapter.bluetoothLeScanner ?: return
        autoScanner = sc

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val record = result?.scanRecord ?: return
                val sData = record.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
                if (sData.size < 8) return

                val peerId = String(sData, 0, 4, StandardCharsets.US_ASCII)
                val peerHash = ((sData[4].toInt() and 0xFF) shl 24) or
                    ((sData[5].toInt() and 0xFF) shl 16) or
                    ((sData[6].toInt() and 0xFF) shl 8) or
                    (sData[7].toInt() and 0xFF)

                if (peerId == myDeviceId) return

                val lastT = lastSyncWithPeer[peerId]
                val lastPH = lastPeerHash[peerId]
                val lastMH = lastMyHash[peerId]
                if (lastT != null && (System.currentTimeMillis() - lastT < 30_000L) &&
                    lastPH == peerHash && lastMH == myStateHash
                ) {
                    return
                }

                if (isCurrentlySyncing.get()) return
                if (myDeviceId < peerId && isCurrentlySyncing.compareAndSet(false, true)) {
                    currentConnectedPeerId = peerId
                    currentConnectedPeerHash = peerHash
                    result.device?.let { connectAsAutoClient(it) } ?: isCurrentlySyncing.set(false)
                }
            }
        }
        autoScanCallback = cb
        runCatching { sc.startScan(listOf(filter), settings, cb) }
    }

    private fun stopAutoScanning() {
        autoScanCallback?.let { runCatching { autoScanner?.stopScan(it) } }
        autoScanCallback = null
    }

    private fun connectAsAutoClient(device: BluetoothDevice) {
        mainHandler.post {
            _status.value = _status.value.copy(
                state = BleMeshState.SYNCING,
                statusMessage = "Sinhroniziram z napravo DEV-$currentConnectedPeerId..."
            )
        }
        autoTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val timeout = Runnable { closeAutoClientGatt() }
        autoTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, 12_000L)

        runCatching {
            activeGattClient = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        runCatching { gatt.requestMtu(512) }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        closeAutoClientGatt()
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    runCatching { gatt.discoverServices() }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        closeAutoClientGatt()
                        return
                    }
                    val dataChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_DATA_UUID)
                    if (dataChar == null) {
                        closeAutoClientGatt()
                        return
                    }
                    runCatching {
                        gatt.setCharacteristicNotification(dataChar, true)
                        val desc = dataChar.getDescriptor(CCCD_UUID)
                        if (desc != null) {
                            writeDescriptorCompat(gatt, desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        }
                    }.onFailure { closeAutoClientGatt() }
                }

                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    scope.launch {
                        runCatching {
                            val dataChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_DATA_UUID) ?: return@runCatching
                            streamCharacteristicWrites(gatt, dataChar, myPayloadCompressed, CLIENT_EOF)
                        }.onFailure { closeAutoClientGatt() }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        @Suppress("DEPRECATION")
                        characteristic.value?.let { handleClientChunk(gatt, it) }
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    handleClientChunk(gatt, value)
                }

                private fun handleClientChunk(gatt: BluetoothGatt, bytes: ByteArray) {
                    if (bytes.isEmpty()) return
                    if (bytes.contentEquals(SERVER_EOF)) {
                        val fullBytes = synchronized(clientIncomingBuffer) {
                            clientIncomingBuffer.toByteArray().also { clientIncomingBuffer.reset() }
                        }
                        val serverJson = decompress(fullBytes)
                        lastSyncWithPeer[currentConnectedPeerId] = System.currentTimeMillis()
                        lastPeerHash[currentConnectedPeerId] = currentConnectedPeerHash
                        lastMyHash[currentConnectedPeerId] = myStateHash

                        handleClientReceivedJson(currentConnectedPeerId, serverJson)
                        runCatching { gatt.disconnect() }
                    } else {
                        synchronized(clientIncomingBuffer) {
                            clientIncomingBuffer.write(bytes, 0, bytes.size)
                        }
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
        }.onFailure { closeAutoClientGatt() }
    }

    private fun closeAutoClientGatt() {
        autoTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        autoTimeoutRunnable = null
        activeGattClient?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        activeGattClient = null
        synchronized(clientIncomingBuffer) { clientIncomingBuffer.reset() }
        isCurrentlySyncing.set(false)
    }

    private fun connectManualReceiver(device: BluetoothDevice) {
        runCatching {
            manualClientGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        runCatching { gatt.requestMtu(512) }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (_status.value.state == BleMeshState.MANUAL_RECEIVING) {
                            _events.tryEmit(BleSyncEvent.Error("Povezava z oddajnikom je bila prekinjena."))
                            cancelManual()
                        }
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    runCatching { gatt.discoverServices() }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) return
                    val svc = gatt.getService(SERVICE_UUID) ?: return
                    val dataChar = svc.getCharacteristic(CHAR_DATA_UUID) ?: return
                    val ctrlChar = svc.getCharacteristic(CHAR_CTRL_UUID) ?: return

                    runCatching {
                        gatt.setCharacteristicNotification(dataChar, true)
                        dataChar.getDescriptor(CCCD_UUID)?.let {
                            writeDescriptorCompat(gatt, it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        }
                        mainHandler.postDelayed({
                            runCatching { writeCharacteristicCompat(gatt, ctrlChar, MANUAL_START) }
                        }, 150L)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        @Suppress("DEPRECATION")
                        characteristic.value?.let { handleManualChunk(it) }
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    handleManualChunk(value)
                }

                private fun handleManualChunk(bytes: ByteArray) {
                    if (bytes.isEmpty()) return
                    if (bytes.contentEquals(EOF_MARKER)) {
                        val fullBytes = synchronized(manualReceiveBuffer) {
                            manualReceiveBuffer.toByteArray().also { manualReceiveBuffer.reset() }
                        }
                        val json = decompress(fullBytes)
                        scope.launch {
                            runCatching {
                                val res = repository.mergeSyncPayloadJson(json)
                                mainHandler.post {
                                    _status.value = _status.value.copy(
                                        state = BleMeshState.SYNCED,
                                        lastSyncTimeMs = System.currentTimeMillis(),
                                        statusMessage = "Prejeto in združeno (+${res.addedArticles} novih, ${res.updatedRecords} posodobljenih)."
                                    )
                                    _events.tryEmit(BleSyncEvent.ManualReceived(res, fullBytes.size))
                                    cancelManual()
                                }
                            }.onFailure {
                                _events.tryEmit(BleSyncEvent.Error(it.message ?: "Napaka pri uvozu BLE"))
                                cancelManual()
                            }
                        }
                    } else {
                        synchronized(manualReceiveBuffer) {
                            manualReceiveBuffer.write(bytes, 0, bytes.size)
                        }
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
        }.onFailure {
            _events.tryEmit(BleSyncEvent.Error(it.message ?: "Napaka BLE"))
            cancelManual()
        }
    }

    private fun handleServerReceivedJson(json: String) {
        if (json.isBlank()) return
        scope.launch {
            runCatching {
                val res = repository.mergeSyncPayloadJson(json)
                mainHandler.post {
                    _status.value = _status.value.copy(
                        state = BleMeshState.SYNCED,
                        lastPeerId = "peer",
                        lastSyncTimeMs = System.currentTimeMillis(),
                        statusMessage = "Samodejno usklajeno (+${res.addedArticles} novih)"
                    )
                    _events.tryEmit(BleSyncEvent.AutoMerged("peer", res))
                    refreshPayloadAndAdvertise()
                }
            }
        }
    }

    private fun handleClientReceivedJson(peerId: String, json: String) {
        if (json.isBlank()) return
        scope.launch {
            runCatching {
                val res = repository.mergeSyncPayloadJson(json)
                mainHandler.post {
                    _status.value = _status.value.copy(
                        state = BleMeshState.SYNCED,
                        lastPeerId = peerId,
                        lastSyncTimeMs = System.currentTimeMillis(),
                        statusMessage = "Samodejno usklajeno z DEV-$peerId"
                    )
                    _events.tryEmit(BleSyncEvent.AutoMerged(peerId, res))
                    refreshPayloadAndAdvertise()
                }
            }
        }
    }

    private fun streamChunkedNotification(
        device: BluetoothDevice,
        payload: ByteArray,
        eofMarker: ByteArray,
        onComplete: (() -> Unit)? = null
    ) {
        scope.launch {
            runCatching {
                val dataChar = gattServer?.getService(SERVICE_UUID)?.getCharacteristic(CHAR_DATA_UUID) ?: return@runCatching
                val chunkSize = 240
                var offset = 0
                while (offset < payload.size) {
                    val end = minOf(offset + chunkSize, payload.size)
                    val chunk = payload.copyOfRange(offset, end)
                    notifyCharacteristicCompat(device, dataChar, chunk)
                    offset = end
                    kotlinx.coroutines.delay(15L)
                }
                kotlinx.coroutines.delay(30L)
                notifyCharacteristicCompat(device, dataChar, eofMarker)
                onComplete?.invoke()
            }
        }
    }

    private suspend fun streamCharacteristicWrites(
        gatt: BluetoothGatt,
        dataChar: BluetoothGattCharacteristic,
        payload: ByteArray,
        eofMarker: ByteArray
    ) {
        val chunkSize = 240
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + chunkSize, payload.size)
            val chunk = payload.copyOfRange(offset, end)
            writeCharacteristicCompat(gatt, dataChar, chunk)
            offset = end
            kotlinx.coroutines.delay(15L)
        }
        kotlinx.coroutines.delay(30L)
        writeCharacteristicCompat(gatt, dataChar, eofMarker)
    }

    private fun notifyCharacteristicCompat(device: BluetoothDevice, ch: BluetoothGattCharacteristic, value: ByteArray) {
        val srv = gattServer ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                srv.notifyCharacteristicChanged(device, ch, false, value)
            } else {
                @Suppress("DEPRECATION")
                ch.value = value
                @Suppress("DEPRECATION")
                srv.notifyCharacteristicChanged(device, ch, false)
            }
        }
    }

    private fun writeCharacteristicCompat(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                ch.value = value
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(ch)
            }
        }
    }

    private fun writeDescriptorCompat(gatt: BluetoothGatt, desc: BluetoothGattDescriptor, value: ByteArray) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(desc, value)
            } else {
                @Suppress("DEPRECATION")
                desc.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(desc)
            }
        }
    }

    private fun compress(str: String): ByteArray {
        return runCatching {
            ByteArrayOutputStream().use { out ->
                GZIPOutputStream(out).use { it.write(str.toByteArray(StandardCharsets.UTF_8)) }
                out.toByteArray()
            }
        }.getOrElse { str.toByteArray(StandardCharsets.UTF_8) }
    }

    private fun decompress(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return runCatching {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
                gzip.readBytes().toString(StandardCharsets.UTF_8)
            }
        }.getOrElse { String(bytes, StandardCharsets.UTF_8) }
    }

    companion object {
        private val SERVICE_UUID = UUID.fromString("0000FE55-0000-1000-8000-00805F9B34FB")
        private val CHAR_DATA_UUID = UUID.fromString("0000FE56-0000-1000-8000-00805F9B34FB")
        private val CHAR_CTRL_UUID = UUID.fromString("0000FE57-0000-1000-8000-00805F9B34FB")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private val EOF_MARKER = "__EOF__".toByteArray(StandardCharsets.UTF_8)
        private val CLIENT_EOF = "__C_EOF__".toByteArray(StandardCharsets.UTF_8)
        private val SERVER_EOF = "__S_EOF__".toByteArray(StandardCharsets.UTF_8)
        private val MANUAL_START = "START".toByteArray(StandardCharsets.UTF_8)
    }
}
