package app.indoortrain.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import app.indoortrain.domain.devices.DeviceKind
import app.indoortrain.domain.devices.DiscoveredDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Scans for heart rate monitors (0x180D) and FTMS trainers (0x1826). */
@SuppressLint("MissingPermission") // callers ensure BLUETOOTH_SCAN is granted
class BleScanner(private val context: Context) {

    class ScanException(message: String) : Exception(message)

    /** Emits each advertisement of a matching device; scanning stops when collection ends. */
    fun scan(): Flow<DiscoveredDevice> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(ScanException("Bluetooth is off"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                map(result)?.let { trySend(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r -> map(r)?.let { trySend(it) } }
            }

            override fun onScanFailed(errorCode: Int) {
                close(ScanException("Scan failed (error $errorCode)"))
            }
        }
        val filters = listOf(GattUuids.HEART_RATE_SERVICE, GattUuids.FITNESS_MACHINE_SERVICE).map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, callback)
        awaitClose {
            runCatching { scanner.stopScan(callback) } // throws if Bluetooth was turned off meanwhile
        }
    }

    private fun map(result: ScanResult): DiscoveredDevice? {
        val uuids = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
        val kind = when {
            GattUuids.FITNESS_MACHINE_SERVICE in uuids -> DeviceKind.Trainer
            GattUuids.HEART_RATE_SERVICE in uuids -> DeviceKind.HeartRateMonitor
            else -> return null
        }
        return DiscoveredDevice(
            address = result.device.address,
            name = result.scanRecord?.deviceName ?: result.device.name,
            kind = kind,
            rssi = result.rssi,
        )
    }
}
