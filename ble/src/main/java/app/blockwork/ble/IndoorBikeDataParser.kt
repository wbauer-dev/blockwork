package app.blockwork.ble

import app.blockwork.domain.BikeSample

/**
 * Parses the FTMS Indoor Bike Data characteristic (0x2AD2).
 *
 * Fields appear in flag-bit order; note that bit 0 ("More Data") is inverted:
 * instantaneous speed is present when the bit is **0**.
 */
object IndoorBikeDataParser {

    fun parse(value: ByteArray): BikeSample? {
        if (value.size < 2) return null
        return try {
            val r = ByteReader(value)
            val flags = r.uint16()
            fun has(bit: Int) = flags and (1 shl bit) != 0

            var speed: Double? = null
            var cadence: Double? = null
            var distance: Int? = null
            var power: Int? = null

            if (!has(0)) speed = r.uint16() / 100.0         // instantaneous speed, 0.01 km/h
            if (has(1)) r.skip(2)                            // average speed
            if (has(2)) cadence = r.uint16() / 2.0           // instantaneous cadence, 0.5 rpm
            if (has(3)) r.skip(2)                            // average cadence
            if (has(4)) distance = r.uint24()                // total distance, m
            if (has(5)) r.skip(2)                            // resistance level
            if (has(6)) power = r.sint16()                   // instantaneous power, W
            if (has(7)) r.skip(2)                            // average power
            if (has(8)) r.skip(5)                            // expended energy (total, /h, /min)
            if (has(9)) r.skip(1)                            // heart rate
            if (has(10)) r.skip(1)                           // metabolic equivalent
            if (has(11)) r.skip(2)                           // elapsed time
            if (has(12)) r.skip(2)                           // remaining time

            BikeSample(
                powerWatts = power,
                cadenceRpm = cadence,
                speedKmh = speed,
                totalDistanceMeters = distance,
            )
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }
}
