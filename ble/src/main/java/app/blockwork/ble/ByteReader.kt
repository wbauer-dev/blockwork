package app.blockwork.ble

/** Sequential little-endian reader for GATT payloads. */
internal class ByteReader(private val bytes: ByteArray) {
    var offset = 0
        private set

    val remaining: Int get() = bytes.size - offset

    fun uint8(): Int = bytes[offset++].toInt() and 0xFF

    fun uint16(): Int = uint8() or (uint8() shl 8)

    fun sint16(): Int = uint16().toShort().toInt()

    fun uint24(): Int = uint8() or (uint8() shl 8) or (uint8() shl 16)

    fun skip(n: Int) { offset += n }
}
