package com.wizzard.cc1101.protocol

import com.wizzard.cc1101.cli.CapturedPacket
import com.wizzard.cc1101.cli.MonitorParser
import com.wizzard.cc1101.cli.TxFrame

// ---------------------------------------------------------------------------
// EV1527 / PT2262 family (OOK, 24-bit PWM): 20-bit address + 4-bit data.
// A well-known fixed-code remote-control encoding.
// ---------------------------------------------------------------------------
class Ev1527Analyzer : ProtocolAnalyzer {
    override val id = "ev1527"
    override val displayName = "EV1527 / PT2262 (24-bit OOK)"

    override fun analyze(packet: CapturedPacket): AnalysisResult? {
        if (packet !is CapturedPacket.Ook) return null
        val t = OokDecoding.estimateTiming(packet.us)
        if (t.shortUs == 0) return null
        // EV1527 / PT2262 is a FIXED 24-bit frame. Take the longest clean PWM run
        // and require it to be ~24 bits. The firmware's "gap→frame→gap" grabber
        // trims the trailing sync gap, so we no longer require the frame to be
        // gap-terminated (that would reject a correctly-captured single frame).
        // Instead we reject when a clearly longer clean run exists (e.g. a 66-bit
        // KeeLoq/HCS frame) — the extra bits prove it isn't EV1527.
        val frames = OokDecoding.decodePwmFrames(packet.us, t)
        if (frames.any { it.bits.length >= 28 }) return null
        val frame = frames.filter { it.bits.length in 23..25 }.maxByOrNull { it.bits.length } ?: return null
        val use = frame.bits.take(24).padEnd(24, '0')
        val addrBits = use.take(20)
        val dataBits = use.substring(20, 24)
        val addr = OokDecoding.bitsToLong(addrBits)

        // dynamic confidence from how cleanly the pulses fit the EV1527 grid;
        // a present separator (repeats captured) nudges it up, exact 24 bits too.
        val score = Scoring.score(0.55f,
            Scoring.quality(frame.meanTickErr, separator = frame.gapTerminated, exactBits = frame.bits.length == 24))

        val fields = listOf(
            ProtoField("addr", "Address (20-bit code)", "0x%05X".format(addr), FieldType.HEX),
            ProtoField("data", "Data (4-bit)", dataBits, FieldType.BITS),
            ProtoField("te", "Base time Te [us]", t.shortUs.toString(), FieldType.DEC),
            ProtoField("bits", "Decoded bits", use, FieldType.BITS, editable = false),
        )

        return AnalysisResult(
            analyzerId = id,
            protocol = "EV1527/PT2262 (24-bit)",
            score = score,
            summary = "addr=0x%05X data=%s  (%d bits, Te≈%dus)".format(addr, dataBits, use.length, t.shortUs),
            fields = fields,
            canTransmit = true,
        ) { edited ->
            val a = edited.firstOrNull { it.key == "addr" }?.value?.let { parseLong(it) } ?: return@AnalysisResult null
            val d = edited.firstOrNull { it.key == "data" }?.value ?: dataBits
            val te = edited.firstOrNull { it.key == "te" }?.value?.toIntOrNull() ?: t.shortUs
            val outBits = OokDecoding.longToBits(a and 0xFFFFF, 20) + d.filter { it == '0' || it == '1' }.take(4).padEnd(4, '0')
            TxFrame.OokPulses(OokDecoding.encodePwm(outBits, te))
        }
    }
}

// ---------------------------------------------------------------------------
// Generic OOK fallback: no protocol assumed, just the raw pulse-train. Lets the
// user inspect timings, see a midpoint-threshold bit guess, edit the train and
// replay it verbatim.
// ---------------------------------------------------------------------------
class GenericOokAnalyzer : ProtocolAnalyzer {
    override val id = "generic_ook"
    override val displayName = "Generic OOK (raw pulses)"

    override fun analyze(packet: CapturedPacket): AnalysisResult? {
        if (packet !is CapturedPacket.Ook) return null
        if (packet.us.isEmpty()) return null
        val t = OokDecoding.estimateTiming(packet.us)
        val guess = packet.us.joinToString("") { if (it >= t.threshold) "1" else "0" }

        val fields = listOf(
            ProtoField("n", "Pulse count", packet.us.size.toString(), FieldType.DEC, editable = false),
            ProtoField("short", "Short [us]", t.shortUs.toString(), FieldType.DEC, editable = false),
            ProtoField("long", "Long [us]", t.longUs.toString(), FieldType.DEC, editable = false),
            ProtoField("levels", "Width>threshold bits", guess, FieldType.BITS, editable = false),
            ProtoField("us", "Pulse-train [us, CSV]", packet.us.joinToString(","), FieldType.TEXT),
        )

        return AnalysisResult(
            analyzerId = id,
            protocol = "Raw OOK pulse-train",
            score = 0.30f,
            summary = "${packet.us.size} pulses, short≈${t.shortUs}us, rssi=${packet.rssi}",
            fields = fields,
            canTransmit = true,
        ) { edited ->
            val csv = edited.firstOrNull { it.key == "us" }?.value ?: return@AnalysisResult null
            val arr = csv.split(Regex("[,\\s]+")).mapNotNull { it.trim().toIntOrNull() }
            if (arr.isEmpty()) null else TxFrame.OokPulses(arr.toIntArray())
        }
    }
}

// ---------------------------------------------------------------------------
// Generic FSK/MSK fallback: raw RX-FIFO bytes. Editable hex, retransmit in the
// current modulation.
// ---------------------------------------------------------------------------
class GenericFskAnalyzer : ProtocolAnalyzer {
    override val id = "generic_fsk"
    override val displayName = "Generic FSK (raw bytes)"

    override fun analyze(packet: CapturedPacket): AnalysisResult? {
        if (packet !is CapturedPacket.Fsk) return null
        val hex = MonitorParser.bytesToHex(packet.bytes)
        val fields = listOf(
            ProtoField("hex", "Payload (hex)", hex, FieldType.HEX),
            ProtoField("len", "Length [bytes]", packet.bytes.size.toString(), FieldType.DEC, editable = false),
            ProtoField("crc", "CRC", if (packet.crc) "ok" else "bad", FieldType.TEXT, editable = false),
            ProtoField("mod", "Modulation", packet.mod, FieldType.TEXT, editable = false),
        )
        return AnalysisResult(
            analyzerId = id,
            protocol = "Raw ${packet.mod.uppercase()} frame",
            score = if (packet.crc) 0.50f else 0.35f,
            summary = "${packet.bytes.size} bytes ${packet.mod} crc=${if (packet.crc) 1 else 0} rssi=${packet.rssi}",
            fields = fields,
            canTransmit = true,
        ) { edited ->
            val hx = edited.firstOrNull { it.key == "hex" }?.value?.filter { !it.isWhitespace() } ?: return@AnalysisResult null
            if (hx.length < 2 || hx.length % 2 != 0) null else TxFrame.FskHex(hx.lowercase())
        }
    }
}

// ---------------------------------------------------------------------------
// Heuristic rolling-code splitter (FSK): demonstrates editable codes + counters.
// Treats the trailing 2 bytes as a little-endian counter and the rest as a
// fixed ID. This is a structural guess, not a specific protocol.
// ---------------------------------------------------------------------------
class RollingCodeFskAnalyzer : ProtocolAnalyzer {
    override val id = "rolling_fsk"
    override val displayName = "Rolling-code heuristic (FSK)"

    override fun analyze(packet: CapturedPacket): AnalysisResult? {
        if (packet !is CapturedPacket.Fsk) return null
        val b = packet.bytes
        if (b.size < 4) return null

        val idBytes = b.copyOfRange(0, b.size - 2)
        val counter = ((b[b.size - 1].toInt() and 0xFF) shl 8) or (b[b.size - 2].toInt() and 0xFF)
        val idHex = MonitorParser.bytesToHex(idBytes)

        val fields = listOf(
            ProtoField("id", "Fixed ID (hex)", idHex, FieldType.HEX),
            ProtoField("counter", "Rolling counter (LE16)", counter.toString(), FieldType.DEC),
            ProtoField("len", "Length [bytes]", b.size.toString(), FieldType.DEC, editable = false),
        )
        return AnalysisResult(
            analyzerId = id,
            protocol = "Rolling-code (heuristic split)",
            score = if (packet.bytes.size in 4..16) 0.45f else 0.30f,
            summary = "id=$idHex counter=$counter (trailing LE16) — structural guess",
            fields = fields,
            canTransmit = true,
        ) { edited ->
            val idv = edited.firstOrNull { it.key == "id" }?.value?.filter { !it.isWhitespace() } ?: return@AnalysisResult null
            val cnt = edited.firstOrNull { it.key == "counter" }?.value?.toIntOrNull() ?: return@AnalysisResult null
            val idb = MonitorParser.hexToBytes(idv)
            val out = ByteArray(idb.size + 2)
            System.arraycopy(idb, 0, out, 0, idb.size)
            out[idb.size] = (cnt and 0xFF).toByte()         // low byte first (LE)
            out[idb.size + 1] = ((cnt shr 8) and 0xFF).toByte()
            TxFrame.FskHex(MonitorParser.bytesToHex(out))
        }
    }
}

// ---------------------------------------------------------------------------
// PT2240 / PT2262-family (OOK, 24-bit PWM) with a short-pulse + ~7-tick gap
// preamble. Ported from the v1 firmware decoder.
// ---------------------------------------------------------------------------
class Pt2240Analyzer : ProtocolAnalyzer {
    override val id = "pt2240"
    override val displayName = "PT2240 (24-bit OOK)"

    override fun analyze(packet: CapturedPacket): AnalysisResult? {
        if (packet !is CapturedPacket.Ook) return null
        val us = packet.us
        if (us.size < 2 + 2 * 24) return null
        val base = OokDecoding.dominantBase(us)
        if (base <= 0) return null
        fun t(i: Int) = OokDecoding.ticks(us[i], base)

        // find preamble: one short HIGH followed by a ~7-8 tick gap
        var i = 0
        var found = false
        while (i < us.size - 1) {
            if (t(i) == 1 && t(i + 1) in 7..8) { i += 2; found = true; break }
            i++
        }
        if (!found) return null

        val bits = StringBuilder()
        var errSum = 0.0; var errN = 0; var clean = true
        while (i + 1 < us.size && bits.length < 24) {
            val p0 = t(i); val p1 = t(i + 1)
            when {
                p0 == 3 && p1 == 1 -> bits.append('1')
                p0 == 1 && p1 == 3 -> bits.append('0')
                p0 == 3 -> { bits.append('1'); clean = false }   // tolerate truncated last bit
                p0 == 1 -> { bits.append('0'); clean = false }
                else -> return null
            }
            errSum += OokDecoding.tickError(us[i], base) + OokDecoding.tickError(us[i + 1], base); errN += 2
            i += 2
        }
        if (bits.length < 24) return null
        val code = OokDecoding.bitsToLong(bits.toString())

        // the leading short+gap preamble was required by the gate above (separator)
        val score = Scoring.score(0.58f, Scoring.quality(if (errN > 0) errSum / errN else 0.5, separator = true, exactBits = clean))

        return AnalysisResult(
            analyzerId = id,
            protocol = "PT2240 (24-bit)",
            score = score,
            summary = "code=0x%06X (Te≈%dus)".format(code, base),
            fields = listOf(
                ProtoField("code", "Code (24-bit)", "0x%06X".format(code), FieldType.HEX),
                ProtoField("te", "Base time Te [us]", base.toString(), FieldType.DEC),
                ProtoField("bits", "Decoded bits", bits.toString(), FieldType.BITS, editable = false),
            ),
            canTransmit = true,
        ) { edited ->
            val c = edited.firstOrNull { it.key == "code" }?.value?.let { parseLong(it) } ?: return@AnalysisResult null
            val te = edited.firstOrNull { it.key == "te" }?.value?.toIntOrNull() ?: base
            val outBits = OokDecoding.longToBits(c and 0xFFFFFF, 24)
            val arr = OokDecoding.encodePwm(outBits, te, appendSync = false).toMutableList()
            arr.add(te); arr.add(te * 7)   // PT2240 trailing gap
            TxFrame.OokPulses(arr.toIntArray())
        }
    }
}

// ---------------------------------------------------------------------------
// Base Manchester decoder for OOK — common line code for many sub-GHz remotes.
// Decodes the pulse-train (IEEE 802.3: 0=HIGH→LOW, 1=LOW→HIGH) into raw bits,
// lets them be edited, and re-encodes to a Manchester pulse-train.
// ---------------------------------------------------------------------------
class ManchesterAnalyzer : ProtocolAnalyzer {
    override val id = "manchester"
    override val displayName = "Manchester (OOK, bazowy)"

    override fun analyze(packet: CapturedPacket): AnalysisResult? {
        if (packet !is CapturedPacket.Ook) return null
        val us = packet.us
        if (us.size < 8) return null
        val base = OokDecoding.dominantBase(us)
        if (base <= 0) return null

        // confidence: how cleanly durations map to 1 or 2 base ticks
        var clean = 0
        for (d in us) { val t = OokDecoding.ticks(d, base); if (t == 1 || t == 2) clean++ }
        val cleanFrac = clean.toFloat() / us.size

        val levels = OokDecoding.toLevelString(us, base)
        val bits = OokDecoding.decodeManchester(levels)
        if (bits.length < 8) return null

        val hex = MonitorParser.bytesToHex(OokDecoding.bitsToBytes(bits))

        return AnalysisResult(
            analyzerId = id,
            protocol = "Manchester (OOK)",
            score = if (cleanFrac > 0.8f) 0.40f else 0.25f,
            summary = "${bits.length} bits, Te≈${base}us, hex=$hex",
            fields = listOf(
                ProtoField("bits", "Bits (Manchester)", bits, FieldType.BITS),
                ProtoField("hex", "Bytes (hex)", hex, FieldType.HEX, editable = false),
                ProtoField("te", "Half-bit Te [us]", base.toString(), FieldType.DEC),
            ),
            canTransmit = true,
        ) { edited ->
            val b = edited.firstOrNull { it.key == "bits" }?.value?.filter { it == '0' || it == '1' } ?: return@AnalysisResult null
            val te = edited.firstOrNull { it.key == "te" }?.value?.toIntOrNull() ?: base
            if (b.isEmpty()) null else TxFrame.OokPulses(OokDecoding.encodeManchester(b, te))
        }
    }
}

// ===========================================================================
// KeeLoq family (OOK PWM). A generic codec (KeeLoqCodec) does the framing;
// HCS200 / HCS300 share the 66-bit layout, Nice Flor-S uses a 52-bit frame.
// NOTE: the hopping/encrypted portion is shown as captured — no KeeLoq decrypt.
// ===========================================================================

/** Shared decode for the HCS 66-bit cleartext layout (enc / serial / btn / flags). */
private fun hcsDecode(packet: CapturedPacket, id: String, name: String): AnalysisResult? {
    if (packet !is CapturedPacket.Ook) return null
    val dr = KeeLoqCodec.decodeResult(packet.us, KeeLoqCodec.HCS) ?: return null
    val bits = dr.bits
    // same band as the decoder (KeeLoqCodec.HCS) so the shown/edited Te matches
    // what decodeResult used — including sub-250us Te remotes.
    val base = OokDecoding.dominantBaseBand(
        packet.us, KeeLoqCodec.HCS.baseLo, KeeLoqCodec.HCS.baseHi, KeeLoqCodec.HCS.baseFallback)

    val enc = KeeLoqCodec.field(bits, 0, 32)
    val serial = KeeLoqCodec.field(bits, 32, 28)
    val btn = KeeLoqCodec.field(bits, 60, 4)
    val vlow = KeeLoqCodec.field(bits, 64, 1)
    val rpt = KeeLoqCodec.field(bits, 65, 1)

    return AnalysisResult(
        analyzerId = id,
        // High specificity prior (23-pulse preamble + header + 66 exact PWM bits),
        // blended with the measured fit of this capture.
        score = Scoring.score(0.70f, Scoring.quality(dr.meanTickErr, dr.separator, dr.exact)),
        protocol = name,
        summary = "enc=0x%08X serial=0x%07X btn=%X vlow=%d rpt=%d (Te≈%dus)".format(enc, serial, btn, vlow, rpt, base),
        fields = listOf(
            ProtoField("enc", "Hopping code (32-bit, encrypted)", "0x%08X".format(enc), FieldType.HEX),
            ProtoField("serial", "Serial (28-bit)", "0x%07X".format(serial), FieldType.HEX),
            ProtoField("btn", "Button (4-bit)", "0x%X".format(btn), FieldType.HEX),
            ProtoField("vlow", "Battery low (0/1)", vlow.toString(), FieldType.DEC),
            ProtoField("rpt", "Repeat (0/1)", rpt.toString(), FieldType.DEC),
            ProtoField("te", "Base time Te [us]", base.toString(), FieldType.DEC),
        ),
        canTransmit = true,
    ) { edited -> hcsEncode(edited, base) }
}

internal fun hcsEncode(fields: List<ProtoField>, defBase: Int): TxFrame? {
    fun v(k: String) = fields.firstOrNull { it.key == k }?.value
    val enc = v("enc")?.let { parseLong(it) } ?: return null
    val serial = v("serial")?.let { parseLong(it) } ?: return null
    val btn = v("btn")?.let { parseLong(it) } ?: 0L
    val vlow = (v("vlow")?.toIntOrNull() ?: 0).toLong() and 1L
    val rpt = (v("rpt")?.toIntOrNull() ?: 0).toLong() and 1L
    val te = v("te")?.toIntOrNull() ?: defBase

    val bits = IntArray(66)
    KeeLoqCodec.setField(bits, 0, 32, enc)
    KeeLoqCodec.setField(bits, 32, 28, serial)
    KeeLoqCodec.setField(bits, 60, 4, btn)
    KeeLoqCodec.setField(bits, 64, 1, vlow)
    KeeLoqCodec.setField(bits, 65, 1, rpt)
    return TxFrame.OokPulses(KeeLoqCodec.encode(bits, te, KeeLoqCodec.HCS))
}

/** HCS200 — classic KeeLoq hopping-code remote. */
class Hcs200Analyzer : ProtocolAnalyzer {
    override val id = "hcs200"
    override val displayName = "HCS200 / KeeLoq (OOK, 66-bit)"
    override fun analyze(packet: CapturedPacket) = hcsDecode(packet, id, "HCS200 / KeeLoq (66-bit)")
}

/** HCS300 — same 66-bit on-air frame as HCS200 (counter lives inside the
 *  encrypted hopping code; serial/button/flags are cleartext). */
class Hcs300Analyzer : ProtocolAnalyzer {
    override val id = "hcs300"
    override val displayName = "HCS300 / KeeLoq (OOK, 66-bit)"
    override fun analyze(packet: CapturedPacket) = hcsDecode(packet, id, "HCS300 / KeeLoq (66-bit)")
}

// keep the old encoder id working for saved packets
internal fun hcs200Encode(fields: List<ProtoField>, defBase: Int): TxFrame? = hcsEncode(fields, defBase)

// ---------------------------------------------------------------------------
// Nice Flor-S — KeeLoq-family, 52-bit frame. Fields below are a best-effort
// slice of the on-air (encrypted) frame; editing + replay round-trips exactly.
// Timing defaults may need tuning against a real capture.
// ---------------------------------------------------------------------------
class NiceFlorSAnalyzer : ProtocolAnalyzer {
    override val id = "nice_flors"
    override val displayName = "Nice Flor-S (OOK, 52-bit)"

    override fun analyze(packet: CapturedPacket): AnalysisResult? {
        if (packet !is CapturedPacket.Ook) return null
        val dr = KeeLoqCodec.decodeResult(packet.us, KeeLoqCodec.NICE_FLORS) ?: return null
        val bits = dr.bits
        val base = OokDecoding.dominantBaseBand(packet.us, 350, 800, 500)

        val serial = KeeLoqCodec.field(bits, 0, 28)
        val counter = KeeLoqCodec.field(bits, 28, 16)
        val btn = KeeLoqCodec.field(bits, 44, 4)
        val flags = KeeLoqCodec.field(bits, 48, 4)

        return AnalysisResult(
            analyzerId = id,
            protocol = "Nice Flor-S (52-bit)",
            // Lower prior: no header, 52-bit slice is a best-effort framing.
            score = Scoring.score(0.50f, Scoring.quality(dr.meanTickErr, dr.separator, dr.exact)),
            summary = "serial=0x%07X cnt=%d btn=%X (Te≈%dus) — encrypted frame".format(serial, counter, btn, base),
            fields = listOf(
                ProtoField("serial", "Serial (28-bit)", "0x%07X".format(serial), FieldType.HEX),
                ProtoField("counter", "Counter (16-bit)", counter.toString(), FieldType.DEC),
                ProtoField("btn", "Button (4-bit)", "0x%X".format(btn), FieldType.HEX),
                ProtoField("flags", "Flags (4-bit)", "0x%X".format(flags), FieldType.HEX),
                ProtoField("te", "Base time Te [us]", base.toString(), FieldType.DEC),
            ),
            canTransmit = true,
        ) { edited -> niceFlorSEncode(edited, base) }
    }
}

internal fun niceFlorSEncode(fields: List<ProtoField>, defBase: Int): TxFrame? {
    fun v(k: String) = fields.firstOrNull { it.key == k }?.value
    val serial = v("serial")?.let { parseLong(it) } ?: return null
    val counter = v("counter")?.let { parseLong(it) } ?: 0L
    val btn = v("btn")?.let { parseLong(it) } ?: 0L
    val flags = v("flags")?.let { parseLong(it) } ?: 0L
    val te = v("te")?.toIntOrNull() ?: defBase
    val bits = IntArray(52)
    KeeLoqCodec.setField(bits, 0, 28, serial)
    KeeLoqCodec.setField(bits, 28, 16, counter)
    KeeLoqCodec.setField(bits, 44, 4, btn)
    KeeLoqCodec.setField(bits, 48, 4, flags)
    return TxFrame.OokPulses(KeeLoqCodec.encode(bits, te, KeeLoqCodec.NICE_FLORS))
}

// ---------------------------------------------------------------------------
// Somfy RTS (OOK, 640us half-clock, Manchester/NRZ). Ported from v1: hardware
// sync preamble (24 or 12 repeated) + soft sync (~4800us) + coded bits. The
// payload is shown as captured (encrypted rolling code — not decrypted).
// ---------------------------------------------------------------------------
class SomfyAnalyzer : ProtocolAnalyzer {
    override val id = "somfy"
    override val displayName = "Somfy RTS (OOK, Manchester)"

    private val HALF = 640
    private val SOFT_SYNC = 4800
    private val CODED_BITS = 81

    override fun analyze(packet: CapturedPacket): AnalysisResult? {
        if (packet !is CapturedPacket.Ook) return null
        val us = packet.us
        if (us.size < 106) return null
        val base = OokDecoding.dominantBaseBand(us, 500, 800, HALF)
        if (base <= 0) return null
        fun t(i: Int) = OokDecoding.ticks(us[i], base)

        // hardware-sync preamble: pulses of ~4..5 ticks
        var i = 0; var pre = 0
        while (i < us.size) {
            val tt = t(i)
            if (tt in 4..5) { pre++; if (pre == 24 || (pre == 12 && i > 30)) { i++; break } } else pre = 0
            i++
        }
        if (pre < 12) return null
        // soft sync ~4800us
        if (i >= us.size) return null
        if (us[i] in (SOFT_SYNC - HALF / 2)..(SOFT_SYNC + HALF / 2 + HALF / 10)) i++ else return null

        val bits = StringBuilder()
        var errSum = 0.0; var errN = 0
        while (i < us.size - 1 && bits.length < CODED_BITS) {
            val p0 = t(i); val p1 = t(i + 1)
            when {
                p0 == 1 && p1 == 1 -> { bits.append('1'); errSum += OokDecoding.tickError(us[i], base) + OokDecoding.tickError(us[i + 1], base); errN += 2; i += 2 }
                p0 == 2 -> { bits.append('0'); errSum += OokDecoding.tickError(us[i], base); errN += 1; i += 1 }
                p0 == 1 && p1 == 3 -> { bits.append('1'); errSum += OokDecoding.tickError(us[i], base); errN += 1; i += 2 }
                else -> return null
            }
        }
        if (bits.length < CODED_BITS - 1) return null

        val frame = bits.toString()
        val meanErr = if (errN > 0) errSum / errN else 0.5
        // hardware + soft sync were matched by the gate (separator); completeness
        // = did we recover the full coded-bit count.
        val somfyScore = Scoring.score(0.62f,
            Scoring.quality(meanErr, separator = true, exactBits = frame.length == CODED_BITS))
        val hex = MonitorParser.bytesToHex(OokDecoding.bitsToBytes(frame))
        // The 81 payload bits are presented as three codes over a byte-aligned
        // (88-bit / 11-byte) view, MSB-first: code0 = bits[0..31],
        // code1 = bits[32..63], code3 = bits[64..87] (the last 7 bits are pad).
        val padded = frame.padEnd(88, '0')
        val code0 = OokDecoding.bitsToLong(padded.substring(0, 32))
        val code1 = OokDecoding.bitsToLong(padded.substring(32, 64))
        val code3 = OokDecoding.bitsToLong(padded.substring(64, 88))

        return AnalysisResult(
            analyzerId = id,
            protocol = "Somfy RTS (${frame.length} bit)",
            score = somfyScore,
            summary = "frame=$hex (Te≈${base}us) — encrypted rolling code",
            fields = listOf(
                ProtoField("code0", "Code0 (32-bit)", "0x%08X".format(code0), FieldType.HEX),
                ProtoField("code1", "Code1 (32-bit)", "0x%08X".format(code1), FieldType.HEX),
                ProtoField("code3", "Code3 (24-bit)", "0x%06X".format(code3), FieldType.HEX),
                ProtoField("next_code", "Next code (0-12)", "0", FieldType.TICKER, min = 0, max = 12),
                ProtoField("te", "Half-bit Te [us]", base.toString(), FieldType.DEC),
                ProtoField("len", "Frame bits", frame.length.toString(), FieldType.DEC, editable = false),
            ),
            canTransmit = true,
        ) { edited -> somfyEncode(edited, base) }
    }
}

/**
 * Somfy "next key" XOR table, indexed by next_code (0..12). Index 0 is a no-op;
 * a non-zero next_code XORs code0 (the 32-bit first code word) by this value to
 * produce the next rolling key before the frame is reassembled.
 */
internal val SOMFY_NEXT_KEY_XOR = longArrayOf(
    0x00000000, 0x00018180, 0x00028280, 0x00048480, 0x00088880, 0x00090900,
    0x00050500, 0x000F0F00, 0x000E8E80, 0x000C0C00, 0x00060600, 0x00078780, 0x00030300,
)

internal fun somfyEncode(fields: List<ProtoField>, defBase: Int): TxFrame? {
    val te = fields.firstOrNull { it.key == "te" }?.value?.toIntOrNull() ?: defBase

    // Build the payload bit string. New scheme: reassemble from code0/code1/code3
    // (with the next_code XOR applied to code0). Fall back to a raw "bits" field
    // for packets saved under the old scheme.
    val hasCodes = fields.any { it.key == "code0" }
    val rawBits = fields.firstOrNull { it.key == "bits" }?.value?.filter { it == '0' || it == '1' }
    val bits: String = if (!hasCodes && rawBits != null) {
        rawBits
    } else {
        val c0base = fields.firstOrNull { it.key == "code0" }?.value?.let { parseLong(it) } ?: return null
        val c1 = (fields.firstOrNull { it.key == "code1" }?.value?.let { parseLong(it) } ?: 0L) and 0xFFFFFFFFL
        val c3 = (fields.firstOrNull { it.key == "code3" }?.value?.let { parseLong(it) } ?: 0L) and 0xFFFFFFL
        val next = fields.firstOrNull { it.key == "next_code" }?.value?.toIntOrNull()?.coerceIn(0, 12) ?: 0
        val len = (fields.firstOrNull { it.key == "len" }?.value?.toIntOrNull() ?: 81).coerceIn(1, 88)
        var c0 = c0base and 0xFFFFFFFFL
        if (next != 0) c0 = c0 xor SOMFY_NEXT_KEY_XOR[next]
        val assembled = OokDecoding.longToBits(c0, 32) +
            OokDecoding.longToBits(c1, 32) +
            OokDecoding.longToBits(c3, 24)
        assembled.take(len)
    }
    if (bits.isEmpty()) return null
    val out = ArrayList<Int>()
    // 24-pulse hardware sync: first is (4+1)*Te, rest 4*Te
    for (p in 0 until 24) out.add(if (p == 0) 5 * te else 4 * te)
    out.add(4800)   // soft sync
    for (c in bits) {
        if (c == '1') { out.add(1 * te); out.add(1 * te) } else { out.add(2 * te) }
    }
    // trailing inter-frame gap: long enough that the decoder stops cleanly
    // without reading it as an extra bit (do NOT merge into the last data pulse)
    out.add(16 * te)
    return TxFrame.OokPulses(out.toIntArray())
}

// ---------------------------------------------------------------------------
// DB200 (OOK, 333us, 4-bit PWM). Ported from v1: 8×(short,long) preamble, then
// 4 data bits (1 = short+short, 0 = long+short). code is a 4-bit value.
// ---------------------------------------------------------------------------
class Db200Analyzer : ProtocolAnalyzer {
    override val id = "db200"
    override val displayName = "DB200 (OOK, 4-bit)"

    override fun analyze(packet: CapturedPacket): AnalysisResult? {
        if (packet !is CapturedPacket.Ook) return null
        val us = packet.us
        if (us.size < 24) return null
        val base = OokDecoding.dominantBaseBand(us, 200, 500, 333)
        if (base <= 0) return null
        fun t(i: Int) = OokDecoding.ticks(us[i], base)

        // 8× (short, long) preamble
        var i = 0; var pre = 0
        while (i + 1 < us.size) {
            if (t(i) == 1 && t(i + 1) == 2) { pre++; i += 2; if (pre == 8) break }
            else { pre = 0; i++ }
        }
        if (pre != 8) return null

        // 4 PWM bits, MSB-first
        var code = 0; var n = 0
        while (i + 1 < us.size && n < 4) {
            val p0 = t(i); val p1 = t(i + 1)
            when {
                p0 == 1 && (p1 == 1 || (p1 >= 1 && n == 3)) -> { code = (code shl 1) or 1; n++ }
                p0 == 2 && (p1 == 1 || (p1 >= 2 && n == 3)) -> { code = (code shl 1); n++ }
                else -> return null
            }
            i += 2
        }
        if (n < 4) return null

        return AnalysisResult(
            analyzerId = id,
            protocol = "DB200 (4-bit)",
            score = 0.70f,
            summary = "code=0x%X (Te≈%dus)".format(code, base),
            fields = listOf(
                ProtoField("code", "Code (4-bit)", "0x%X".format(code), FieldType.HEX),
                ProtoField("te", "Base time Te [us]", base.toString(), FieldType.DEC),
            ),
            canTransmit = true,
        ) { edited -> db200Encode(edited, base) }
    }
}

internal fun db200Encode(fields: List<ProtoField>, defBase: Int): TxFrame? {
    fun v(k: String) = fields.firstOrNull { it.key == k }?.value
    val code = (v("code")?.let { parseLong(it) } ?: return null).toInt() and 0x0f
    val te = v("te")?.toIntOrNull() ?: defBase
    val out = ArrayList<Int>()
    repeat(8) { out.add(1 * te); out.add(2 * te) }          // preamble
    for (b in 3 downTo 0) {                                  // 4 bits MSB-first
        if ((code shr b) and 1 == 1) { out.add(1 * te); out.add(1 * te) }
        else { out.add(2 * te); out.add(1 * te) }
    }
    out.add(1 * te)            // frame-end short
    out.add(12000)             // DB200 guard gap
    return TxFrame.OokPulses(out.toIntArray())
}

// shared hex/dec parse for "0x.." or decimal
private fun parseLong(s: String): Long? {
    val t = s.trim()
    return try {
        if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2).toLong(16)
        else t.toLong()
    } catch (e: Exception) { null }
}
