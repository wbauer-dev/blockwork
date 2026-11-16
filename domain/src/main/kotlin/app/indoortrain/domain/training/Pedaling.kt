package app.indoortrain.domain.training

import app.indoortrain.domain.BikeSample

/** Decides from trainer data whether the rider is pedaling (for auto start and auto pause). */
object Pedaling {
    /** Below this the flywheel is coasting down or standing still. */
    const val MIN_CADENCE_RPM = 20.0

    /** Fallback for trainers that report no cadence. */
    const val MIN_POWER_WATTS = 25

    /** Seconds without pedaling before auto pause kicks in. */
    const val AUTO_PAUSE_AFTER_SEC = 3

    /** true/false from cadence (or power when cadence is missing); null when the trainer reports neither. */
    fun detect(sample: BikeSample?): Boolean? {
        if (sample == null) return null
        sample.cadenceRpm?.let { return it >= MIN_CADENCE_RPM }
        sample.powerWatts?.let { return it >= MIN_POWER_WATTS }
        return null
    }
}
