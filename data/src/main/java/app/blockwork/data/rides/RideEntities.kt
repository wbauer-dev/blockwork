package app.blockwork.data.rides

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val workoutId: String,
    val workoutName: String,
    val ftp: Int,
    /** false while the ride is running (or if the app died during it). */
    val finished: Boolean = false,
    /** true if the workout ran to its end, false if stopped early. */
    val completed: Boolean = false,
    val durationSec: Int? = null,
    val avgPower: Int? = null,
    val maxPower: Int? = null,
    val normalizedPower: Int? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val avgCadence: Int? = null,
    val energyKj: Int? = null,
    val intensityFactor: Double? = null,
    val tss: Int? = null,
    val distanceMeters: Int? = null,
    // Unused since the Strava upload was dropped (see branch feature/strava-upload).
    // Kept so existing v3 databases need no destructive migration.
    val stravaUploadId: Long? = null,
    val stravaActivityId: Long? = null,
    val stravaError: String? = null,
    /** Training plan session this ride belongs to (DB v4). */
    val plannedSessionId: String? = null,
    /** Ramp test ride (DB v5); excluded from the FTP check's "ended early" signal. */
    @ColumnInfo(defaultValue = "0") val isFtpTest: Boolean = false,
    /** intervals.icu upload (DB v7): [app.blockwork.data.intervals.IntervalsUploadState] name, null = never queued. */
    val intervalsState: String? = null,
    /** intervals.icu activity id, e.g. "i55751783", when known. */
    val intervalsActivityId: String? = null,
    /** Last upload problem or note, shown on the ride screen. */
    val intervalsMessage: String? = null,
)

@Entity(
    tableName = "ride_samples",
    primaryKeys = ["rideId", "elapsedSec"],
    foreignKeys = [
        ForeignKey(
            entity = RideEntity::class,
            parentColumns = ["id"],
            childColumns = ["rideId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rideId")],
)
data class RideSampleEntity(
    val rideId: Long,
    val elapsedSec: Int,
    val timestamp: Long,
    val power: Int?,
    val cadence: Double?,
    val speed: Double?,
    val heartRate: Int?,
    val targetPower: Int?,
    val stepIndex: Int,
    /** Intensity setting (1.0 = as planned); null in rides recorded before DB v5. */
    val intensity: Double? = null,
)

/** Beat-to-beat (RR) intervals from the heart-rate strap (DB v6), raw and unfiltered. */
@Entity(
    tableName = "ride_rr_intervals",
    primaryKeys = ["rideId", "idx"],
    foreignKeys = [
        ForeignKey(
            entity = RideEntity::class,
            parentColumns = ["id"],
            childColumns = ["rideId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rideId")],
)
data class RideRrIntervalEntity(
    val rideId: Long,
    /** Arrival order within the ride. */
    val idx: Int,
    /** Ride running seconds completed when the interval arrived. */
    val elapsedSec: Int,
    val rrMs: Int,
)
