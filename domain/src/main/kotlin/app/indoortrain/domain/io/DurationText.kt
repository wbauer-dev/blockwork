package app.indoortrain.domain.io

import kotlin.math.roundToInt

/** Text formats used by the workout editor. */
object DurationText {
    /** 270 -> "4:30", 3600 -> "60:00". */
    fun format(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    /** "4:30" -> 270, "45" -> 2700 (minutes), "0:30" -> 30, "1:05:00" -> 3900. Null if invalid or not positive. */
    fun parse(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.size > 3 || parts.any { it.isBlank() }) return null
        val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        if (numbers.size > 1 && numbers.drop(1).any { it >= 60 }) return null
        val seconds = when (numbers.size) {
            1 -> numbers[0] * 60
            2 -> numbers[0] * 60 + numbers[1]
            else -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
        }
        return seconds.takeIf { it > 0 }
    }

    /** FTP fraction -> whole percent text: 1.15 -> "115". */
    fun formatPercent(ftpFraction: Double): String = (ftpFraction * 100).roundToInt().toString()

    /** "115" or "115 %" -> 1.15; null if invalid. */
    fun parsePercent(text: String): Double? =
        text.trim().removeSuffix("%").trim().replace(',', '.').toDoubleOrNull()
            ?.takeIf { it >= 0 && it <= WorkoutValidator.MAX_FTP_FRACTION * 100 }
            ?.let { it / 100.0 }
}
