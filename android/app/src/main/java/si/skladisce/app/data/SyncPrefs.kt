package si.skladisce.app.data

import android.content.Context
import kotlin.random.Random

data class SyncHeaderInfo(
    val deviceId: String,
    val dbVersion: Int,
    val stateHash: Int,
    val hashHex: String
)

class SyncPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("skladisce_native_prefs", Context.MODE_PRIVATE)

    fun getDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank() && existing.length == 4) return existing
        val chars = "0123456789abcdef"
        val generated = (0 until 4).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun getDbVersion(): Int = prefs.getInt(KEY_DB_VERSION, 1)

    fun getLastLogId(): String {
        return prefs.getString(KEY_LAST_LOG_ID, null) ?: run {
            val initVal = "${getDeviceId()}_init"
            prefs.edit().putString(KEY_LAST_LOG_ID, initVal).apply()
            initVal
        }
    }

    fun bumpMutationVersion(): SyncHeaderInfo {
        val nextVer = getDbVersion() + 1
        val newLogId = "${getDeviceId()}_${System.currentTimeMillis()}_${Random.nextInt(10000)}"
        prefs.edit()
            .putInt(KEY_DB_VERSION, nextVer)
            .putString(KEY_LAST_LOG_ID, newLogId)
            .apply()
        return getSyncHeaderInfo()
    }

    fun getSyncHeaderInfo(): SyncHeaderInfo {
        val devId = getDeviceId()
        val ver = getDbVersion()
        val lastLog = getLastLogId()
        val hash = fnv1a32("${devId}_v${ver}_${lastLog}")
        val unsignedHex = (hash.toLong() and 0xFFFFFFFFL).toString(16).uppercase().padStart(4, '0').takeLast(4)
        return SyncHeaderInfo(
            deviceId = devId,
            dbVersion = ver,
            stateHash = hash,
            hashHex = "#$unsignedHex"
        )
    }

    var lastUsedShelf: String
        get() = prefs.getString(KEY_LAST_SHELF, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LAST_SHELF, value.trim()).apply() }

    var activeScannerWarehouse: String
        get() = prefs.getString(KEY_SCANNER_WH, "spodnje") ?: "spodnje"
        set(value) { prefs.edit().putString(KEY_SCANNER_WH, value.trim().ifEmpty { "spodnje" }).apply() }

    var isInitialSeedDone: Boolean
        get() = prefs.getBoolean(KEY_INITIAL_SEED, false)
        set(value) { prefs.edit().putBoolean(KEY_INITIAL_SEED, value).apply() }

    companion object {
        private const val KEY_DEVICE_ID = "skladisce_device_id"
        private const val KEY_DB_VERSION = "skladisce_db_version"
        private const val KEY_LAST_LOG_ID = "skladisce_last_log_id"
        private const val KEY_LAST_SHELF = "skladisce_last_shelf"
        private const val KEY_SCANNER_WH = "skladisce_active_scanner_wh"
        private const val KEY_INITIAL_SEED = "skladisce_initial_seed_done"

        /**
         * Deterministični 32-bit FNV-1a hash (enak kot v db.js za BLE Mesh združljivost)
         */
        fun fnv1a32(input: String): Int {
            var hash = 0x811c9dc5L
            val prime = 0x01000193L
            for (ch in input) {
                hash = hash xor (ch.code.toLong() and 0xFFFFL)
                hash = (hash * prime) and 0xFFFFFFFFL
            }
            return hash.toInt()
        }
    }
}
