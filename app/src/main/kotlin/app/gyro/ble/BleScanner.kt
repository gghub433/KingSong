package app.gyro.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import app.gyro.protocol.ProtocolDetector
import app.gyro.protocol.ProtocolFamily
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class ScannedWheel(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** Best guess from the name; null for unfamiliar names (new models are still listed). */
    val hint: ProtocolFamily?,
)

/**
 * Lists nearby BLE devices. There is no service filter: many wheels do not advertise their UART
 * service, so every named device is shown and likely wheels are sorted to the top.
 */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    sealed interface Update {
        data class Devices(val wheels: List<ScannedWheel>) : Update
        data class Error(val message: String) : Update
    }

    fun scan(): Flow<Update> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            trySend(Update.Error("Включите Bluetooth"))
            close()
            return@callbackFlow
        }
        val found = linkedMapOf<String, ScannedWheel>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                val address = result.device.address
                found[address] = ScannedWheel(address, name, result.rssi, ProtocolDetector.hintFromName(name))
                trySend(Update.Devices(sorted(found.values)))
            }

            override fun onScanFailed(errorCode: Int) {
                trySend(Update.Error("Ошибка поиска Bluetooth ($errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(null, settings, callback) }
            .onFailure { trySend(Update.Error("Нет разрешения на поиск Bluetooth")) }
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    private fun sorted(wheels: Collection<ScannedWheel>): List<ScannedWheel> =
        wheels.filter { it.name != null || it.hint != null }
            .sortedWith(compareBy<ScannedWheel> { it.hint == null }.thenByDescending { it.rssi })
}
