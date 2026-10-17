package com.wizzard.cc1101.protocol

import kotlin.math.abs

/**
 * Shared helpers for OOK/ASK pulse-train decoding. The firmware emits the raw
 * edge durations in microseconds, alternating HIGH, LOW, HIGH, LOW, ... starting
 * with the first HIGH after idle (see radio_pulses.ino `radio_pulses_grab`).
 */
object OokDecoding {

    data class Timing(val shortUs: Int, val longUs: Int, val threshold: Int)

    /** Estimate the base ("short") time and classification threshold from a train. */
    fun estimateTiming(us: IntArray): Timing {
        if (us.isEmpty()) return Timing(0, 0, 0)
        val sorted = us.filter { it in 50..20000 }.sorted()
        if (sorted.isEmpty()) return Timing(0, 0, 0)
        // short = low percentile (robust against the odd outlier), long ~ 3x short
        val shortUs = sorted[(sorted.size * 0.10).toInt().coerceIn(0, sorted.size - 1)]
        val longUs = (shortUs * 3)
        val threshold = shortUs * 2
        return Timing(shortUs, longUs, threshold)
    }

    /**
     * Decode a PWM-style train (EV1527 / PT2262 family) into a bit string.
     * bit 0 = HIGH short + LOW long ; bit 1 = HIGH long + LOW short.
     *
     * Real captures usually carry a sync/pilot pair (a short HIGH followed by a
     * very long LOW gap, ~24-31x the base time) and several repeats of the frame.
     * The firmware grabs from the first HIGH after idle, so the pilot often lands
     * at the very start — a naive decoder that stops at the first long LOW would
     * then return zero bits. Instead we split the train on those gaps, decode
     * each segment independently, and return the segment that best matches a
     * frame (closest to 24 bits), so a leading pilot or a trailing partial repeat
     * no longer defeats the decode.
     */
    fun decodePwm(us: IntArray, t: Timing): String {
        if (t.shortUs == 0 || us.size < 4) return ""
        val gap = t.shortUs * 6          // >= this LOW ends a frame (pilot / inter-frame)
        val segments = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { segments.add(cur.toString()); cur.setLength(0) } }

        var i = 0
        while (i + 1 < us.size) {
            val hi = us[i]
            val lo = us[i + 1]
            if (lo >= gap) {             // sync/pilot pair: ends the current frame
                flush(); i += 2; continue
            }
            val hiShort = hi < t.threshold
            val loShort = lo < t.threshold
            when {
                hiShort && !loShort -> cur.append('0')
                !hiShort && loShort -> cur.append('1')
                else -> { flush(); i += 2; continue } // noise/boundary: resync (keeps parity)
            }
            i += 2
        }
        flush()
        if (segments.isEmpty()) return ""
        // Prefer a plausible frame (20..26 bits, peaking at 24); otherwise the
        // longest segment we found.
        return segments.maxByOrNull { seg ->
            val len = seg.length
            if (len in 20..26) 1000 - abs(24 - len) else len.coerceAtMost(19)
        } ?: ""
    }

    /**
     * Encode a bit string back into a PWM pulse-train using the given short time.
     * bit 0 -> HIGH=te, LOW=3te ; bit 1 -> HIGH=3te, LOW=te.
     * A trailing sync gap (HIGH=te, LOW=31te) is appended so a receiver re-syncs.
     */
    fun encodePwm(bits: String, shortUs: Int, appendSync: Boolean = true): IntArray {
        val te = shortUs.coerceIn(50, 20000)
        val out = ArrayList<Int>(bits.length * 2 + 2)
        for (b in bits) {
            if (b == '0') { out.add(te); out.add(te * 3) }
            else          { out.add(te * 3); out.add(te) }
        }
        if (appendSync) { out.add(te); out.add(te * 31) }
        return out.toIntArray()
    }

    /**
     * One PWM frame candidate: its bits, whether it ended at a sync gap, and the
     * mean timing jitter of the pulses it consumed (fractional ticks off the
     * nearest integer multiple of Te — 0 = perfect, up to 0.5 = on the boundary).
     */
    data class PwmFrame(val bits: String, val gapTerminated: Boolean, val meanTickErr: Double)

    /** Fractional distance of a duration from the nearest whole Te tick, 0..0.5. */
    fun tickError(durUs: Int, teUs: Int): Double {
        if (teUs <= 0) return 0.5
        val q = durUs.toDouble() / teUs
        return kotlin.math.abs(q - kotlin.math.round(q)).coerceIn(0.0, 0.5)
    }

    /**
     * Like [decodePwm] but returns every frame found between sync gaps, each
     * flagged with whether it was actually terminated by a gap (the inter-frame
     * separator) rather than by running out of samples or hitting noise. A
     * fixed-length protocol (e.g. EV1527's 24 bits) should require BOTH the exact
     * bit count AND gapTerminated — that is how the original RP2040 firmware told
     * a real frame from the leading bits of a longer, different protocol.
     */
    fun decodePwmFrames(us: IntArray, t: Timing): List<PwmFrame> {
        if (t.shortUs == 0 || us.size < 2) return emptyList()
        val gap = t.shortUs * 6
        val frames = ArrayList<PwmFrame>()
        val cur = StringBuilder()
        var errSum = 0.0; var errN = 0
        fun flush(gapTerm: Boolean) {
            if (cur.isNotEmpty()) {
                frames.add(PwmFrame(cur.toString(), gapTerm, if (errN > 0) errSum / errN else 0.5))
                cur.setLength(0)
            }
            errSum = 0.0; errN = 0
        }
        var i = 0
        while (i + 1 < us.size) {
            val hi = us[i]; val lo = us[i + 1]
            if (lo >= gap) { flush(true); i += 2; continue }   // sync / inter-frame separator
            val hiShort = hi < t.threshold
            val loShort = lo < t.threshold
            when {
                hiShort && !loShort -> cur.append('0')
                !hiShort && loShort -> cur.append('1')
                else -> { flush(false); i += 2; continue }     // noise/boundary, not a clean gap
            }
            // accumulate timing jitter for this bit's two pulses (ideal 1 and 3 ticks)
            errSum += tickError(hi, t.shortUs) + tickError(lo, t.shortUs); errN += 2
            i += 2
        }
        flush(false)                                           // ran out of samples
        return frames
    }

    fun bitsToLong(bits: String): Long {
        var v = 0L
        for (c in bits) { v = (v shl 1) or (if (c == '1') 1L else 0L) }
        return v
    }

    fun longToBits(value: Long, width: Int): String {
        val sb = StringBuilder()
        for (i in width - 1 downTo 0) sb.append(if ((value shr i) and 1L == 1L) '1' else '0')
        return sb.toString()
    }

    fun approx(a: Int, b: Int, tolFrac: Double = 0.4): Boolean =
        b != 0 && abs(a - b).toDouble() / b <= tolFrac

    // ---- shared quantisation (ported from the v1 Protocol base) ----------

    /**
     * Dominant base ("short") pulse time, chosen like the v1 firmware: the most
     * frequent pulse width in the 160..500 us band. Falls back to the estimate.
     */
    fun dominantBase(us: IntArray): Int = dominantBaseBand(us, 160, 500, estimateTiming(us).shortUs)

    /** Dominant pulse width within [lo,hi]; fallback if none. */
    fun dominantBaseBand(us: IntArray, lo: Int, hi: Int, fallback: Int): Int {
        val counts = HashMap<Int, Int>()
        for (v in us) if (v in lo..hi) counts[v] = (counts[v] ?: 0) + 1
        return counts.maxByOrNull { it.value }?.key ?: fallback
    }

    /** Reverse the bit order of a byte (LSB<->MSB), like the v1 firmware's reverse8. */
    fun reverse8(b: Int): Int {
        var v = b and 0xFF
        var r = 0
        for (i in 0 until 8) { r = (r shl 1) or (v and 1); v = v shr 1 }
        return r and 0xFF
    }

    /** Quantise a microsecond duration to whole base-time ticks. */
    fun ticks(durationUs: Int, baseUs: Int): Int =
        if (baseUs <= 0) 0 else (durationUs + baseUs / 2) / baseUs

    // ---- Manchester codec (base decoder for OOK) -------------------------

    /**
     * Expand an OOK pulse-train (durations, alternating HIGH,LOW,... starting
     * HIGH) into a half-bit level string of '1'/'0', one char per base tick.
     */
    fun toLevelString(us: IntArray, baseUs: Int, maxLen: Int = 4096): String {
        val sb = StringBuilder()
        var high = true
        for (d in us) {
            var t = ticks(d, baseUs)
            if (t < 1) t = 1
            val c = if (high) '1' else '0'
            var k = 0
            while (k < t && sb.length < maxLen) { sb.append(c); k++ }
            high = !high
        }
        return sb.toString()
    }

    /**
     * Decode a half-bit level string as Manchester (IEEE 802.3 / G.E. Thomas:
     * 0 = HIGH→LOW "10", 1 = LOW→HIGH "01"). Returns bit string, stopping at the
     * first non-transition pair (frame boundary / noise).
     */
    fun decodeManchester(levels: String): String {
        val bits = StringBuilder()
        var i = 0
        while (i + 1 < levels.length) {
            val a = levels[i]; val b = levels[i + 1]
            when {
                a == '1' && b == '0' -> bits.append('0')
                a == '0' && b == '1' -> bits.append('1')
                else -> return bits.toString() // equal pair = boundary
            }
            i += 2
        }
        return bits.toString()
    }

    /** Encode bits back to a Manchester OOK pulse-train (merged equal levels). */
    fun encodeManchester(bits: String, baseUs: Int): IntArray {
        // build the half-bit level sequence
        val lv = StringBuilder()
        for (b in bits) if (b == '1') lv.append("01") else lv.append("10")
        if (lv.isEmpty()) return IntArray(0)
        // run-length encode into pulse durations; firmware starts HIGH, so the
        // first emitted pulse must be a HIGH one — prepend a 0-length guard only
        // if the sequence starts LOW by inverting is avoided: emit as-is.
        val out = ArrayList<Int>()
        var i = 0
        while (i < lv.length) {
            val c = lv[i]
            var run = 0
            while (i < lv.length && lv[i] == c) { run++; i++ }
            out.add(run * baseUs)
        }
        return out.toIntArray()
    }

    /** Pack a bit string (MSB-first) into bytes. */
    fun bitsToBytes(bits: String): ByteArray {
        val n = (bits.length + 7) / 8
        val out = ByteArray(n)
        for (i in bits.indices) if (bits[i] == '1') out[i / 8] = (out[i / 8].toInt() or (0x80 shr (i % 8))).toByte()
        return out
    }
}
