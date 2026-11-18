package app.blockwork.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import app.blockwork.domain.HeartRateSample
import app.blockwork.domain.devices.HeartRateMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Standard BLE Heart Rate Service (0x180D), e.g. Polar H9. */
class BleHeartRateMonitor(context: Context) : BleSensorDevice(context), HeartRateMonitor {

    private val _heartRate = MutableStateFlow<HeartRateSample?>(null)
    override val heartRate: StateFlow<HeartRateSample?> = _heartRate.asStateFlow()

    override val manager: SensorBleManager = Manager().also(::observe)

    override fun onDisconnected() {
        _heartRate.value = null
    }

    private inner class Manager : SensorBleManager() {
        private var measurement: BluetoothGattCharacteristic? = null

        override fun findRequired(gatt: BluetoothGatt): Boolean {
            measurement = gatt.getService(GattUuids.HEART_RATE_SERVICE)
                ?.getCharacteristic(GattUuids.HEART_RATE_MEASUREMENT)
            return measurement != null
        }

        override fun initializeSensor() {
            setNotificationCallback(measurement).with { _, data ->
                data.value?.let(HeartRateParser::parse)?.let { _heartRate.value = it }
            }
            enableNotifications(measurement).enqueue()
        }

        override fun invalidate() {
            measurement = null
        }
    }
}
