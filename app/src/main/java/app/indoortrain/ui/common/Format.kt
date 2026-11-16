package app.indoortrain.ui.common

/** 75 -> "1:15", 3725 -> "1:02:05". */
fun formatDuration(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** 2700 -> "45 min", 5400 -> "1 h 30 min". */
fun formatMinutes(totalSec: Int): String {
    val min = (totalSec + 30) / 60
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

/** English noun for a count: (1, "ride") -> "ride", (3, "ride") -> "rides". */
fun noun(n: Int, word: String): String = if (n == 1) word else "${word}s"

/** (1, "ride") -> "1 ride", (3, "ride") -> "3 rides". */
fun plural(n: Int, word: String): String = "$n ${noun(n, word)}"
