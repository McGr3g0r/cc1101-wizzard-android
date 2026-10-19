/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.protocol

/**
 * A software TNC for APRS: Bell-202 AFSK (1200 Hz mark / 2200 Hz space, 1200 baud)
 * → NRZI → HDLC (flag framing + bit de-stuffing) → AX.25 → APRS text.
 *
 * Two entry points match the two monitor modes:
 *   - [decodeFromPulses]  for AFSK: the firmware's `direct` capture gives a GDO0
 *     level pulse-train (the same `us` array OOK uses); each segment is a tone
 *     half-period, so we recover the mark/space symbol per bit window.
 *   - [decodeFromBytes]   for classic FSK/packet mode: the RX-FIFO bytes are run
 *     through the same HDLC/AX.25 deframer (best effort; APRS is really AFSK).
 *
 * Every frame is validated by its AX.25 FCS (CRC-16 X.25 residue 0xF0B8), so only
 * genuine packets are reported. Encoders ([ax25Frame], [hdlcBits], [afskPulses])
 * mirror the decoders exactly, which lets the whole chain be round-trip tested.
 */
object AprsCodec {

    const val BAUD = 1200
    const val MARK_HZ = 1200
    const val SPACE_HZ = 2200
    private const val BIT_US = 1_000_000.0 / BAUD          // 833.33 us
    private const val MARK_HALF = 1_000_000.0 / (2 * MARK_HZ)   // 416.7 us
    private const val SPACE_HALF = 1_000_000.0 / (2 * SPACE_HZ) // 227.3 us

    data class Packet(
        val source: String,
        val dest: String,
        val path: List<String>,
        val info: String,
        val frame: ByteArray,     // full AX.25 frame incl. FCS
    ) {
        /** "SRC>DEST[,DIGI...]:info" — the familiar TNC-2 monitor form. */
        fun tnc2(): String {
            val via = if (path.isEmpty()) "" else "," + path.joinToString(",")
            return "$source>$dest$via:$info"
        }
    }

    // =======================================================================
    // top-level decode
    // =======================================================================

    /** AFSK path: GDO0 pulse-train → symbols → bits → frames. */
    fun decodeFromPulses(us: IntArray): List<Packet> {
        if (us.size < 16) return emptyList()
        val symbols = demodAfsk(us)
        return framesFromSymbols(symbols)
    }

    /** Classic FSK path: raw FIFO bytes → bits → frames (best effort). */
    fun decodeFromBytes(bytes: ByteArray): List<Packet> {
        if (bytes.size < 4) return emptyList()
        // bits in transmission order (LSB first), tried both as-is and NRZI-decoded
        val raw = ArrayList<Boolean>(bytes.size * 8)
        for (b in bytes) for (i in 0..7) raw.add((b.toInt() shr i) and 1 == 1)
        val out = LinkedHashMap<String, Packet>()
        for (bits in listOf(nrziDecode(raw), raw)) {
            for (f in hdlcDeframe(bits)) parseAx25(f)?.let { out[it.tnc2()] = it }
        }
        return out.values.toList()
    }

    private fun framesFromSymbols(symbols: BooleanArray): List<Packet> {
        val bits = nrziDecode(symbols.toList())
        val out = LinkedHashMap<String, Packet>()
        for (f in hdlcDeframe(bits)) parseAx25(f)?.let { out[it.tnc2()] = it }
        return out.values.toList()
    }

    // =======================================================================
    // AFSK demodulation: us pulse-train -> one mark/space symbol per bit
    // =======================================================================

    /**
     * Classify the tone in each 1200-baud bit window as mark (true) or space
     * (false), by the average tone half-period over the window. Bit windows are
     * stepped at [BIT_US]; a simple leading-edge phase is used. (Real captures may
     * want a PLL for jitter — a follow-up once on-air signals are available.)
     */
    fun demodAfsk(us: IntArray): BooleanArray {
        // edge times (cumulative), and the half-period length of each segment
        val n = us.size
        val edge = DoubleArray(n + 1)
        for (i in 0 until n) edge[i + 1] = edge[i] + us[i]
        val total = edge[n]
        val nbits = (total / BIT_US).toInt()
        if (nbits <= 0) return BooleanArray(0)

        val mid = (MARK_HALF + SPACE_HALF) / 2.0     // decision boundary between tones
        val sym = BooleanArray(nbits)
        var seg = 0
        for (k in 0 until nbits) {
            val w0 = k * BIT_US
            val w1 = (k + 1) * BIT_US
            // average segment (half-period) length weighted by overlap with [w0,w1)
            var wsum = 0.0; var lsum = 0.0
            var s = seg
            while (s < n && edge[s] < w1) {
                val a = maxOf(edge[s], w0); val b = minOf(edge[s + 1], w1)
                val ov = b - a
                if (ov > 0) { wsum += ov; lsum += ov * us[s] }
                if (edge[s + 1] <= w0) seg = s + 1
                s++
            }
            val avgHalf = if (wsum > 0) lsum / wsum else SPACE_HALF
            sym[k] = avgHalf > mid     // longer half-period => mark (1200 Hz)
        }
        return sym
    }

    // =======================================================================
    // NRZI  (tone unchanged = 1, tone changed = 0)
    // =======================================================================

    fun nrziDecode(symbols: List<Boolean>): List<Boolean> {
        val out = ArrayList<Boolean>(symbols.size)
        var prev = true                  // assume leading mark
        for (s in symbols) { out.add(s == prev); prev = s }
        return out
    }

    fun nrziEncode(bits: List<Boolean>): List<Boolean> {
        val out = ArrayList<Boolean>(bits.size)
        var level = true
        for (b in bits) { if (!b) level = !level; out.add(level) }   // 0 => toggle
        return out
    }

    // =======================================================================
    // HDLC: flag framing (0x7E) + bit de-stuffing; assemble LSB-first bytes
    // =======================================================================

    private const val FLAG = 0x7E

    fun hdlcDeframe(bits: List<Boolean>): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        // locate flags as the literal pattern 0 1 1 1 1 1 1 0
        fun isFlagAt(p: Int): Boolean {
            if (p + 8 > bits.size) return false
            if (bits[p] || bits[p + 7]) return false
            for (i in 1..6) if (!bits[p + i]) return false
            return true
        }
        var i = 0
        while (i + 8 <= bits.size) {
            if (!isFlagAt(i)) { i++; continue }
            // consume consecutive flags
            var p = i
            while (isFlagAt(p)) p += 8
            // collect de-stuffed bits until the next flag
            val frameBits = ArrayList<Boolean>()
            var ones = 0
            var end = -1
            var q = p
            while (q < bits.size) {
                if (isFlagAt(q)) { end = q; break }
                val b = bits[q]
                if (ones == 5) {
                    // a stuffed 0 after five 1s is dropped; a 1 here (would be 0x7E) is a flag/err
                    ones = 0
                    if (b) { break }       // not a valid de-stuff → abort this frame
                    q++; continue
                }
                frameBits.add(b)
                ones = if (b) ones + 1 else 0
                q++
            }
            if (end >= 0 && frameBits.size >= 8) {
                frames.add(bitsToBytesLsb(frameBits))
                i = end
            } else i = p
        }
        return frames
    }

    fun hdlcBits(frame: ByteArray, leadFlags: Int = 8, tailFlags: Int = 3): List<Boolean> {
        val out = ArrayList<Boolean>()
        fun emitFlag() { for (i in 0..7) out.add((FLAG shr i) and 1 == 1) }
        repeat(leadFlags) { emitFlag() }
        var ones = 0
        for (b in frame) for (i in 0..7) {
            val bit = (b.toInt() shr i) and 1 == 1
            out.add(bit)
            if (bit) { ones++; if (ones == 5) { out.add(false); ones = 0 } } else ones = 0
        }
        repeat(tailFlags) { emitFlag() }
        return out
    }

    private fun bitsToBytesLsb(bits: List<Boolean>): ByteArray {
        val n = bits.size / 8
        val out = ByteArray(n)
        for (i in 0 until n) {
            var v = 0
            for (j in 0..7) if (bits[i * 8 + j]) v = v or (1 shl j)
            out[i] = v.toByte()
        }
        return out
    }

    // =======================================================================
    // AX.25 frame <-> fields
    // =======================================================================

    fun crc16x25(data: ByteArray, len: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (b in 0..7) crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
        }
        return crc and 0xFFFF
    }

    /** Parse a full AX.25 frame (incl. 2 FCS bytes); null if FCS or structure bad. */
    fun parseAx25(frame: ByteArray): Packet? {
        if (frame.size < 17) return null                        // 2 addr(14)+ctrl+pid+fcs(2) minimum-ish
        if (crc16x25(frame) != 0xF0B8) return null              // X.25 good-frame residue
        val body = frame.copyOfRange(0, frame.size - 2)         // drop FCS

        // addresses: 7 bytes each until the one whose SSID byte has bit0 set
        val addrs = ArrayList<String>()
        var i = 0
        while (i + 7 <= body.size) {
            val sb = StringBuilder()
            for (j in 0..5) {
                val c = (body[i + j].toInt() and 0xFF) ushr 1
                if (c != ' '.code) sb.append(c.toChar())
            }
            val ssidByte = body[i + 6].toInt() and 0xFF
            val ssid = (ssidByte ushr 1) and 0x0F
            if (ssid != 0) sb.append("-").append(ssid)
            addrs.add(sb.toString())
            i += 7
            if (ssidByte and 0x01 == 1) break                   // extension bit = last address
        }
        if (addrs.size < 2 || i + 2 > body.size) return null
        val dest = addrs[0]; val src = addrs[1]
        val path = if (addrs.size > 2) addrs.subList(2, addrs.size).toList() else emptyList()

        val control = body[i].toInt() and 0xFF
        val pid = if (i + 1 < body.size) body[i + 1].toInt() and 0xFF else -1
        // UI frame (0x03) + no-layer-3 PID (0xF0) carry APRS; be lenient otherwise
        val infoStart = if (control == 0x03 && pid == 0xF0) i + 2 else i + 2
        if (infoStart > body.size) return null
        val info = String(body.copyOfRange(infoStart, body.size), Charsets.ISO_8859_1)
            .filter { it >= ' ' || it == '\t' }
        return Packet(src, dest, path, info, frame)
    }

    /** Build a full AX.25 UI frame (with FCS) from APRS fields — used for tests. */
    fun ax25Frame(source: String, dest: String, path: List<String>, info: String): ByteArray {
        fun addr(call: String, last: Boolean): ByteArray {
            val parts = call.split("-")
            val cs = parts[0].uppercase().padEnd(6, ' ').take(6)
            val ssid = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val b = ByteArray(7)
            for (j in 0..5) b[j] = (cs[j].code shl 1).toByte()
            b[6] = (0x60 or (ssid shl 1) or (if (last) 1 else 0)).toByte()   // 0x60: reserved bits set
            return b
        }
        val out = ArrayList<Byte>()
        out.addAll(addr(dest, false).toList())
        val lastIsSrc = path.isEmpty()
        out.addAll(addr(source, lastIsSrc).toList())
        path.forEachIndexed { idx, d -> out.addAll(addr(d, idx == path.size - 1).toList()) }
        out.add(0x03.toByte())                 // UI
        out.add(0xF0.toByte())                 // PID: no layer 3
        for (c in info) out.add(c.code.toByte())
        val body = out.toByteArray()
        val fcs = crc16x25(body) xor 0xFFFF
        return body + byteArrayOf((fcs and 0xFF).toByte(), ((fcs ushr 8) and 0xFF).toByte())
    }

    // =======================================================================
    // AFSK modulation (bits/symbols -> us pulse-train) — for round-trip tests
    // =======================================================================

    /**
     * Symbols (mark=true) → GDO0 half-period pulse-train. Each bit is split into a
     * whole number of equal half-periods (mark ≈ 2, space ≈ 4) summing exactly to
     * one bit period, so the cumulative timing never drifts and the windowed
     * demodulator re-aligns on every bit. (A real transmitter is continuous-phase;
     * the demod only measures the average half-period per bit, so either works.)
     */
    fun afskPulses(symbols: List<Boolean>): IntArray {
        val out = ArrayList<Int>()
        var emitted = 0
        for ((k, s) in symbols.withIndex()) {
            val half = if (s) MARK_HALF else SPACE_HALF
            val cnt = Math.round(BIT_US / half).toInt().coerceAtLeast(1)
            for (j in 1..cnt) {
                val target = Math.round((k * BIT_US) + j * (BIT_US / cnt)).toInt()
                out.add(target - emitted); emitted = target
            }
        }
        return out.toIntArray()
    }

    /** Full APRS → AFSK pulse-train (flags + stuffing + NRZI + tones). */
    fun encodeToPulses(source: String, dest: String, path: List<String>, info: String): IntArray {
        val frame = ax25Frame(source, dest, path, info)
        val bits = hdlcBits(frame)
        val symbols = nrziEncode(bits)
        return afskPulses(symbols)
    }
}
