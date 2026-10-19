/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.protocol

import com.wizzard.cc1101.cli.CapturedPacket
import com.wizzard.cc1101.cli.TxFrame

/**
 * Receive-oriented OOK weather-sensor protocols (Nexus, Acurite 609TXC,
 * LaCrosse TX141TH-Bv2, Oregon Scientific v2.1). They decode a captured sensor
 * burst into editable fields — id / channel / battery / temperature / humidity —
 * so the Protocol tab can show the reading and, after editing, re-transmit a
 * spoofed frame (useful for exercising a receiver/display).
 *
 * Layouts follow the rtl_433 documentation; every analyzer validates its own
 * signature (constant nibble, checksum or CRC) before claiming a frame, and the
 * encode path recomputes that checksum so an edited frame stays valid. Timings
 * are the documented defaults and are exposed as the Te field for tuning against
 * a real capture.
 */

// ===========================================================================
// shared helpers
// ===========================================================================

/** Pulse-position OOK: a fixed HIGH pulse, then a LOW whose length is the bit. */
internal fun ppmEncode(
    bits: String, highUs: Int, zeroLowUs: Int, oneLowUs: Int, syncLowUs: Int,
): IntArray {
    val out = ArrayList<Int>(bits.length * 2 + 4)
    out.add(highUs); out.add(syncLowUs)                 // leading sync
    for (b in bits) { out.add(highUs); out.add(if (b == '1') oneLowUs else zeroLowUs) }
    out.add(highUs); out.add(syncLowUs)                 // trailing sync / repeat gap
    return out.toIntArray()
}

/**
 * Split a PPM train into frames on the sync gaps and decode each: classify every
 * LOW as 0/1, and a LOW well above the one-gap ends the current frame. Returns one
 * bit-string per frame, so a capture that starts mid-burst or holds several repeats
 * still yields a complete frame (the caller picks the valid one).
 */
internal fun ppmFrames(us: IntArray, zeroLowUs: Int, oneLowUs: Int): List<String> {
    val boundary = oneLowUs + (oneLowUs - zeroLowUs)    // clearly longer than a one-bit
    val frames = ArrayList<String>()
    val sb = StringBuilder()
    var i = 0
    while (i + 1 < us.size) {
        val lo = us[i + 1]
        if (lo > boundary) {                            // sync / inter-frame gap
            if (sb.isNotEmpty()) { frames.add(sb.toString()); sb.setLength(0) }
            i += 2; continue
        }
        sb.append(if (kotlin.math.abs(lo - oneLowUs) < kotlin.math.abs(lo - zeroLowUs)) '1' else '0')
        i += 2
    }
    if (sb.isNotEmpty()) frames.add(sb.toString())
    return frames
}

/** Sign-extend an n-bit value. */
internal fun signExtend(v: Int, bits: Int): Int {
    val m = 1 shl (bits - 1)
    return (v xor m) - m
}

internal fun tempRaw(s: String?): Int =
    Math.round((s?.toDoubleOrNull() ?: 0.0) * 10.0).toInt()

internal fun tempStr(rawTenths: Int): String = "%.1f".format(rawTenths / 10.0)

/** rtl_433 lfsr_digest8_reflect — used by LaCrosse TX141TH-Bv2. */
internal fun lfsrDigest8Reflect(bytes: IntArray, n: Int, gen: Int, key0: Int): Int {
    var sum = 0; var key = key0 and 0xFF
    for (k in n - 1 downTo 0) {
        val data = bytes[k] and 0xFF
        for (bit in 0..7) {
            if ((data shr bit) and 1 == 1) sum = sum xor key
            key = if (key and 0x80 != 0) ((key shl 1) xor gen) and 0xFF else (key shl 1) and 0xFF
        }
    }
    return sum and 0xFF
}

/** Common editable field set for a temp/humidity sensor. */
private fun sensorFields(
    id: Long, idWidth: Int, channel: Int, battery: Int, tempTenths: Int, humidity: Int,
    teUs: Int, extra: List<ProtoField> = emptyList(), withChannel: Boolean = true,
): List<ProtoField> = buildList {
    add(ProtoField("id", "Sensor id", "0x%0${idWidth}X".format(id), FieldType.HEX))
    addAll(extra)
    if (withChannel) add(ProtoField("channel", "Channel (1..3)", channel.toString(), FieldType.TICKER, min = 1, max = 3))
    add(ProtoField("battery", "Battery OK (1/0)", battery.toString(), FieldType.TICKER, min = 0, max = 1))
    add(ProtoField("temp", "Temperature [°C]", tempStr(tempTenths), FieldType.DEC))
    add(ProtoField("humidity", "Humidity [%]", humidity.toString(), FieldType.TICKER, min = 0, max = 100))
    add(ProtoField("te", "Base time Te [us]", teUs.toString(), FieldType.DEC))
}

private fun parseLong(s: String): Long? {
    val t = s.trim()
    return try {
        if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2).toLong(16) else t.toLong()
    } catch (e: Exception) { null }
}

private fun fi(f: List<ProtoField>, k: String): Long? =
    f.firstOrNull { it.key == k }?.value?.let { parseLong(it) }
private fun fd(f: List<ProtoField>, k: String): Int? =
    f.firstOrNull { it.key == k }?.value?.trim()?.toIntOrNull()
private fun ftemp(f: List<ProtoField>, k: String): Int =
    tempRaw(f.firstOrNull { it.key == k }?.value)

// ===========================================================================
// Nexus  (36-bit PPM; id,bat,chan,temp12,0xF,hum) — also Sencor/Digoo clones
// ===========================================================================

class NexusAnalyzer : ProtocolAnalyzer {
    override val id = "nexus"
    override val displayName = "Nexus (weather, 36-bit OOK)"

    override fun analyze(packet: CapturedPacket): AnalysisResult? {
        if (packet !is CapturedPacket.Ook) return null
        // scan every repeat in the burst; take the first that is exactly 36 bits with
        // the 0xF constant nibble and a sane humidity, so a mid-frame start or a noisy
        // repeat (common in a real capture) doesn't defeat detection.
        val us = packet.us
        val frames = ppmFrames(us, ZERO, ONE) +
            (if (us.size > 1) ppmFrames(us.copyOfRange(1, us.size), ZERO, ONE) else emptyList())
        val b = frames.firstOrNull {
            it.length == 36 && it.substring(24, 28) == "1111" &&
                OokDecoding.bitsToLong(it.substring(28, 36)).toInt() <= 100
        } ?: return null
        val sid = OokDecoding.bitsToLong(b.substring(0, 8))
        val battery = b[8] - '0'
        val channel = OokDecoding.bitsToLong(b.substring(10, 12)).toInt() + 1
        val tempTenths = signExtend(OokDecoding.bitsToLong(b.substring(12, 24)).toInt(), 12)
        val humidity = OokDecoding.bitsToLong(b.substring(28, 36)).toInt()

        // strong signature already validated (0xF nibble + exactly 36 PPM bits + hum<=100)
        val score = Scoring.score(0.85f, 0.9)
        return AnalysisResult(
            id, "Nexus (weather 36-bit)", score,
            "id=0x%02X ch%d %s°C %d%% bat=%d".format(sid, channel, tempStr(tempTenths), humidity, battery),
            sensorFields(sid, 2, channel, battery, tempTenths, humidity, HIGH_ZERO_ONE_TE),
            canTransmit = true,
        ) { nexusEncode(it) }
    }

    companion object { const val ZERO = 1000; const val ONE = 2000; const val HIGH_ZERO_ONE_TE = 500 }
}

internal fun nexusEncode(f: List<ProtoField>): TxFrame? {
    val sid = fi(f, "id") ?: return null
    val ch = ((fd(f, "channel") ?: 1).coerceIn(1, 3)) - 1
    val bat = (fd(f, "battery") ?: 1).coerceIn(0, 1)
    val temp = ftemp(f, "temp") and 0xFFF
    val hum = (fd(f, "humidity") ?: 0).coerceIn(0, 100)
    val bits = OokDecoding.longToBits(sid and 0xFF, 8) +
        bat.toString() + "0" + OokDecoding.longToBits(ch.toLong(), 2) +
        OokDecoding.longToBits(temp.toLong(), 12) + "1111" +
        OokDecoding.longToBits(hum.toLong(), 8)
    return TxFrame.OokPulses(ppmEncode(bits, NexusAnalyzer.HIGH_ZERO_ONE_TE, NexusAnalyzer.ZERO, NexusAnalyzer.ONE, 4000))
}

// ===========================================================================
// Acurite 609TXC (40-bit PWM; id,status,temp12,hum8,sum8)
// ===========================================================================

class Acurite609Analyzer : ProtocolAnalyzer {
    override val id = "acurite609"
    override val displayName = "Acurite 609TXC (weather, 40-bit OOK)"

    override fun analyze(packet: CapturedPacket): AnalysisResult? {
        if (packet !is CapturedPacket.Ook) return null
        val t = OokDecoding.estimateTiming(packet.us)
        if (t.shortUs == 0) return null
        val frame = OokDecoding.decodePwmFrames(packet.us, t)
            .firstOrNull { it.bits.length in 40..41 } ?: return null
        val bytes = fiveBytes(frame.bits) ?: return null
        val sum = (bytes[0] + bytes[1] + bytes[2] + bytes[3]) and 0xFF
        if (sum != bytes[4]) return null                         // checksum signature

        val sid = bytes[0].toLong()
        val battery = if (bytes[1] and 0x80 != 0) 0 else 1       // bit7 set = low
        val tempTenths = signExtend(((bytes[1] and 0x0F) shl 8) or bytes[2], 12)
        val humidity = bytes[3]
        if (humidity > 100) return null

        val score = Scoring.score(0.82f, Scoring.quality(frame.meanTickErr, frame.gapTerminated, frame.bits.length >= 40))
        return AnalysisResult(
            id, "Acurite 609TXC", score,
            "id=0x%02X %s°C %d%% bat=%d".format(sid, tempStr(tempTenths), humidity, battery),
            sensorFields(sid, 2, 1, battery, tempTenths, humidity, 500, withChannel = false),
            canTransmit = true,
        ) { acurite609Encode(it) }
    }
}

internal fun acurite609Encode(f: List<ProtoField>): TxFrame? {
    val sid = (fi(f, "id") ?: return null).toInt() and 0xFF
    val bat = (fd(f, "battery") ?: 1).coerceIn(0, 1)
    val temp = ftemp(f, "temp") and 0xFFF
    val hum = (fd(f, "humidity") ?: 0).coerceIn(0, 100)
    val te = fd(f, "te") ?: 500
    val b1 = ((if (bat == 0) 0x80 else 0) or ((temp shr 8) and 0x0F))
    val b2 = temp and 0xFF
    val bytes = intArrayOf(sid, b1, b2, hum, 0)
    bytes[4] = (bytes[0] + bytes[1] + bytes[2] + bytes[3]) and 0xFF
    return TxFrame.OokPulses(OokDecoding.encodePwm(bytesToBits(bytes), te))
}

// ===========================================================================
// LaCrosse TX141TH-Bv2 (40-bit PWM; id,flags,temp12,hum8,crc8 via LFSR)
// ===========================================================================

class LaCrosseTx141Analyzer : ProtocolAnalyzer {
    override val id = "lacrosse_tx141"
    override val displayName = "LaCrosse TX141TH (weather, 40-bit OOK)"

    override fun analyze(packet: CapturedPacket): AnalysisResult? {
        if (packet !is CapturedPacket.Ook) return null
        val t = OokDecoding.estimateTiming(packet.us)
        if (t.shortUs == 0) return null
        val frame = OokDecoding.decodePwmFrames(packet.us, t)
            .firstOrNull { it.bits.length in 40..41 } ?: return null
        val b = frame.bits.take(40)
        // layout: id(8) flags(4) temp(12, raw-500 = 0.1°C) humidity(8) crc(8, LFSR over first 4 bytes)
        val bytes4 = IntArray(4) { OokDecoding.bitsToLong(b.substring(it * 8, it * 8 + 8)).toInt() }
        val crc = OokDecoding.bitsToLong(b.substring(32, 40)).toInt()
        if (lfsrDigest8Reflect(bytes4, 4, 0x31, 0xF) != crc) return null      // CRC signature

        val sid = OokDecoding.bitsToLong(b.substring(0, 8))
        val flags = OokDecoding.bitsToLong(b.substring(8, 12)).toInt()
        val battery = if (flags and 0x8 != 0) 0 else 1
        val channel = (flags and 0x3) + 1
        val tempTenths = OokDecoding.bitsToLong(b.substring(12, 24)).toInt() - 500
        val hum = OokDecoding.bitsToLong(b.substring(24, 32)).toInt()
        if (hum > 100) return null

        val score = Scoring.score(0.85f, Scoring.quality(frame.meanTickErr, frame.gapTerminated, frame.bits.length >= 40))
        return AnalysisResult(
            id, "LaCrosse TX141TH-Bv2", score,
            "id=0x%02X ch%d %s°C %d%% bat=%d".format(sid, channel, tempStr(tempTenths), hum, battery),
            sensorFields(sid, 2, channel, battery, tempTenths, hum, 550),
            canTransmit = true,
        ) { lacrosseEncode(it) }
    }
}

internal fun lacrosseEncode(f: List<ProtoField>): TxFrame? {
    val sid = (fi(f, "id") ?: return null).toInt() and 0xFF
    val ch = ((fd(f, "channel") ?: 1).coerceIn(1, 3)) - 1
    val bat = (fd(f, "battery") ?: 1).coerceIn(0, 1)
    val raw = (ftemp(f, "temp") + 500).coerceIn(0, 4095)      // raw-500 = 0.1°C (−50°C offset)
    val hum = (fd(f, "humidity") ?: 0).coerceIn(0, 100)
    val te = fd(f, "te") ?: 550
    val flags = (if (bat == 0) 0x8 else 0) or ch
    val first32 = OokDecoding.longToBits(sid.toLong(), 8) +
        OokDecoding.longToBits(flags.toLong(), 4) +
        OokDecoding.longToBits(raw.toLong(), 12) +
        OokDecoding.longToBits(hum.toLong(), 8)
    val bytes4 = IntArray(4) { OokDecoding.bitsToLong(first32.substring(it * 8, it * 8 + 8)).toInt() }
    val crc = lfsrDigest8Reflect(bytes4, 4, 0x31, 0xF)
    val bits = first32 + OokDecoding.longToBits(crc.toLong(), 8)
    return TxFrame.OokPulses(OokDecoding.encodePwm(bits, te))
}

// ===========================================================================
// Oregon Scientific v2.1 (Manchester; model,channel,rolling,flags,BCD temp/hum)
// ===========================================================================

class OregonV21Analyzer : ProtocolAnalyzer {
    override val id = "oregon_v21"
    override val displayName = "Oregon Scientific v2.1 (weather, Manchester)"

    override fun analyze(packet: CapturedPacket): AnalysisResult? {
        if (packet !is CapturedPacket.Ook) return null
        val te = OokDecoding.dominantBaseBand(packet.us, 300, 800, 488)
        if (te <= 0) return null
        val levels = OokDecoding.toLevelString(packet.us, te)
        val inv = buildString { for (c in levels) append(if (c == '1') '0' else '1') }
        // Manchester has a polarity and a half-bit phase ambiguity, so try both
        // inversions and both phase offsets and accept the first frame whose sync
        // nibble is present and whose checksum validates.
        var nib: IntArray? = null
        outer@ for (lv in listOf(levels, inv)) for (off in 0..1) {
            if (off >= lv.length) continue
            val raw = OokDecoding.decodeManchester(lv.substring(off))
            val at = raw.indexOf(SYNC)
            if (at < 0 || raw.length < at + SYNC.length + NIBBLES * 4) continue
            val payload = raw.substring(at + SYNC.length)
            val n = IntArray(NIBBLES) { lsbNibble(payload, it * 4) }
            val sum = (0 until NIBBLES - 2).sumOf { n[it] } and 0xFF
            val got = n[NIBBLES - 2] or (n[NIBBLES - 1] shl 4)
            if (sum == got) { nib = n; break@outer }
        }
        val nb = nib ?: return null

        val model = (nb[0]) or (nb[1] shl 4) or (nb[2] shl 8) or (nb[3] shl 12)
        val channel = nb[4].coerceIn(0, 7).let { if (it in 1..3) it else 1 }
        val rolling = nb[5] or (nb[6] shl 4)
        val battery = if (nb[7] and 0x4 != 0) 0 else 1
        val tempTenths = (nb[8] * 1 + nb[9] * 10 + nb[10] * 100) * (if (nb[11] != 0) -1 else 1)
        val humidity = nb[12] + nb[13] * 10
        if (humidity > 100) return null

        val score = Scoring.score(0.80f, 0.85)
        return AnalysisResult(
            id, "Oregon Scientific v2.1", score,
            "model=0x%04X ch%d %s°C %d%% bat=%d".format(model, channel, tempStr(tempTenths), humidity, battery),
            sensorFields(
                model.toLong(), 4, channel, battery, tempTenths, humidity, te,
                extra = listOf(ProtoField("rolling", "Rolling id (8-bit)", "0x%02X".format(rolling), FieldType.HEX)),
            ),
            canTransmit = true,
        ) { oregonEncode(it) }
    }

    companion object {
        const val SYNC = "1010"            // sync nibble 0xA (after a run of preamble 1s)
        const val NIBBLES = 16             // model(4) ch(1) roll(2) flags(1) temp(3) sign(1) hum(2) sum(2)
    }
}

internal fun oregonEncode(f: List<ProtoField>): TxFrame? {
    val model = (fi(f, "model") ?: fi(f, "id") ?: return null).toInt() and 0xFFFF
    val ch = (fd(f, "channel") ?: 1).coerceIn(1, 3)
    val rolling = (fi(f, "rolling") ?: 0L).toInt() and 0xFF
    val bat = (fd(f, "battery") ?: 1).coerceIn(0, 1)
    val tenths = ftemp(f, "temp")
    val neg = tenths < 0; val mag = kotlin.math.abs(tenths)
    val hum = (fd(f, "humidity") ?: 0).coerceIn(0, 100)
    val te = fd(f, "te") ?: 488

    val nib = IntArray(OregonV21Analyzer.NIBBLES)
    nib[0] = model and 0xF; nib[1] = (model shr 4) and 0xF; nib[2] = (model shr 8) and 0xF; nib[3] = (model shr 12) and 0xF
    nib[4] = ch
    nib[5] = rolling and 0xF; nib[6] = (rolling shr 4) and 0xF
    nib[7] = if (bat == 0) 0x4 else 0x0
    nib[8] = mag % 10; nib[9] = (mag / 10) % 10; nib[10] = (mag / 100) % 10
    nib[11] = if (neg) 0x8 else 0x0
    nib[12] = hum % 10; nib[13] = (hum / 10) % 10
    val sum = (0 until OregonV21Analyzer.NIBBLES - 2).sumOf { nib[it] } and 0xFF
    nib[14] = sum and 0xF; nib[15] = (sum shr 4) and 0xF

    val sb = StringBuilder("1".repeat(16))                 // preamble: run of 1s
    sb.append(OregonV21Analyzer.SYNC)                       // sync nibble
    for (n in nib) sb.append(lsbNibbleBits(n))             // payload, LSB-first nibbles
    return TxFrame.OokPulses(OokDecoding.encodeManchester(sb.toString(), te))
}

// ---- small bit helpers shared by the weather analyzers --------------------

/** Parse a 40-bit string into 5 bytes, or null if too short. */
private fun fiveBytes(bits: String): IntArray? {
    if (bits.length < 40) return null
    return IntArray(5) { OokDecoding.bitsToLong(bits.substring(it * 8, it * 8 + 8)).toInt() and 0xFF }
}

private fun bytesToBits(bytes: IntArray): String =
    bytes.joinToString("") { OokDecoding.longToBits(it.toLong() and 0xFF, 8) }

/** Read 4 bits at [pos] as an LSB-first nibble. */
private fun lsbNibble(bits: String, pos: Int): Int {
    var v = 0
    for (i in 0..3) if (pos + i < bits.length && bits[pos + i] == '1') v = v or (1 shl i)
    return v
}

/** Emit a nibble as 4 LSB-first bits. */
private fun lsbNibbleBits(n: Int): String {
    val sb = StringBuilder(4)
    for (i in 0..3) sb.append(if ((n shr i) and 1 == 1) '1' else '0')
    return sb.toString()
}
