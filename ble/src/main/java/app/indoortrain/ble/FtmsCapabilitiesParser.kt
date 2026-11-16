package app.indoortrain.ble

import app.indoortrain.domain.devices.PowerRange
import app.indoortrain.domain.devices.TrainerCapabilities

/** Parsers for FTMS Fitness Machine Feature (0x2ACC) and Supported Power Range (0x2AD8). */
object FtmsCapabilitiesParser {
    private const val FEATURE_CADENCE = 1 shl 1
    private const val FEATURE_POWER_MEASUREMENT = 1 shl 14
    private const val TARGET_POWER = 1 shl 3

    /** 8 bytes: uint32 machine features + uint32 target setting features. */
    fun parseFeature(value: ByteArray): TrainerCapabilities? {
        if (value.size < 8) return null
        val r = ByteReader(value)
        val machine = r.uint16() or (r.uint16() shl 16)
        val targets = r.uint16() or (r.uint16() shl 16)
        return TrainerCapabilities(
            cadenceSupported = machine and FEATURE_CADENCE != 0,
            powerMeasurementSupported = machine and FEATURE_POWER_MEASUREMENT != 0,
            powerTargetSupported = targets and TARGET_POWER != 0,
            featureRawHex = value.toHex(),
        )
    }

    /** 6 bytes: sint16 min, sint16 max, uint16 increment (watts). */
    fun parsePowerRange(value: ByteArray): PowerRange? {
        if (value.size < 6) return null
        val r = ByteReader(value)
        return PowerRange(minWatts = r.sint16(), maxWatts = r.sint16(), incrementWatts = r.uint16())
    }
}

internal fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
