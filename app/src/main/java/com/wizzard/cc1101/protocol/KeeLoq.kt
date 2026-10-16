package com.wizzard.cc1101.protocol

/**
 * Generic KeeLoq-family OOK/PWM codec, shared by HCS200 / HCS300 / Nice Flor-S.
 *
 * Framing (as used by the Microchip HCS encoders and the v1 firmware decoder):
 *   - a preamble of N short pulses (≈1 tick each),
 *   - an optional header pulse (a longer gap, headerLo..headerHi ticks),
 *   - then `nbits` PWM data bits, LSB-first within each byte:
 *         bit 1 = short,long  (ticks 1,2)
 *         bit 0 = long,short  (ticks 2,1)
 *
 * Only the framing/line-coding is generic here; each concrete protocol slices
 * the decoded bits into its own labelled fields (see the analyzers).
 */
object KeeLoqCodec {

    data class Framing(
        val baseLo: Int, val baseHi: Int, val baseFallback: Int,
        val preamble: Int,
        val headerLo: Int, val headerHi: Int,   // headerLo = 0 -> no header pulse
        val nbits: Int,
        val guardUs: Int,
    )

    /** Decode a capture into its on-air bit list (LSB-first order), or null. */
    fun decode(us: IntArray, f: Framing): IntArray? {
        if (us.size < f.preamble + f.nbits) return null
        val base = OokDecoding.dominantBaseBand(us, f.baseLo, f.baseHi, f.baseFallback)
        if (base <= 0) return null
        fun t(i: Int) = OokDecoding.ticks(us[i], base)

        var i = 0; var pre = 0
        while (i < us.size) {
            if (t(i) in 0..1) { pre++; if (pre == f.preamble) { i++; break } } else pre = 0
            i++
        }
        if (pre < f.preamble) return null
        if (f.headerLo > 0) {
            if (i >= us.size || t(i) !in f.headerLo..f.headerHi) return null
            i++
        }

        val bits = ArrayList<Int>(f.nbits)
        while (i + 1 < us.size && bits.size < f.nbits) {
            val p0 = t(i); val p1 = t(i + 1)
            // mid-frame p1 is always 1 or 2 ticks; p1 >= 3 means the trailing
            // inter-frame gap on the final bit (its length depends on Te).
            when {
                p0 == 2 && (p1 == 1 || p1 >= 3) -> bits.add(0)
                p0 == 1 && (p1 == 2 || p1 >= 3) -> bits.add(1)
                else -> return null
            }
            i += 2
        }
        if (bits.size == f.nbits - 1) bits.add(0)   // emulate a missing final bit
        if (bits.size < f.nbits) return null
        return IntArray(f.nbits) { bits[it] }
    }

    /** Encode a bit list back into a pulse-train (same framing). */
    fun encode(bits: IntArray, te: Int, f: Framing): IntArray {
        val out = ArrayList<Int>()
        repeat(f.preamble) { out.add(1 * te) }
        if (f.headerLo > 0) out.add(((f.headerLo + f.headerHi) / 2) * te)
        for (b in bits) {
            if (b == 1) { out.add(1 * te); out.add(2 * te) } else { out.add(2 * te); out.add(1 * te) }
        }
        if (out.isNotEmpty()) out[out.size - 1] = out[out.size - 1] + f.guardUs
        return out.toIntArray()
    }

    /** Pack a bit list (LSB-first within each byte) into bytes. */
    fun bytes(bits: IntArray): IntArray {
        val n = (bits.size + 7) / 8
        val out = IntArray(n)
        for (k in bits.indices) if (bits[k] == 1) out[k / 8] = out[k / 8] or (1 shl (k and 7))
        return out
    }

    /** Expand bytes (LSB-first) back into a bit list of [nbits]. */
    fun bitsFromBytes(bytes: IntArray, nbits: Int): IntArray =
        IntArray(nbits) { (bytes[it / 8] shr (it and 7)) and 1 }

    /** Read a little-endian field of [width] bits starting at bit [start]. */
    fun field(bits: IntArray, start: Int, width: Int): Long {
        var v = 0L
        for (b in 0 until width) {
            val idx = start + b
            if (idx < bits.size && bits[idx] == 1) v = v or (1L shl b)
        }
        return v
    }

    /** Write a little-endian field of [width] bits starting at [start]. */
    fun setField(bits: IntArray, start: Int, width: Int, value: Long) {
        for (b in 0 until width) {
            val idx = start + b
            if (idx < bits.size) bits[idx] = ((value shr b) and 1L).toInt()
        }
    }

    // ---- standard framings -------------------------------------------------

    /** HCS200 / HCS300: 23-pulse preamble, ~10-tick header, 66 PWM bits. */
    val HCS = Framing(baseLo = 250, baseHi = 600, baseFallback = 400,
        preamble = 23, headerLo = 9, headerHi = 11, nbits = 66, guardUs = 15600)

    /**
     * Nice Flor-S: KeeLoq-family, 52-bit frame. Preamble/header timing here is a
     * reasonable default; verify against a real capture and adjust if needed.
     */
    val NICE_FLORS = Framing(baseLo = 350, baseHi = 800, baseFallback = 500,
        preamble = 12, headerLo = 0, headerHi = 0, nbits = 52, guardUs = 15000)
}
