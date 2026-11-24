package si.skladisce.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import si.skladisce.app.ble.BleMeshSyncManager
import si.skladisce.app.data.SyncPrefs
import si.skladisce.app.data.WarehouseRepository
import si.skladisce.app.data.db.AppDatabase
import si.skladisce.app.util.FeedbackManager

class SkladisceApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: AppDatabase
        private set
    lateinit var prefs: SyncPrefs
        private set
    lateinit var repository: WarehouseRepository
        private set
    lateinit var bleSyncManager: BleMeshSyncManager
        private set
    lateinit var feedback: FeedbackManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        prefs = SyncPrefs(this)
        repository = WarehouseRepository(database.warehouseDao(), prefs)
        bleSyncManager = BleMeshSyncManager(this, repository, prefs)
        feedback = FeedbackManager(this)

        repository.onDatabaseMutated = {
            bleSyncManager.refreshPayloadAndAdvertise()
        }

        appScope.launch {
            repository.initializeAppDatabase()
            if (bleSyncManager.hasBlePermissions()) {
                bleSyncManager.startAutoSync()
            }
        }
    }
}
