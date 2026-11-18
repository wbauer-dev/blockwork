package app.blockwork.domain.sim

import app.blockwork.domain.BikeSample
import app.blockwork.domain.HeartRateSample
import app.blockwork.domain.devices.ConnectionState
import app.blockwork.domain.devices.ControlResult
import app.blockwork.domain.devices.DeviceStatus
import app.blockwork.domain.devices.HeartRateMonitor
import app.blockwork.domain.devices.PowerRange
import app.blockwork.domain.devices.SensorDevice
import app.blockwork.domain.devices.SmartTrainer
import app.blockwork.domain.devices.TrainerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cbrt
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Synthetic devices for development and tests (no real health data involved).
 * Both tick once per second while connected.
 */
abstract class SimulatedDevice(
    protected val scope: CoroutineScope,
    private val displayName: String,
) : SensorDevice {
    protected val _status = MutableStateFlow(DeviceStatus())
    override val status: StateFlow<DeviceStatus> = _status.asStateFlow()
    private var job: Job? = null

    override suspend fun connect(address: String): Boolean {
        job?.cancel()
        _status.value = DeviceStatus(
            connection = ConnectionState.Connected,
            address = address,
            name = displayName,
            batteryPercent = 87,
            model = "Simulator",
            firmware = "sim",
        )
        job = scope.launch {
            while (isActive) {
                tick()
                delay(1_000)
            }
        }
        return true
    }

    override suspend fun disconnect() {
        job?.cancel()
        job = null
        onDisconnected()
        _status.value = _status.value.copy(connection = ConnectionState.Disconnected)
    }

    protected abstract fun tick()

    protected abstract fun onDisconnected()
}

class SimulatedSmartTrainer(
    scope: CoroutineScope,
    private val random: Random = Random.Default,
) : SimulatedDevice(scope, "Simulated KICKR"), SmartTrainer {

    private val _bikeData = MutableStateFlow<BikeSample?>(null)
    override val bikeData: StateFlow<BikeSample?> = _bikeData.asStateFlow()

    override val capabilities: StateFlow<TrainerCapabilities> = MutableStateFlow(
        TrainerCapabilities(
            cadenceSupported = true,
            powerMeasurementSupported = true,
            powerTargetSupported = true,
            powerRange = PowerRange(0, 1500, 1),
        ),
    )

    /** ERG target, null = free riding. */
    private val target = MutableStateFlow<Int?>(null)
    val targetWatts: StateFlow<Int?> = target.asStateFlow()

    private var power = FREE_RIDE_WATTS.toDouble()

    /** false = the simulated rider stopped pedaling (cadence, power and speed drop to 0). */
    @Volatile var pedaling: Boolean = true

    override suspend fun setTargetPower(watts: Int): ControlResult {
        if (status.value.connection != ConnectionState.Connected) return ControlResult.NotConnected
        target.value = capabilities.value.powerRange?.clamp(watts) ?: watts
        return ControlResult.Success
    }

    override suspend fun releaseControl(): ControlResult {
        target.value = null
        return ControlResult.Success
    }

    override fun tick() {
        if (!pedaling) {
            power = 0.0
            _bikeData.value = BikeSample(powerWatts = 0, cadenceRpm = 0.0, speedKmh = 0.0)
            return
        }
        val goal = (target.value ?: FREE_RIDE_WATTS).toDouble()
        // ERG settles within a few seconds; small pedalling noise on top.
        power += (goal - power) * 0.5
        val watts = (power + random.nextDouble(-4.0, 4.0)).coerceAtLeast(0.0).roundToInt()
        _bikeData.value = BikeSample(
            powerWatts = watts,
            cadenceRpm = (90 + random.nextDouble(-3.0, 3.0)).roundToHalf(),
            speedKmh = speedForPower(watts),
        )
    }

    override fun onDisconnected() {
        _bikeData.value = null
        target.value = null
    }

    companion object {
        const val FREE_RIDE_WATTS = 150

        /** Rough flat-road speed for a given power; good enough for display. */
        fun speedForPower(watts: Int): Double = (3.6 * cbrt(watts / 0.5) * 100).roundToInt() / 100.0

        private fun Double.roundToHalf() = (this * 2).roundToInt() / 2.0
    }
}

class SimulatedHeartRateMonitor(
    scope: CoroutineScope,
    private val random: Random = Random.Default,
    /** Current power, so heart rate follows the effort. */
    private val powerSource: () -> Int?,
) : SimulatedDevice(scope, "Simulated H9"), HeartRateMonitor {

    private val _heartRate = MutableStateFlow<HeartRateSample?>(null)
    override val heartRate: StateFlow<HeartRateSample?> = _heartRate.asStateFlow()

    private var bpm = RESTING_BPM.toDouble()

    override fun tick() {
        val goal = RESTING_BPM + 0.35 * (powerSource() ?: 0)
        bpm += (goal - bpm) * 0.05 // ~20 s time constant
        val value = (bpm + random.nextDouble(-1.0, 1.0)).roundToInt()
        _heartRate.value = HeartRateSample(
            bpm = value,
            rrIntervalsMs = listOf((60_000.0 / value).roundToInt()),
            sensorContact = true,
        )
    }

    override fun onDisconnected() {
        _heartRate.value = null
    }

    companion object {
        const val RESTING_BPM = 65
    }
}
