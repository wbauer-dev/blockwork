package app.blockwork.ble

/** Encoding/decoding for the FTMS Fitness Machine Control Point (0x2AD9). */
object FtmsControlPoint {
    const val OP_REQUEST_CONTROL = 0x00
    const val OP_RESET = 0x01
    const val OP_SET_TARGET_POWER = 0x05
    const val OP_START_RESUME = 0x07
    const val OP_STOP_PAUSE = 0x08
    const val OP_RESPONSE = 0x80

    fun requestControl(): ByteArray = byteArrayOf(OP_REQUEST_CONTROL.toByte())

    fun reset(): ByteArray = byteArrayOf(OP_RESET.toByte())

    fun startOrResume(): ByteArray = byteArrayOf(OP_START_RESUME.toByte())

    fun stop(): ByteArray = byteArrayOf(OP_STOP_PAUSE.toByte(), 0x01)

    fun pause(): ByteArray = byteArrayOf(OP_STOP_PAUSE.toByte(), 0x02)

    /** Target power in watts, SINT16 little-endian. */
    fun setTargetPower(watts: Int): ByteArray {
        val w = watts.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        return byteArrayOf(OP_SET_TARGET_POWER.toByte(), (w and 0xFF).toByte(), ((w shr 8) and 0xFF).toByte())
    }

    enum class Result(val code: Int) {
        Success(0x01),
        OpCodeNotSupported(0x02),
        InvalidParameter(0x03),
        OperationFailed(0x04),
        ControlNotPermitted(0x05),
        Unknown(-1);

        companion object {
            fun of(code: Int) = entries.firstOrNull { it.code == code } ?: Unknown
        }
    }

    data class Response(val requestOpCode: Int, val result: Result)

    /** Parses an indication on the control point; null if it is not a response. */
    fun parseResponse(value: ByteArray): Response? {
        if (value.size < 3 || (value[0].toInt() and 0xFF) != OP_RESPONSE) return null
        return Response(value[1].toInt() and 0xFF, Result.of(value[2].toInt() and 0xFF))
    }
}
