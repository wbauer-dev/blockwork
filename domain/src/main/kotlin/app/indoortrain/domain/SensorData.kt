package app.indoortrain.domain

/** One heart rate notification from the HR strap. */
data class HeartRateSample(
    val bpm: Int,
    /** RR intervals in milliseconds (may be empty). */
    val rrIntervalsMs: List<Int> = emptyList(),
    /** null = sensor does not report skin contact. */
    val sensorContact: Boolean? = null,
)

/** One data notification from the smart trainer. Fields are null when not reported. */
data class BikeSample(
    val powerWatts: Int? = null,
    val cadenceRpm: Double? = null,
    val speedKmh: Double? = null,
    val totalDistanceMeters: Int? = null,
)

enum class ConnectionState { Disconnected, Connecting, Connected }
