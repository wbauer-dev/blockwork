package app.blockwork.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import app.blockwork.domain.BikeSample
import app.blockwork.domain.devices.ConnectionState
import app.blockwork.domain.devices.ControlResult
import app.blockwork.domain.devices.SmartTrainer
import app.blockwork.domain.devices.TrainerCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nordicsemi.android.ble.ktx.suspend

/** FTMS smart trainer (0x1826), e.g. Wahoo KICKR Core, controlled in ERG mode. */
class BleSmartTrainer(context: Context) : BleSensorDevice(context), SmartTrainer {

    private val _bikeData = MutableStateFlow<BikeSample?>(null)
    override val bikeData: StateFlow<BikeSample?> = _bikeData.asStateFlow()

    private val _capabilities = MutableStateFlow(TrainerCapabilities())
    override val capabilities: StateFlow<TrainerCapabilities> = _capabilities.asStateFlow()

    private val ftms = Manager()
    override val manager: SensorBleManager = ftms.also(::observe)

    private val controlMutex = Mutex()

    @Volatile
    private var hasControl = false

    override fun onDisconnected() {
        _bikeData.value = null
        hasControl = false
    }

    override suspend fun setTargetPower(watts: Int): ControlResult = controlMutex.withLock {
        if (status.value.connection != ConnectionState.Connected) return ControlResult.NotConnected
        if (!ftms.hasControlPoint || capabilities.value.powerTargetSupported == false) return ControlResult.NotSupported
        if (!hasControl) {
            val r = ftms.control(FtmsControlPoint.requestControl())
            if (r != ControlResult.Success) return r
            hasControl = true
            ftms.control(FtmsControlPoint.startOrResume()) // some trainers need it, failure is fine
        }
        val target = capabilities.value.powerRange?.clamp(watts) ?: watts
        ftms.control(FtmsControlPoint.setTargetPower(target)).also {
            if (it == ControlResult.Rejected) hasControl = false
        }
    }

    override suspend fun releaseControl(): ControlResult = controlMutex.withLock {
        if (status.value.connection != ConnectionState.Connected) return ControlResult.NotConnected
        if (!hasControl) return ControlResult.Success
        ftms.control(FtmsControlPoint.stop())
        // Reset ends the control session; the trainer drops the ERG target.
        ftms.control(FtmsControlPoint.reset()).also { hasControl = false }
    }

    private inner class Manager : SensorBleManager() {
        private var bikeDataChar: BluetoothGattCharacteristic? = null
        private var controlPoint: BluetoothGattCharacteristic? = null
        private var statusChar: BluetoothGattCharacteristic? = null
        private var featureChar: BluetoothGattCharacteristic? = null
        private var powerRangeChar: BluetoothGattCharacteristic? = null

        val hasControlPoint: Boolean get() = controlPoint != null

        override fun findRequired(gatt: BluetoothGatt): Boolean {
            val s = gatt.getService(GattUuids.FITNESS_MACHINE_SERVICE) ?: return false
            bikeDataChar = s.getCharacteristic(GattUuids.INDOOR_BIKE_DATA)
            controlPoint = s.getCharacteristic(GattUuids.FITNESS_MACHINE_CONTROL_POINT)
            statusChar = s.getCharacteristic(GattUuids.FITNESS_MACHINE_STATUS)
            featureChar = s.getCharacteristic(GattUuids.FITNESS_MACHINE_FEATURE)
            powerRangeChar = s.getCharacteristic(GattUuids.SUPPORTED_POWER_RANGE)
            return bikeDataChar != null
        }

        override fun initializeSensor() {
            setNotificationCallback(bikeDataChar).with { _, data ->
                data.value?.let(IndoorBikeDataParser::parse)?.let { _bikeData.value = it }
            }
            enableNotifications(bikeDataChar).enqueue()

            controlPoint?.let { enableIndications(it).enqueue() }

            statusChar?.let { c ->
                setNotificationCallback(c).with { _, data ->
                    val op = data.getIntValue(no.nordicsemi.android.ble.data.Data.FORMAT_UINT8, 0)
                    if (op == STATUS_CONTROL_PERMISSION_LOST || op == STATUS_RESET) hasControl = false
                }
                enableNotifications(c).enqueue()
            }

            featureChar?.let { c ->
                readCharacteristic(c).with { _, data ->
                    data.value?.let(FtmsCapabilitiesParser::parseFeature)?.let { f ->
                        _capabilities.update { f.copy(powerRange = it.powerRange) }
                    }
                }.enqueue()
            }
            powerRangeChar?.let { c ->
                readCharacteristic(c).with { _, data ->
                    data.value?.let(FtmsCapabilitiesParser::parsePowerRange)?.let { r ->
                        _capabilities.update { it.copy(powerRange = r) }
                    }
                }.enqueue()
            }
        }

        override fun invalidate() {
            bikeDataChar = null
            controlPoint = null
            statusChar = null
            featureChar = null
            powerRangeChar = null
        }

        /** Writes a control point command and waits for its indication response. */
        suspend fun control(command: ByteArray): ControlResult {
            val cp = controlPoint ?: return ControlResult.NotSupported
            return try {
                val data = waitForIndication(cp)
                    .trigger(writeCharacteristic(cp, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
                    .timeout(CONTROL_TIMEOUT_MS)
                    .suspend()
                val response = data.value?.let(FtmsControlPoint::parseResponse)
                when {
                    response == null || response.requestOpCode != (command[0].toInt() and 0xFF) -> ControlResult.Failed
                    else -> when (response.result) {
                        FtmsControlPoint.Result.Success -> ControlResult.Success
                        FtmsControlPoint.Result.OpCodeNotSupported -> ControlResult.NotSupported
                        FtmsControlPoint.Result.ControlNotPermitted -> ControlResult.Rejected
                        else -> ControlResult.Failed
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "control 0x%02X failed".format(command[0]), e)
                ControlResult.Failed
            }
        }
    }

    companion object {
        private const val TAG = "BleSmartTrainer"
        private const val CONTROL_TIMEOUT_MS = 3_000L
        private const val STATUS_RESET = 0x01
        private const val STATUS_CONTROL_PERMISSION_LOST = 0xFF
    }
}
