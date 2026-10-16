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

    private fun parseJson(t: String): CapturedPacket? = try {
        val o = JSONObject(t)
        val iface = o.optString("iface", "r0")
        val ts = o.optLong("ts")
        val mod = o.optString("mod")
        val rssi = o.optInt("rssi")
        val lqi = o.optInt("lqi")
        if (mod == "ook") {
            val arr = o.optJSONArray("us")
            val us = IntArray(arr?.length() ?: 0) { arr!!.getInt(it) }
            CapturedPacket.Ook(iface, ts, rssi, lqi, us, t)
        } else {
            val hex = o.optString("hex")
            CapturedPacket.Fsk(iface, ts, rssi, lqi, mod, o.optBoolean("crc"), hexToBytes(hex), t)
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
            CapturedPacket.Fsk(iface, ts, rssi, lqi, mod, crc, hexToBytes(payload), t)
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
