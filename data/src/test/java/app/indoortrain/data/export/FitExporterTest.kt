package app.indoortrain.data.export

import app.indoortrain.domain.training.RideSample
import com.garmin.fit.Decode
import com.garmin.fit.LapMesg
import com.garmin.fit.Mesg
import com.garmin.fit.MesgNum
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

class FitExporterTest {

    // Synthetic ride: 2 steps of 60 s at 150/250 W, 10 s pause between them.
    private val start = 1_800_000_000_000L
    private val samples = (1..120).map { t ->
        val pauseShift = if (t > 60) 10_000L else 0L
        RideSample(
            elapsedSec = t,
            timestampMillis = start + t * 1000L + pauseShift,
            powerWatts = if (t <= 60) 150 else 250,
            cadenceRpm = 90.0,
            speedKmh = 36.0,
            heartRateBpm = 120 + t / 10,
            targetWatts = if (t <= 60) 150 else 250,
            stepIndex = if (t <= 60) 0 else 1,
        )
    }

    private fun decode(bytes: ByteArray): List<Mesg> {
        val decode = Decode()
        assertThat(decode.checkFileIntegrity(ByteArrayInputStream(bytes))).isTrue()
        val messages = mutableListOf<Mesg>()
        Decode().read(ByteArrayInputStream(bytes)) { messages += it }
        return messages
    }

    @Test fun writesValidActivity() {
        val bytes = FitExporter.export(FitExporter.RideInfo(start, ftp = 250), samples)
        val messages = decode(bytes)

        val records = messages.filter { it.num == MesgNum.RECORD }.map { RecordMesg(it) }
        assertThat(records).hasSize(120)
        assertThat(records.first().power).isEqualTo(150)
        assertThat(records.last().power).isEqualTo(250)
        assertThat(records.last().distance.toDouble()).isWithin(0.5).of(1200.0)
        assertThat(records.first().speed.toDouble()).isWithin(0.01).of(10.0)

        val laps = messages.filter { it.num == MesgNum.LAP }.map { LapMesg(it) }
        assertThat(laps.map { it.avgPower }).containsExactly(150, 250).inOrder()
        assertThat(laps.map { it.totalTimerTime }).containsExactly(60f, 60f).inOrder()

        val session = SessionMesg(messages.single { it.num == MesgNum.SESSION })
        assertThat(session.sport).isEqualTo(Sport.CYCLING)
        assertThat(session.subSport).isEqualTo(SubSport.INDOOR_CYCLING)
        assertThat(session.totalTimerTime).isEqualTo(120f)
        assertThat(session.totalElapsedTime).isEqualTo(130f)
        assertThat(session.avgPower).isEqualTo(200)
        assertThat(session.numLaps).isEqualTo(2)
        assertThat(session.maxHeartRate.toInt()).isEqualTo(132)
    }

    @Test fun handlesMissingSensors() {
        val bare = samples.map { it.copy(heartRateBpm = null, cadenceRpm = null, speedKmh = null) }
        val messages = decode(FitExporter.export(FitExporter.RideInfo(start, 250), bare))
        val record = RecordMesg(messages.first { it.num == MesgNum.RECORD })
        assertThat(record.heartRate).isNull()
        assertThat(record.cadence).isNull()
        assertThat(SessionMesg(messages.single { it.num == MesgNum.SESSION }).avgHeartRate).isNull()
    }
}
