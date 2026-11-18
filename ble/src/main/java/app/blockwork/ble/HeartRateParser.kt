package app.blockwork.ble

import app.blockwork.domain.HeartRateSample
import kotlin.math.roundToInt

/** Parses the Heart Rate Measurement characteristic (0x2A37). */
object HeartRateParser {
    private const val FLAG_HR_UINT16 = 0x01
    private const val FLAG_CONTACT_DETECTED = 0x02
    private const val FLAG_CONTACT_SUPPORTED = 0x04
    private const val FLAG_ENERGY_EXPENDED = 0x08
    private const val FLAG_RR_INTERVALS = 0x10

    fun parse(value: ByteArray): HeartRateSample? {
        if (value.size < 2) return null
        return try {
            val r = ByteReader(value)
            val flags = r.uint8()
            val bpm = if (flags and FLAG_HR_UINT16 != 0) r.uint16() else r.uint8()
            val contact = if (flags and FLAG_CONTACT_SUPPORTED != 0) {
                flags and FLAG_CONTACT_DETECTED != 0
            } else {
                null
            }
            if (flags and FLAG_ENERGY_EXPENDED != 0) r.skip(2)
            val rr = buildList {
                if (flags and FLAG_RR_INTERVALS != 0) {
                    while (r.remaining >= 2) add((r.uint16() * 1000.0 / 1024.0).roundToInt())
                }
            }
            HeartRateSample(bpm = bpm, rrIntervalsMs = rr, sensorContact = contact)
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }
}
