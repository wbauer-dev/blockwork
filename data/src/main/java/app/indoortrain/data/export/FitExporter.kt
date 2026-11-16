package app.indoortrain.data.export

import app.indoortrain.domain.training.RideSample
import app.indoortrain.domain.training.RideStats
import com.garmin.fit.Activity
import com.garmin.fit.ActivityMesg
import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.LapMesg
import com.garmin.fit.LapTrigger
import com.garmin.fit.Manufacturer
import com.garmin.fit.Mesg
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import java.util.Date
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Writes a ride as a FIT activity file (indoor cycling): one record per second,
 * one lap per workout step, session summary. Accepted by Strava, Garmin Connect, intervals.icu.
 */
object FitExporter {

    data class RideInfo(val startedAtMillis: Long, val ftp: Int)

    fun export(ride: RideInfo, samples: List<RideSample>): ByteArray {
        require(samples.isNotEmpty()) { "Ride has no data" }
        val start = time(ride.startedAtMillis)
        val end = samples.last().timestampMillis
        val messages = mutableListOf<Mesg>()

        messages += FileIdMesg().apply {
            type = File.ACTIVITY
            manufacturer = Manufacturer.DEVELOPMENT
            product = 1
            serialNumber = 1L
            timeCreated = start
            productName = "IndoorTrain"
        }
        messages += event(start, EventType.START)

        var distance = 0.0
        for (s in samples) {
            distance += (s.speedKmh ?: 0.0) / 3.6
            messages += RecordMesg().apply {
                timestamp = time(s.timestampMillis)
                s.heartRateBpm?.let { heartRate = it.toShort() }
                s.cadenceRpm?.let { cadence = it.roundToInt().coerceIn(0, 254).toShort() }
                s.powerWatts?.let { power = it.coerceAtLeast(0) }
                s.speedKmh?.let { speed = (it / 3.6).toFloat() }
                this.distance = distance.toFloat()
            }
        }

        val laps = samples.splitBy { it.stepIndex }
        laps.forEachIndexed { index, lap ->
            val stats = RideStats.summarize(lap, ride.ftp)
            messages += LapMesg().apply {
                messageIndex = index
                timestamp = time(lap.last().timestampMillis)
                startTime = time(lap.first().timestampMillis)
                totalElapsedTime = ((lap.last().timestampMillis - lap.first().timestampMillis) / 1000f + 1)
                totalTimerTime = lap.size.toFloat()
                totalDistance = stats.distanceMeters.toFloat()
                event = Event.LAP
                eventType = EventType.STOP
                lapTrigger = if (index == laps.lastIndex) LapTrigger.SESSION_END else LapTrigger.TIME
                sport = Sport.CYCLING
                subSport = SubSport.INDOOR_CYCLING
                stats.avgPower?.let { avgPower = it }
                stats.maxPower?.let { maxPower = it }
                stats.avgHeartRate?.let { avgHeartRate = it.toShort() }
                stats.maxHeartRate?.let { maxHeartRate = it.toShort() }
                stats.avgCadence?.let { avgCadence = it.toShort() }
            }
        }

        val total = RideStats.summarize(samples, ride.ftp)
        val elapsed = (end - ride.startedAtMillis) / 1000f
        messages += event(time(end), EventType.STOP_ALL)
        messages += SessionMesg().apply {
            messageIndex = 0
            timestamp = time(end)
            startTime = start
            totalElapsedTime = elapsed
            totalTimerTime = samples.size.toFloat()
            totalDistance = total.distanceMeters.toFloat()
            event = Event.SESSION
            eventType = EventType.STOP
            sport = Sport.CYCLING
            subSport = SubSport.INDOOR_CYCLING
            firstLapIndex = 0
            numLaps = laps.size
            thresholdPower = ride.ftp
            totalWork = total.energyKj * 1000L
            totalCalories = total.energyKj // ≈ kcal for cycling (≈ 24 % efficiency)
            total.avgPower?.let { avgPower = it }
            total.maxPower?.let { maxPower = it }
            total.normalizedPower?.let { normalizedPower = it }
            total.avgHeartRate?.let { avgHeartRate = it.toShort() }
            total.maxHeartRate?.let { maxHeartRate = it.toShort() }
            total.avgCadence?.let { avgCadence = it.toShort() }
            total.intensityFactor?.let { intensityFactor = it.toFloat() }
            total.tss?.let { trainingStressScore = it.toFloat() }
        }
        messages += ActivityMesg().apply {
            timestamp = time(end)
            totalTimerTime = samples.size.toFloat()
            numSessions = 1
            type = Activity.MANUAL
            event = Event.ACTIVITY
            eventType = EventType.STOP
            val offsetSec = TimeZone.getDefault().getOffset(end) / 1000
            localTimestamp = time(end).timestamp + offsetSec
        }

        val encoder = BufferEncoder(Fit.ProtocolVersion.V2_0)
        encoder.write(messages)
        return encoder.close()
    }

    private fun time(millis: Long) = DateTime(Date(millis))

    private fun event(at: DateTime, type: EventType) = EventMesg().apply {
        timestamp = at
        event = Event.TIMER
        eventType = type
        eventGroup = 0
    }

    /** Consecutive runs with the same key. */
    private fun <T, K> List<T>.splitBy(key: (T) -> K): List<List<T>> {
        val out = mutableListOf<MutableList<T>>()
        var last: Any? = Any()
        for (item in this) {
            val k = key(item)
            if (out.isEmpty() || k != last) out += mutableListOf<T>()
            out.last() += item
            last = k
        }
        return out
    }
}
