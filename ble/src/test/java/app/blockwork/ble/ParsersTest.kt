package app.blockwork.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

class HeartRateParserTest {

    @Test fun uint8HeartRateWithoutContactSupport() {
        val s = HeartRateParser.parse(bytes(0x00, 72))!!
        assertThat(s.bpm).isEqualTo(72)
        assertThat(s.sensorContact).isNull()
        assertThat(s.rrIntervalsMs).isEmpty()
    }

    @Test fun contactAndRrIntervals() {
        // flags: contact supported+detected (0x06) | RR present (0x10); HR 150; RR 410/1024 s and 1024/1024 s
        val s = HeartRateParser.parse(bytes(0x16, 150, 0x9A, 0x01, 0x00, 0x04))!!
        assertThat(s.bpm).isEqualTo(150)
        assertThat(s.sensorContact).isTrue()
        assertThat(s.rrIntervalsMs).containsExactly(400, 1000).inOrder()
    }

    @Test fun uint16HeartRateWithEnergyExpended() {
        val s = HeartRateParser.parse(bytes(0x09, 0x2C, 0x01, 0x10, 0x00))!!
        assertThat(s.bpm).isEqualTo(300)
    }

    @Test fun truncatedPayloadReturnsNull() {
        assertThat(HeartRateParser.parse(bytes(0x01, 0x2C))).isNull()
        assertThat(HeartRateParser.parse(bytes(0x00))).isNull()
    }
}

class IndoorBikeDataParserTest {

    @Test fun speedCadencePower() {
        // flags 0x0044: speed present (bit0 = 0), cadence (bit2), power (bit6)
        // speed 3250 -> 32.5 km/h, cadence 180 -> 90 rpm, power 250 W
        val s = IndoorBikeDataParser.parse(bytes(0x44, 0x00, 0xB2, 0x0C, 0xB4, 0x00, 0xFA, 0x00))!!
        assertThat(s.speedKmh).isWithin(1e-9).of(32.5)
        assertThat(s.cadenceRpm).isWithin(1e-9).of(90.0)
        assertThat(s.powerWatts).isEqualTo(250)
        assertThat(s.totalDistanceMeters).isNull()
    }

    @Test fun skipsOptionalFieldsBeforePower() {
        // flags 0x0077: no speed (bit0=1), avg speed(bit1), cadence(bit2), distance(bit4), resistance(bit5), power(bit6)
        val s = IndoorBikeDataParser.parse(
            bytes(
                0x77, 0x00,       // flags incl. bit0 More Data
                0x10, 0x27,       // avg speed
                0xA0, 0x00,       // cadence 160 -> 80 rpm
                0x10, 0x27, 0x00, // distance 10000 m
                0x05, 0x00,       // resistance
                0x2C, 0x01,       // power 300 W
            ),
        )!!
        assertThat(s.speedKmh).isNull()
        assertThat(s.cadenceRpm).isWithin(1e-9).of(80.0)
        assertThat(s.totalDistanceMeters).isEqualTo(10000)
        assertThat(s.powerWatts).isEqualTo(300)
    }

    /** KICKR Core sample (nRF Connect 2026-11-16): 30.43 km/h, 93.0 rpm, 282 W; payload rebuilt from decoded values. */
    @Test fun kickrCoreSample() {
        val s = IndoorBikeDataParser.parse(bytes(0x44, 0x00, 0xE3, 0x0B, 0xBA, 0x00, 0x1A, 0x01))!!
        assertThat(s.speedKmh).isWithin(1e-9).of(30.43)
        assertThat(s.cadenceRpm).isWithin(1e-9).of(93.0)
        assertThat(s.powerWatts).isEqualTo(282)
    }

    @Test fun truncatedPayloadReturnsNull() {
        assertThat(IndoorBikeDataParser.parse(bytes(0x44, 0x00, 0xB2))).isNull()
    }
}

class FtmsControlPointTest {

    @Test fun setTargetPowerIsLittleEndian() {
        assertThat(FtmsControlPoint.setTargetPower(280)).isEqualTo(bytes(0x05, 0x18, 0x01))
    }

    @Test fun parsesSuccessResponse() {
        val r = FtmsControlPoint.parseResponse(bytes(0x80, 0x05, 0x01))!!
        assertThat(r.requestOpCode).isEqualTo(FtmsControlPoint.OP_SET_TARGET_POWER)
        assertThat(r.result).isEqualTo(FtmsControlPoint.Result.Success)
    }

    @Test fun ignoresNonResponse() {
        assertThat(FtmsControlPoint.parseResponse(bytes(0x05, 0x18, 0x01))).isNull()
    }
}

class FtmsCapabilitiesParserTest {

    @Test fun feature() {
        // machine: cadence (bit1) + power measurement (bit14) = 0x4002; targets: power (bit3) = 0x08
        val f = FtmsCapabilitiesParser.parseFeature(bytes(0x02, 0x40, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00))!!
        assertThat(f.cadenceSupported).isTrue()
        assertThat(f.powerMeasurementSupported).isTrue()
        assertThat(f.powerTargetSupported).isTrue()
        assertThat(f.featureRawHex).isEqualTo("02 40 00 00 08 00 00 00")
    }

    @Test fun featureWithoutPowerTarget() {
        val f = FtmsCapabilitiesParser.parseFeature(bytes(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))!!
        assertThat(f.powerTargetSupported).isFalse()
        assertThat(FtmsCapabilitiesParser.parseFeature(bytes(0x00, 0x00))).isNull()
    }

    @Test fun powerRange() {
        // min 0, max 1500 (0x05DC), increment 1
        val r = FtmsCapabilitiesParser.parsePowerRange(bytes(0x00, 0x00, 0xDC, 0x05, 0x01, 0x00))!!
        assertThat(r.minWatts).isEqualTo(0)
        assertThat(r.maxWatts).isEqualTo(1500)
        assertThat(r.incrementWatts).isEqualTo(1)
        assertThat(r.clamp(2000)).isEqualTo(1500)
    }
}
