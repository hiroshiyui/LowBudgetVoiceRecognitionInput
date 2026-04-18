package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

import java.nio.ShortBuffer

/**
 * Half-precision (IEEE 754 binary16) ⇄ single-precision conversion.
 *
 * Android's `minSdk = 31` (Java 11) predates `Float.float16ToFloat` (Java 20+),
 * so we implement it directly. The routine covers zeros, subnormals, normals,
 * and inf/NaN — enough for ORT output tensors.
 */
object Fp16 {

    fun toFloat(h: Short): Float {
        val bits = h.toInt() and 0xFFFF
        val sign = (bits and 0x8000) shl 16
        val exp = (bits and 0x7C00) shr 10
        val mant = bits and 0x03FF
        return Float.fromBits(
            when {
                exp == 0x1F -> sign or 0x7F800000 or (mant shl 13)
                exp != 0 -> sign or ((exp + 112) shl 23) or (mant shl 13)
                mant == 0 -> sign
                else -> {
                    var e = 113
                    var m = mant
                    while ((m and 0x0400) == 0) { m = m shl 1; e-- }
                    m = m and 0x03FF
                    sign or (e shl 23) or (m shl 13)
                }
            }
        )
    }

    /** Argmax over an f16 tensor without materialising the full float32 array. */
    fun argmax(buf: ShortBuffer): Int {
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in 0 until buf.remaining()) {
            val v = toFloat(buf.get(buf.position() + i))
            if (v > bestVal) { bestVal = v; bestIdx = i }
        }
        return bestIdx
    }
}
