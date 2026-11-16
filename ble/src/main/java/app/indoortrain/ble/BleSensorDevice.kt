package app.indoortrain.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import app.indoortrain.domain.devices.ConnectionState
import app.indoortrain.domain.devices.DeviceStatus
import app.indoortrain.domain.devices.SensorDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.exception.BluetoothDisabledException
import no.nordicsemi.android.ble.exception.DeviceDisconnectedException
import no.nordicsemi.android.ble.exception.RequestFailedException
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.observer.ConnectionObserver

/**
 * Base for BLE sensors: connection handling, status flow and Device Information / Battery reads.
 * Subclasses provide a [SensorBleManager] that discovers and subscribes to their own service.
 */
@SuppressLint("MissingPermission") // callers ensure BLUETOOTH_CONNECT is granted
abstract class BleSensorDevice(protected val context: Context) : SensorDevice {

    protected val _status = MutableStateFlow(DeviceStatus())
    override val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    protected abstract val manager: SensorBleManager

    /** Called when the link is gone; clear live values here. */
    protected abstract fun onDisconnected()

    private val adapter
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    protected fun observe(m: BleManager) {
        m.connectionObserver = object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) = set(ConnectionState.Connecting)
            override fun onDeviceConnected(device: BluetoothDevice) = set(ConnectionState.Connecting)
            override fun onDeviceReady(device: BluetoothDevice) = set(ConnectionState.Connected)
            override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                set(ConnectionState.Disconnected, connectionReasonMessage(reason))
            }

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                onDisconnected()
                val error = if (reason == ConnectionObserver.REASON_LINK_LOSS) "Connection lost" else null
                set(ConnectionState.Disconnected, error)
            }
        }
    }

    private fun set(state: ConnectionState, error: String? = null) {
        _status.update {
            it.copy(
                connection = state,
                error = if (state == ConnectionState.Connected) null else error ?: it.error,
            )
        }
    }

    override suspend fun connect(address: String): Boolean {
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            _status.update { it.copy(connection = ConnectionState.Disconnected, address = address, error = "Bluetooth is off") }
            return false
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            _status.update { it.copy(error = "Invalid address $address") }
            return false
        }
        _status.update { it.copy(address = address, name = device.name ?: it.name) }
        return try {
            manager.connect(device)
                .retry(2, 500)
                .useAutoConnect(false)
                .timeout(CONNECT_TIMEOUT_MS)
                .suspend()
            set(ConnectionState.Connected)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "connect $address failed: $e")
            _status.update { it.copy(connection = ConnectionState.Disconnected, error = errorMessage(e)) }
            false
        }
    }

    override suspend fun disconnect() {
        try {
            manager.disconnect().suspend()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // not connected – nothing to do
        }
        onDisconnected()
        _status.update { it.copy(connection = ConnectionState.Disconnected) }
    }

    /** Applies Device Information / Battery values read by the manager. */
    internal fun onInfo(model: String? = null, firmware: String? = null, battery: Int? = null) {
        _status.update {
            it.copy(
                model = model ?: it.model,
                firmware = firmware ?: it.firmware,
                batteryPercent = battery ?: it.batteryPercent,
            )
        }
    }

    /** BleManager with common optional services. */
    abstract inner class SensorBleManager : BleManager(context) {
        private var modelChar: BluetoothGattCharacteristic? = null
        private var firmwareChar: BluetoothGattCharacteristic? = null
        private var batteryChar: BluetoothGattCharacteristic? = null

        protected abstract fun findRequired(gatt: BluetoothGatt): Boolean
        protected open fun findOptional(gatt: BluetoothGatt) = Unit
        protected abstract fun initializeSensor()
        protected open fun invalidate() = Unit

        final override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean = findRequired(gatt)

        final override fun isOptionalServiceSupported(gatt: BluetoothGatt): Boolean {
            gatt.getService(GattUuids.DEVICE_INFORMATION_SERVICE)?.let { s ->
                modelChar = s.getCharacteristic(GattUuids.MODEL_NUMBER)
                firmwareChar = s.getCharacteristic(GattUuids.FIRMWARE_REVISION)
            }
            batteryChar = gatt.getService(GattUuids.BATTERY_SERVICE)?.getCharacteristic(GattUuids.BATTERY_LEVEL)
            findOptional(gatt)
            return true
        }

        final override fun initialize() {
            initializeSensor()
            modelChar?.let { c -> readCharacteristic(c).with { _, d -> onInfo(model = d.string()) }.enqueue() }
            firmwareChar?.let { c -> readCharacteristic(c).with { _, d -> onInfo(firmware = d.string()) }.enqueue() }
            batteryChar?.let { c ->
                readCharacteristic(c).with { _, d -> onInfo(battery = d.getIntValue(Data.FORMAT_UINT8, 0)) }.enqueue()
            }
        }

        final override fun onServicesInvalidated() {
            modelChar = null
            firmwareChar = null
            batteryChar = null
            invalidate()
        }

        override fun getMinLogPriority(): Int = Log.INFO

        override fun log(priority: Int, message: String) {
            Log.println(priority, TAG, message)
        }

        private fun Data.string(): String? = getStringValue(0)?.trim()?.trimEnd('\u0000')
    }

    companion object {
        private const val TAG = "BleSensor"

        internal fun errorMessage(e: Exception): String = when (e) {
            is BluetoothDisabledException -> "Bluetooth is off"
            is DeviceDisconnectedException -> "Device disconnected"
            is RequestFailedException -> when (e.status) {
                FailCallback.REASON_TIMEOUT -> NOT_REACHABLE
                FailCallback.REASON_DEVICE_NOT_SUPPORTED -> "Device does not offer the expected Bluetooth service"
                FailCallback.REASON_BLUETOOTH_DISABLED -> "Bluetooth is off"
                FailCallback.REASON_CANCELLED -> "Connection cancelled"
                else -> "Connection failed (code ${e.status})"
            }
            else -> e.message ?: e.javaClass.simpleName
        }

        internal fun connectionReasonMessage(reason: Int): String = when (reason) {
            ConnectionObserver.REASON_TIMEOUT -> NOT_REACHABLE
            ConnectionObserver.REASON_NOT_SUPPORTED -> "Device does not offer the expected Bluetooth service"
            ConnectionObserver.REASON_LINK_LOSS -> "Connection lost"
            else -> "Connection failed (reason $reason)"
        }

        private const val NOT_REACHABLE = "Not reachable – is the device awake and nearby?"
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
