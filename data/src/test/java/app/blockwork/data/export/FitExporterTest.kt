package app.blockwork.data.export

import app.blockwork.domain.training.RideSample
import app.blockwork.domain.training.RrInterval
import com.garmin.fit.HrvMesg
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
        assertThat(messages.none { it.num == MesgNum.HRV }).isTrue()
    }

    @Test fun writesRrIntervalsAsHrvMessagesInArrivalOrder() {
        // Synthetic: two intervals per ride second, the first two before the first record.
        val rr = (0 until 240).map { i -> RrInterval(index = i, elapsedSec = i / 2, rrMs = 400 + i) }
        val messages = decode(FitExporter.export(FitExporter.RideInfo(start, 250), samples, rr.shuffled()))

        val hrv = messages.filter { it.num == MesgNum.HRV }.map { HrvMesg(it) }
        val times = hrv.flatMap { m -> (0 until m.numTime).map { m.getTime(it).toDouble() } }
        assertThat(times).hasSize(240)
        times.forEachIndexed { i, t -> assertThat(t).isWithin(0.0005).of((400 + i) / 1000.0) }
        assertThat(hrv.all { it.numTime <= 5 }).isTrue()

        // Interleaved with the records by arrival time.
        val firstRecord = messages.indexOfFirst { it.num == MesgNum.RECORD }
        val beforeFirst = messages.subList(0, firstRecord).filter { it.num == MesgNum.HRV }.map { HrvMesg(it).numTime }
        assertThat(beforeFirst).containsExactly(2)
        val secondRecord = messages.withIndex().filter { it.value.num == MesgNum.RECORD }[1].index
        val afterFirst = messages.subList(firstRecord, secondRecord).filter { it.num == MesgNum.HRV }.map { HrvMesg(it).numTime }
        assertThat(afterFirst).containsExactly(2)
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
