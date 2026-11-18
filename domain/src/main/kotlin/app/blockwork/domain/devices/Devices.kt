package app.blockwork.domain.devices

import app.blockwork.domain.BikeSample
import app.blockwork.domain.HeartRateSample
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState { Disconnected, Connecting, Connected }

enum class DeviceKind { HeartRateMonitor, Trainer }

/** A device found while scanning. */
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val kind: DeviceKind,
    val rssi: Int,
)

/** A device the user selected; the app reconnects to it automatically. */
data class PairedDevice(
    val address: String,
    val name: String?,
    val kind: DeviceKind,
)

data class DeviceStatus(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val address: String? = null,
    val name: String? = null,
    val batteryPercent: Int? = null,
    val model: String? = null,
    val firmware: String? = null,
    /** Last connection error, cleared on successful connect. */
    val error: String? = null,
)

/** Common part of all sensors. Implementations must be safe to call from any coroutine. */
interface SensorDevice {
    val status: StateFlow<DeviceStatus>

    /**
     * Connects to [address] and suspends until the device is ready (true) or the attempt
     * failed (false). Never throws except for cancellation.
     */
    suspend fun connect(address: String): Boolean

    suspend fun disconnect()
}

interface HeartRateMonitor : SensorDevice {
    /** Latest sample; null while disconnected. */
    val heartRate: StateFlow<HeartRateSample?>
}

data class PowerRange(val minWatts: Int, val maxWatts: Int, val incrementWatts: Int) {
    fun clamp(watts: Int): Int = watts.coerceIn(minWatts, maxWatts)
}

data class TrainerCapabilities(
    val cadenceSupported: Boolean? = null,
    val powerMeasurementSupported: Boolean? = null,
    val powerTargetSupported: Boolean? = null,
    val powerRange: PowerRange? = null,
    /** Raw Fitness Machine Feature value as hex, for diagnostics. */
    val featureRawHex: String? = null,
)

enum class ControlResult { Success, NotConnected, NotSupported, Rejected, Failed }

interface SmartTrainer : SensorDevice {
    /** Latest sample; null while disconnected. */
    val bikeData: StateFlow<BikeSample?>

    val capabilities: StateFlow<TrainerCapabilities>

    /** Sets the ERG target. Requests control from the trainer first if needed. */
    suspend fun setTargetPower(watts: Int): ControlResult

    /** Stops ERG mode and releases control (trainer goes back to free resistance). */
    suspend fun releaseControl(): ControlResult
}
