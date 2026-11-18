package app.blockwork.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import app.blockwork.domain.HeartRateSample
import app.blockwork.domain.devices.HeartRateMonitor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Standard BLE Heart Rate Service (0x180D), e.g. Polar H9. */
class BleHeartRateMonitor(context: Context) : BleSensorDevice(context), HeartRateMonitor {

    private val _heartRate = MutableStateFlow<HeartRateSample?>(null)
    override val heartRate: StateFlow<HeartRateSample?> = _heartRate.asStateFlow()

    private val _rrIntervals = MutableSharedFlow<List<Int>>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val rrIntervals: SharedFlow<List<Int>> = _rrIntervals.asSharedFlow()

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
                val sample = data.value?.let(HeartRateParser::parse) ?: return@with
                _heartRate.value = sample
                if (sample.rrIntervalsMs.isNotEmpty()) _rrIntervals.tryEmit(sample.rrIntervalsMs)
            }
            enableNotifications(measurement).enqueue()
        }

        override fun invalidate() {
            measurement = null
        }
    }
}
