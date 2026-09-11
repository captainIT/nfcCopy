package com.example.nfccopy.util

private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

fun ByteArray.toHex(separator: String = ""): String {
    val sb = StringBuilder(size * (2 + separator.length))
    for ((index, b) in this.withIndex()) {
        if (index > 0 && separator.isNotEmpty()) sb.append(separator)
        val v = b.toInt() and 0xFF
        sb.append(HEX_CHARS[v ushr 4])
        sb.append(HEX_CHARS[v and 0x0F])
    }
    return sb.toString()
}

/** Parse a hex string, ignoring spaces/colons. Returns null on malformed input. */
fun String.hexToBytesOrNull(): ByteArray? {
    val cleaned = this.filter { it != ' ' && it != ':' && it != '\n' && it != '\r' && it != '\t' }
    if (cleaned.length % 2 != 0) return null
    val out = ByteArray(cleaned.length / 2)
    var i = 0
    while (i < cleaned.length) {
        val hi = Character.digit(cleaned[i], 16)
        val lo = Character.digit(cleaned[i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i / 2] = ((hi shl 4) or lo).toByte()
        i += 2
    }
    return out
}
