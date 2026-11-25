/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.cli

import org.json.JSONObject

/**
 * Recognises and parses firmware `monitor` output lines, in both JSON and HEX
 * forms, as emitted by Monitor.ino:
 *
 * OOK JSON:
 *   { "iface":"r0", "ts":400352, "mod":"ook", "rssi":-62, "lqi":40, "n":50, "us":[310,290,...] }
 * OOK HEX:
 *   r0 ts=400352 ook rssi=-62 lqi=40 n=50 0136012202...
 *
 * FSK JSON:
 *   { "iface":"r0", "ts":40188, "mod":"2fsk", "rssi":-71, "lqi":35, "crc":true, "len":16, "hex":"a1b2.." }
 * FSK HEX:
 *   r0 ts=40188 2fsk rssi=-71 lqi=35 crc=1 len=16 a1b2c3...
 */
object MonitorParser {

    private val MODS = setOf("ook", "2fsk", "gfsk", "4fsk", "msk")

    /** True if the line looks like a monitor capture (either form). */
    fun isMonitorLine(line: String): Boolean {
        val t = line.trim()
        if (t.startsWith("{") && t.contains("\"mod\"") && t.contains("\"iface\"")) return true
        // hex form: "<iface> ts=<n> <mod> rssi=..."
        val parts = t.split(Regex("\\s+"))
        return parts.size >= 4 &&
            parts[0].matches(Regex("r\\d")) &&
            parts[1].startsWith("ts=") &&
            parts[2] in MODS
    }

    /** Parse to a [CapturedPacket], or null if it cannot be parsed. */
    fun parse(line: String): CapturedPacket? {
        val t = line.trim()
        return if (t.startsWith("{")) parseJson(t) else parseHex(t)
    }

    // ---- swept-spectrum ("sweep") lines ---------------------------------
    //
    // JSON: { "sweep":"r0", "ts":123, "f0":433000000, "df":25000, "n":73, "rbw":58035, "rssi":"5A5B.." }
    // HEX : sweep r0 ts=123 f0=433000000 df=25000 n=73 rbw=58035 5A5B..

    /** True if the line is a sweep waterfall row (either form). */
    fun isSweepLine(line: String): Boolean {
        val t = line.trim()
        if (t.startsWith("{") && t.contains("\"sweep\"")) return true
        val parts = t.split(Regex("\\s+"))
        return parts.size >= 7 && parts[0] == "sweep" &&
            parts[1].matches(Regex("r\\d")) && parts[2].startsWith("ts=")
    }

    /** Parse a sweep row, or null. */
    fun parseSweep(line: String): SweepLine? {
        val t = line.trim()
        return try {
            if (t.startsWith("{")) {
                val o = JSONObject(t)
                SweepLine(
                    iface = o.optString("sweep", "r0"),
                    ts = o.optLong("ts"),
                    f0Hz = o.optLong("f0"),
                    dfHz = o.optLong("df"),
                    n = o.optInt("n"),
                    rbwHz = o.optLong("rbw"),
                    rssiRaw = hexBytesToInts(o.optString("rssi")),
                )
            } else {
                val parts = t.split(Regex("\\s+"))
                val map = HashMap<String, String>()
                for (p in parts) {
                    val eq = p.indexOf('=')
                    if (eq > 0) map[p.substring(0, eq)] = p.substring(eq + 1)
                }
                SweepLine(
                    iface = parts[1],
                    ts = map["ts"]?.toLongOrNull() ?: 0,
                    f0Hz = map["f0"]?.toLongOrNull() ?: 0,
                    dfHz = map["df"]?.toLongOrNull() ?: 0,
                    n = map["n"]?.toIntOrNull() ?: 0,
                    rbwHz = map["rbw"]?.toLongOrNull() ?: 0,
                    rssiRaw = hexBytesToInts(parts.last()),
                )
            }.takeIf { it.rssiRaw.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** "5A5B..." → [0x5A, 0x5B, ...] as unsigned ints (2 hex digits per point). */
    private fun hexBytesToInts(hex: String): IntArray {
        val c = hex.trim().filter { !it.isWhitespace() }
        val n = c.length / 2
        return IntArray(n) { c.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    private fun parseJson(t: String): CapturedPacket? = try {
        val o = JSONObject(t)
        val iface = o.optString("iface", "r0")
        val ts = o.optLong("ts")
        val mod = o.optString("mod")
        val rssi = o.optInt("rssi")
        val lqi = o.optInt("lqi")
        // A "us" array means a pulse-train: OOK, or any modulation captured in the
        // firmware's `direct` async-GDO0 mode (e.g. 2-FSK carrying AFSK). Packet mode
        // emits "hex" instead.
        if (o.has("us")) {
            val arr = o.optJSONArray("us")
            val us = IntArray(arr?.length() ?: 0) { arr!!.getInt(it) }
            CapturedPacket.Ook(iface, ts, rssi, lqi, us, t)
        } else {
            val hex = o.optString("hex")
            CapturedPacket.Fsk(
                iface, ts, rssi, lqi, mod, o.optBoolean("crc"), hexToBytes(hex), t,
                addr = if (o.has("addr")) o.optInt("addr") else null,
                crcEn = if (o.has("crcen")) o.optBoolean("crcen") else null,
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun parseHex(t: String): CapturedPacket? = try {
        val p = t.split(Regex("\\s+"))
        val iface = p[0]
        val ts = p[1].removePrefix("ts=").toLong()
        val mod = p[2]
        val map = HashMap<String, String>()
        var payloadIdx = 3
        for (i in 3 until p.size) {
            val kv = p[i]
            val eq = kv.indexOf('=')
            if (eq > 0) {
                map[kv.substring(0, eq)] = kv.substring(eq + 1)
                payloadIdx = i + 1
            } else break
        }
        val rssi = map["rssi"]?.toIntOrNull() ?: 0
        val lqi = map["lqi"]?.toIntOrNull() ?: 0
        val payload = if (payloadIdx < p.size) p[payloadIdx] else ""
        if (mod == "ook") {
            // payload = concatenated 4-hex-digit big-endian microsecond values
            val us = IntArray(payload.length / 4) {
                payload.substring(it * 4, it * 4 + 4).toInt(16)
            }
            CapturedPacket.Ook(iface, ts, rssi, lqi, us, t)
        } else {
            val crc = map["crc"] == "1"
            CapturedPacket.Fsk(
                iface, ts, rssi, lqi, mod, crc, hexToBytes(payload), t,
                addr = map["addr"]?.toIntOrNull(),
                crcEn = map["crcen"]?.let { it == "1" },
            )
        }
    } catch (e: Exception) {
        null
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().filter { !it.isWhitespace() }
        if (clean.length < 2) return ByteArray(0)
        val n = clean.length / 2
        return ByteArray(n) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun bytesToHex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
