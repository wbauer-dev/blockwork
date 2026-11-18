package app.blockwork.ble

import java.util.UUID

/** Bluetooth SIG assigned numbers used by the app. */
object GattUuids {
    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    // Heart Rate Service
    val HEART_RATE_SERVICE: UUID = uuid16(0x180D)
    val HEART_RATE_MEASUREMENT: UUID = uuid16(0x2A37)

    // Device Information
    val DEVICE_INFORMATION_SERVICE: UUID = uuid16(0x180A)
    val MODEL_NUMBER: UUID = uuid16(0x2A24)
    val FIRMWARE_REVISION: UUID = uuid16(0x2A26)

    // Battery Service
    val BATTERY_SERVICE: UUID = uuid16(0x180F)
    val BATTERY_LEVEL: UUID = uuid16(0x2A19)

    // Fitness Machine Service (FTMS)
    val FITNESS_MACHINE_SERVICE: UUID = uuid16(0x1826)
    val FITNESS_MACHINE_FEATURE: UUID = uuid16(0x2ACC)
    val INDOOR_BIKE_DATA: UUID = uuid16(0x2AD2)
    val TRAINING_STATUS: UUID = uuid16(0x2AD3)
    val SUPPORTED_RESISTANCE_LEVEL_RANGE: UUID = uuid16(0x2AD6)
    val SUPPORTED_POWER_RANGE: UUID = uuid16(0x2AD8)
    val FITNESS_MACHINE_CONTROL_POINT: UUID = uuid16(0x2AD9)
    val FITNESS_MACHINE_STATUS: UUID = uuid16(0x2ADA)

    // Cycling Power Service (fallback)
    val CYCLING_POWER_SERVICE: UUID = uuid16(0x1818)
    val CYCLING_POWER_MEASUREMENT: UUID = uuid16(0x2A63)
}
