package app.gyro

import android.app.Application
import android.content.Context
import app.gyro.ble.BleScanner
import app.gyro.ble.WheelConnectionManager
import app.gyro.data.RideMonitor
import app.gyro.data.UserPrefs
import app.gyro.data.db.GyroDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GyroApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency graph; one instance per process, shared by the UI and the foreground service. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: GyroDatabase = GyroDatabase.create(context)
    val prefs = UserPrefs(context)
    val scanner = BleScanner(context)
    val connection = WheelConnectionManager(context, appScope, db.knownWheels())
    val rideMonitor = RideMonitor(appScope, connection, prefs, db)
}
