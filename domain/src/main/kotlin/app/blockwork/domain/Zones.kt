package app.blockwork.domain

/** Coggan power zones, bounds as FTP fraction (upper bound inclusive). */
enum class PowerZone(val number: Int, val label: String, val upperFtp: Double) {
    Z1(1, "Active Recovery", 0.55),
    Z2(2, "Endurance", 0.75),
    Z3(3, "Tempo", 0.90),
    Z4(4, "Threshold", 1.05),
    Z5(5, "VO2max", 1.20),
    Z6(6, "Anaerobic", Double.POSITIVE_INFINITY);

    companion object {
        fun forWatts(watts: Int, ftp: Int): PowerZone = forFtpFraction(watts.toDouble() / ftp)

        fun forFtpFraction(fraction: Double): PowerZone = entries.first { fraction <= it.upperFtp }
    }
}
