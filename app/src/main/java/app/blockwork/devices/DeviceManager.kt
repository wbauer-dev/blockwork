package app.blockwork.devices

import android.content.Context
import app.blockwork.ble.BleHeartRateMonitor
import app.blockwork.ble.BleSmartTrainer
import app.blockwork.data.DeviceSettings
import app.blockwork.data.SettingsRepository
import app.blockwork.di.ApplicationScope
import app.blockwork.domain.BikeSample
import app.blockwork.domain.HeartRateSample
import app.blockwork.domain.devices.ConnectionSupervisor
import app.blockwork.domain.devices.ControlResult
import app.blockwork.domain.devices.DeviceKind
import app.blockwork.domain.devices.DeviceStatus
import app.blockwork.domain.devices.DiscoveredDevice
import app.blockwork.domain.devices.HeartRateMonitor
import app.blockwork.domain.devices.PairedDevice
import app.blockwork.domain.devices.SensorDevice
import app.blockwork.domain.devices.SmartTrainer
import app.blockwork.domain.devices.TrainerCapabilities
import app.blockwork.domain.sim.SimulatedHeartRateMonitor
import app.blockwork.domain.sim.SimulatedSmartTrainer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the active heart rate monitor and trainer (real BLE or simulated) and keeps the
 * paired devices connected while permissions are granted and Bluetooth is on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DeviceManager @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsRepository,
    private val bleHeartRate: BleHeartRateMonitor,
    private val bleTrainer: BleSmartTrainer,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val simTrainer = SimulatedSmartTrainer(scope)
    private val simHeartRate = SimulatedHeartRateMonitor(scope) { simTrainer.bikeData.value?.powerWatts }

    private val _permissionsGranted = MutableStateFlow(BluetoothPermissions.granted(context))
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    val bluetoothEnabled: StateFlow<Boolean> = BluetoothPermissions.enabledChanges(context)
        .stateIn(scope, SharingStarted.Eagerly, BluetoothPermissions.isEnabled(context))

    val deviceSettings: StateFlow<DeviceSettings> = settings.devices
        .stateIn(scope, SharingStarted.Eagerly, DeviceSettings())

    val simulation: StateFlow<Boolean> = deviceSettings.map { it.useSimulation }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val heartRateMonitor: StateFlow<HeartRateMonitor> = simulation
        .map { if (it) simHeartRate else bleHeartRate }
        .stateIn(scope, SharingStarted.Eagerly, bleHeartRate)

    val trainer: StateFlow<SmartTrainer> = simulation
        .map { if (it) simTrainer else bleTrainer }
        .stateIn(scope, SharingStarted.Eagerly, bleTrainer)

    val heartRateStatus: StateFlow<DeviceStatus> = heartRateMonitor.flatMapLatest { it.status }.eager(DeviceStatus())
    val trainerStatus: StateFlow<DeviceStatus> = trainer.flatMapLatest { it.status }.eager(DeviceStatus())
    val heartRate: StateFlow<HeartRateSample?> = heartRateMonitor.flatMapLatest { it.heartRate }.eager(null)
    val bikeData: StateFlow<BikeSample?> = trainer.flatMapLatest { it.bikeData }.eager(null)
    val trainerCapabilities: StateFlow<TrainerCapabilities> =
        trainer.flatMapLatest { it.capabilities }.eager(TrainerCapabilities())

    init {
        val environment = combine(deviceSettings, permissionsGranted, bluetoothEnabled) { d, perm, bt ->
            Environment(d, perm && bt)
        }
        supervise(
            environment.map { env ->
                when {
                    env.devices.useSimulation -> simHeartRate to SIM_ADDRESS
                    env.bleReady -> env.devices.heartRateMonitor?.let { bleHeartRate to it.address }
                    else -> null
                }
            },
        )
        supervise(
            environment.map { env ->
                when {
                    env.devices.useSimulation -> simTrainer to SIM_ADDRESS
                    env.bleReady -> env.devices.trainer?.let { bleTrainer to it.address }
                    else -> null
                }
            },
        )
    }

    fun onPermissionsChanged(granted: Boolean) {
        _permissionsGranted.value = granted
    }

    suspend fun pair(device: DiscoveredDevice) =
        settings.setPairedDevice(PairedDevice(device.address, device.name, device.kind))

    suspend fun forget(kind: DeviceKind) = settings.forgetDevice(kind)

    suspend fun setSimulation(enabled: Boolean) = settings.setUseSimulation(enabled)

    suspend fun setTargetPower(watts: Int): ControlResult = trainer.value.setTargetPower(watts)

    suspend fun releaseTrainerControl(): ControlResult = trainer.value.releaseControl()

    /** One supervisor per device slot; restarts when the target device/address changes. */
    private fun supervise(target: Flow<Pair<SensorDevice, String>?>) {
        scope.launch {
            target.distinctUntilChanged().collectLatest { t ->
                if (t != null) ConnectionSupervisor(t.first).maintain(t.second)
            }
        }
    }

    private fun <T> Flow<T>.eager(initial: T): StateFlow<T> = stateIn(scope, SharingStarted.Eagerly, initial)

    private data class Environment(val devices: DeviceSettings, val bleReady: Boolean)

    companion object {
        const val SIM_ADDRESS = "SIMULATOR"
    }
}
